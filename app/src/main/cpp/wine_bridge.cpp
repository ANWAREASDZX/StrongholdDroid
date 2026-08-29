// wine_bridge.cpp — StrongholdDroid Wine process supervisor (impl).
//
// This file implements everything declared in wine_bridge.h. It uses a
// minimal subset of POSIX (fork, execvp, pipe, dup2, setsid) so that the
// logic is identical on real Android devices and on the build-time
// x86_64 emulator.
//
// Architecture ("Arm64 Wine WOW64"):
//   • The wine loader binary ships inside the APK runtime asset
//     (assets/prebuilt.zip → extracted to filesDir/usr/bin/wine) and is
//     an ARM64 ELF that runs NATIVELY — no instruction translation for
//     wine itself.
//   • The game's 32-bit x86 code is executed INSIDE the wine process by
//     box64's WoW64 cpu dll (wowbox64.dll, staged as xtajit.dll in the
//     WINEPREFIX by EnvironmentBuilder).  There is no separate box64
//     process and no box64 library to link against.

#include "wine_bridge.h"

#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/prctl.h>
#include <string>
#include <vector>
#include <unordered_map>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <cerrno>
#include <atomic>

#define LOG_TAG "strongholddroid-wine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

thread_local std::string g_last_error;

void set_last_error(const char* msg) {
    g_last_error = msg ? msg : "(no message)";
}

// Map a high-level pipe fd to stdin/stdout/stderr of the child.
void redirect_stdio(int child_fd, int target_fd) {
    if (child_fd >= 0) {
        dup2(child_fd, target_fd);
        close(child_fd);
    }
}

struct PidInfo {
    pid_t wine_pid;
    pid_t wineserver_pid;
    std::string wine_prefix;
};

// Per-process registry of launched Wine processes so wait_for_exit/force_kill
// can look them up. Keyed by the integer PID that we hand back to Kotlin.
std::unordered_map<int, PidInfo> g_pids;
pthread_mutex_t   g_pids_lock = PTHREAD_MUTEX_INITIALIZER;
std::atomic<int>  g_next_pid_token{1};

int register_pid(PidInfo info) {
    pthread_mutex_lock(&g_pids_lock);
    int token = g_next_pid_token.fetch_add(1);
    g_pids[token] = std::move(info);
    pthread_mutex_unlock(&g_pids_lock);
    return token;
}

PidInfo lookup_pid(int token) {
    pthread_mutex_lock(&g_pids_lock);
    auto it = g_pids.find(token);
    PidInfo copy = (it == g_pids.end()) ? PidInfo{-1, -1, {}} : it->second;
    pthread_mutex_unlock(&g_pids_lock);
    return copy;
}

void forget_pid(int token) {
    pthread_mutex_lock(&g_pids_lock);
    g_pids.erase(token);
    pthread_mutex_unlock(&g_pids_lock);
}

// Per-child process: pump child stdout/stderr → logcat.
// Runs in its own pthread; lifetime = lifetime of the child.
void* log_pump_thread(void* arg) {
    int* pipe_fd_ptr = static_cast<int*>(arg);
    int fd = *pipe_fd_ptr;
    delete pipe_fd_ptr;

    FILE* fp = fdopen(fd, "r");
    if (!fp) { close(fd); return nullptr; }

    char* line = nullptr;
    size_t cap = 0;
    while (getline(&line, &cap, fp) > 0) {
        // Strip trailing newline
        line[strcspn(line, "\r\n")] = 0;
        __android_log_write(ANDROID_LOG_INFO, "wine-stdout", line);
    }
    free(line);
    fclose(fp);
    return nullptr;
}

int spawn_log_pump(int child_stdout_fd) {
    int* fd_ptr = new int(child_stdout_fd);
    pthread_t thr;
    if (pthread_create(&thr, nullptr, log_pump_thread, fd_ptr) != 0) {
        delete fd_ptr;
        return -1;
    }
    pthread_detach(thr);
    return 0;
}

bool build_env_vector(const strongholddroid::wine::LaunchOptions* opts,
                       std::vector<std::string>& storage,
                       std::vector<const char*>& out) {
    // User-supplied env vars first
    for (size_t i = 0; i < opts->env_kv_count; ++i) {
        storage.emplace_back(opts->env_kv[i]);
    }
    // Always set these defaults — Wine behaves badly without them.
    storage.emplace_back(std::string("WINEPREFIX=")  + opts->wine_prefix);
    storage.emplace_back(std::string("WINEARCH=")   + opts->wine_arch);
    storage.emplace_back("WINEDEBUG=-all");                 // silence Wine's spam
    storage.emplace_back("WINEDLLOVERRIDES=winemenubuilder.exe=d");
    if (opts->wow64_enabled)  storage.emplace_back("WINEESYNC=1");
    if (opts->esync_enabled)  storage.emplace_back("WINEESYNC=1");
    if (opts->fsync_enabled)  storage.emplace_back("WINEFSYNC=1");
    if (opts->audio_pipe_path) {
        storage.emplace_back(std::string("PULSE_PIPE=") + opts->audio_pipe_path);
    }
    if (opts->render_target_width && opts->render_target_height) {
        char buf[64];
        snprintf(buf, sizeof(buf), "STRONGHOLDDROID_RENDER_TARGET=%ux%u",
                 opts->render_target_width, opts->render_target_height);
        storage.emplace_back(buf);
    }
    for (auto& s : storage) out.push_back(s.c_str());
    out.push_back(nullptr);
    return true;
}

}  // namespace

namespace strongholddroid { namespace wine {

bool init(JNIEnv* /*env*/) noexcept {
    // Block SIGCHLD by default in this thread — we explicitly reap children in
    // wait_for_exit. This prevents zombie accumulation if the parent loses the
    // race with the SIGCHLD handler.
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask, SIGCHLD);
    pthread_sigmask(SIG_BLOCK, &mask, nullptr);
    LOGI("wine_bridge: initialized");
    return true;
}

void shutdown(JNIEnv* /*env*/) noexcept {
    pthread_mutex_lock(&g_pids_lock);
    for (auto& [token, info] : g_pids) {
        force_kill(token);
    }
    g_pids.clear();
    pthread_mutex_unlock(&g_pids_lock);
    LOGI("wine_bridge: shutdown complete");
}

int launch_game(const LaunchOptions* opts) noexcept {
    if (!opts || !opts->wine_bin_path || !opts->wine_prefix || !opts->game_exec) {
        set_last_error("invalid LaunchOptions");
        return -1;
    }

    // Make sure the prefix exists and is writable. mkdir -p semantics.
    auto mkdir_p = [](const std::string& p) {
        for (size_t i = 1; i < p.size(); ++i) {
            if (p[i] == '/') {
                std::string sub = p.substr(0, i);
                mkdir(sub.c_str(), 0700);
            }
        }
        mkdir(p.c_str(), 0700);
    };
    mkdir_p(opts->wine_prefix);

    int stdout_pipe[2];
    if (pipe(stdout_pipe) != 0) {
        set_last_error("pipe() failed");
        return -1;
    }
    int stderr_pipe[2];
    if (pipe(stderr_pipe) != 0) {
        set_last_error("pipe(stderr) failed");
        close(stdout_pipe[0]); close(stdout_pipe[1]);
        return -1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        set_last_error("fork() failed");
        close(stdout_pipe[0]); close(stdout_pipe[1]);
        close(stderr_pipe[0]); close(stderr_pipe[1]);
        return -1;
    }

    if (pid == 0) {
        // ----- child -----
        // Become a process-group leader so we can kill the whole tree later.
        setsid();
        prctl(PR_SET_PDEATHSIG, SIGTERM);  // kill us if the JVM dies

        redirect_stdio(stdout_pipe[1], STDOUT_FILENO);
        redirect_stdio(stderr_pipe[1], STDERR_FILENO);
        // stdin from /dev/null so Wine doesn't read keystrokes from logcat fd
        int devnull = open("/dev/null", O_RDONLY);
        if (devnull >= 0) { dup2(devnull, STDIN_FILENO); close(devnull); }
        close(stdout_pipe[0]);
        close(stderr_pipe[0]);

        // Build env vector
        std::vector<std::string> env_storage;
        std::vector<const char*> env_argv;
        build_env_vector(opts, env_storage, env_argv);

        // argv:  wine_bin_path game_exec  (wine's CLI takes no arch
        // argument — WINEARCH comes in via the env vector, and the wow64
        // loader auto-detects 32-bit executables).
        std::vector<const char*> argv;
        argv.push_back(opts->wine_bin_path);
        argv.push_back(opts->game_exec);
        argv.push_back(nullptr);

        execve(argv[0], const_cast<char* const*>(argv.data()),
               const_cast<char* const*>(env_argv.data()));
        // If execve returns, it failed.
        _exit(EXIT_FAILURE);
    }

    // ----- parent -----
    close(stdout_pipe[1]);
    close(stderr_pipe[1]);
    spawn_log_pump(stdout_pipe[0]);
    spawn_log_pump(stderr_pipe[0]);

    int token = register_pid(PidInfo{
        .wine_pid = pid,
        .wineserver_pid = -1,
        .wine_prefix = opts->wine_prefix,
    });
    LOGI("wine_bridge: launched wine (pid=%d, token=%d) for %s",
         pid, token, opts->game_exec);
    return token;
}

int wait_for_exit(int token) noexcept {
    PidInfo info = lookup_pid(token);
    if (info.wine_pid <= 0) {
        set_last_error("invalid pid token");
        return -1;
    }

    int status = 0;
    // Reap the wine64 process.
    pid_t waited = waitpid(info.wine_pid, &status, 0);
    if (waited < 0 && errno != ECHILD) {
        set_last_error("waitpid failed");
        LOGW("wait_for_exit: waitpid(%d) errno=%d", info.wine_pid, errno);
        return -1;
    }

    // Best-effort: kill wineserver so it doesn't leak between sessions.
    if (info.wineserver_pid > 0) {
        kill(info.wineserver_pid, SIGTERM);
    }

    int exit_code = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
    LOGI("wait_for_exit: token=%d exit_code=%d", token, exit_code);
    forget_pid(token);
    return exit_code;
}

void request_shutdown(int token) noexcept {
    PidInfo info = lookup_pid(token);
    if (info.wine_pid > 0) {
        // Send SIGTERM to the whole process group (negative pid).
        kill(-info.wine_pid, SIGTERM);
        LOGI("request_shutdown: SIGTERM -> pgid=%d", info.wine_pid);
    }
}

void force_kill(int token) noexcept {
    PidInfo info = lookup_pid(token);
    if (info.wine_pid > 0) {
        kill(-info.wine_pid, SIGKILL);
        LOGW("force_kill: SIGKILL -> pgid=%d", info.wine_pid);
    }
}

const char* last_error() noexcept {
    return g_last_error.c_str();
}

}}  // namespace strongholddroid::wine
