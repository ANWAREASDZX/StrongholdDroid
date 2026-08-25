# دليل استكشاف الأخطاء وإصلاحها لـ StrongholdDroid

> جداول منظمة حسب الأعراض: العَرَض → الأسباب المحتملة → الحلول التدريجية.

---

## جدول المحتويات

1. [مشاكل التثبيت](#مشاكل-التثبيت)
2. [مشاكل التشغيل](#مشاكل-التشغيل)
3. [مشاكل الرسوميات](#مشاكل-الرسوميات)
4. [مشاكل الصوت](#مشاكل-الصوت)
5. [مشاكل الأداء](#مشاكل-الأداء)
6. [مشاكل التحكم](#مشاكل-التحكم)
7. [مشاكل الحفظ](#مشاكل-الحفظ)
8. [مشاكل الـ Gamepad](#مشاكل-الـ-gamepad)
9. [مشاكل الاستقرار](#مشاكل-الاستقرار)
10. [تجميع الـ crash dumps](#تجميع-الـ-crash-dumps)

---

## مشاكل التثبيت

### العَرَض: "App not installed" عند تثبيت APK

| السبب المحتمل | الحل |
|---------------|------|
| نسخة قديمة مثبتة بنفس applicationId | أزل النسخة القديمة: `adb uninstall com.strongholddroid.emulator.debug` |
| إنكار مصدر غير معروف | الإعدادات → الأمان → السماح بمصادر غير معروقة |
| عدم توافق ABI (جهاز x86) | StrongholdDroid يدعم arm64-v8a فقط — لا يعمل على أجهزة Intel/AMD 32-bit |
| مساحة فارغة غير كافية | حرّر 2 GB+ على الأقل |

### العَرَض: التطبيق يفتح ثم يغلق فوراً

| السبب المحتمل | الحل |
|---------------|------|
| مكتبة JNI مفقودة (libstrongholddroid_jni.so) | أعد تثبيت APK — تحقّق عبر `adb logcat \| grep JNI_OnLoad` |
| منع SELinux (الأجهزة المروتية) | إيقاف SELinux مؤقتاً أو استخدام non-root mode |
| إصدار أندرويد قديم جدًّا | تحقّق من API >= 26 في الإعدادات → الهاتف → الإصدار |

---

## مشاكل التشغيل

### العَرَض: شاشة سوداء بعد "تشغيل"

| السبب المحتمل | الحل التدريجي |
|---------------|---------------|
| 1. Wine prefix لم يُهيّأ بعد | انتظر 30 ثانية — أول تشغيل لكل لعبة يأخذ وقتاً |
| 2. ملفات اللعبة غير موجودة | تحقّق من الإعدادات → المكتبة → حجم التثبيت |
| 3. Vulkan driver مفقود | الإعدادات → الرسوميات → فعّل gl4es بدلاً من DXVK |
| 4. Wine binary مفقود | الإعدادات → التشخيص → تحقّق من الباينري |
| 5. الذاكرة منخفضة جدًّا | أغلق التطبيقات الأخرى، أعد المحاولة |

**التشخيص المتقدم**:

```bash
adb logcat -s strongholddroid-wine:V wine-stdout:V wine-stderr:V
```

### العَرَض: الشاشة الأصلية للعبة صغيرة جداً

| السبب | الحل |
|-------|------|
| دقة SurfaceView أصغر من شاشة الهاتف | الإعدادات → الرسوميات → اضبط render target على `1920×1080` |
| SC 1.1 يدعم فقط 1024×768 | هذا طبيعي — سيُعرض مع أطراف سوداء (letterbox) |

### العَرَض: لا شيء يظهر على الشاشة، لكن الصوت يعمل

| السبب المحتمل | الحل |
|---------------|------|
| SurfaceView لم يستهلك ANativeWindow | أعد تشغيل GameManagerActivity (اضغط Back مرتين) |
| Vulkan swapchain لم يُنشأ | تحقّق من `adb logcat \| grep vkCreateSwapchain` |
| Composition破了? أوقف "pointer location" في خيارات المطوّر | إيقاف خيار "Show touches" في خيارات المطور |

---

## مشاكل الرسوميات

### العَرَض: OpenGL error 1282 في logcat

```
wine-stderr: err:d3d:wined3d_context_gl_create ... GL_INVALID_OPERATION (1282)
```

| السبب المحتمل | الحل |
|---------------|------|
| Adreno driver bug في GL 4.x core profile | بدّل إلى DXVK (Vulkan) — أكثر استقراراً |
| gl4es غير مهيّأ | تحقّق من `libGL.so` في `app/src/main/cpp/prebuilt/arm64-v8a/` |
| Mesa libgl conflict | لا يحدث على أندرويد — تجاهل |

### العَرَض: الفنون (textures) سوداء أو ناقصة

| السبب | الحل |
|-------|------|
| Palette mode (SC 1.1) لم يُترجم | بدّل إلى wined3d-Zink بدل gl4es |
| GL_EXT_unpack_subimage مفقود | الإعدادات → الرسوميات → فعّل "Software ddraw fallback" |
| ملفات اللعبة تالفة | أعد تثبيت ملفات اللعبة |

### العَرَض: tearing أو شقوق في الصورة

| السبب | الحل |
|-------|------|
| VSync معطل | الإعدادات → الرسوميات → فعّل "VSync" |
| FPS أعلى من 60 | خفّض الإطار المستهدف إلى 30 |
| Composition破了؟ | أوقف "Disable HW overlays" في خيارات المطوّر |

### العَرَض: تدوير الكاميرا يسبب شاشة سوداء لمدة ثانية

| السبب | الحل |
|-------|------|
| DXVK shader cache miss | طبيعي في أول دوران — يحدث مرة واحدة لكل زاوية |
| Shader cache غير محفوظ | تحقّق من أن `<filesDir>/prefixes/<slug>/drive_c/users/<user>/AppData/Local/Stronghold_Crusader/DXVKStateCache/` قابل للكتابة |

### العَرَض: خط النص يبدو مشوّهاً

| السبب | الحل |
|-------|------|
| SC 1.1 يستخدم خط Tahoma غير موجود | ثبّت `winetricks corefonts` عبر الإعدادات → المكتبة → winetricks |
| اتجاه RTL يكسر النص | الإعدادات → التحكم → إيقاف "Force RTL" |

---

## مشاكل الصوت

### العَرَض: لا يوجد صوت نهائياً

| السبب المحتمل | الحل |
|---------------|------|
| Volume على الهاتف = 0 | ارفع مستوى الصوت |
| إذن POST_NOTIFICATIONS مرفوض | الإعدادات → التطبيقات → StrongholdDroid → الأذونات |
| PulseAudio stub لم يبدأ | تحقّق من `adb logcat \| grep AudioBridge` |
| FIFO غير قابل للكتابة | احذف `<cacheDir>/pulse-audio.fifo` وأعد المحاولة |

### العَرَض: الصوت متأخر (~200 ms)

| السبب | الحل |
|-------|------|
| AAudio في SAFE mode (default) | الإعدادات → الصوت → فعّل "Low latency audio" |
| Buffer كبير | خفّض buffer frames في `audio_bridge.cpp` من 441 إلى 256 |
| Bluetooth A2DP latency | هذا حدّ A2DP — استخدم سماعة سلكية |

### العَرَض: مؤثرات صوتية مفقودة (SFX) في المعارك الكبيرة

| السبب | الحل |
|-------|------|
| Wine 6.0 dsound voices limit (64) | حدّث Wine إلى 9.0 — يرفع الحد إلى 512 |
| Stronghold Extreme يطلّب >500 صوت | الإعدادات → الصوت → فعّل "Aggressive voice pooling" |
| PulseAudio stub dropouts | خفّض عدد المؤثرات في اللعبة نفسها (الإعدادات داخل اللعبة) |

### العَرَض: نقرات أو طنين (clipping)

| السبب | الحل |
|-------|------|
| Volume مرتفع جدًّا | خفّض 10 dB |
| PulseAudio resampler bug | تحقّق من `LIBPULSE_SAMPLE_RATE=44100` في env |

---

## مشاكل الأداء

### العَرَض: FPS منخفضة (<20) في المعارك الكبيرة

| السبب المحتمل | الحل التدريجي |
|---------------|---------------|
| 1. Dynamic Resolution معطل | الإعدادات → الأداء → فعّل "تحجيم ديناميكي" |
| 2. Box64 dynarec معطل | تحقّق من `BOX64_DYNAREC=1` في logcat |
| 3. أكثر من ~500 وحدة على الشاشة | خفّض عدد الوحدات داخل اللعبة |
| 4. الهاتف في وضع توفير الطاقة | إيقاف وضع توفير الطاقة أثناء اللعب |
| 5. حرارة عالية | راجع قسم الحرارة في USER_MANUAL.md |

**التشخيص**:

```bash
adb logcat -s strongholddroid-input:V FpsMonitor:V ThermalManager:V
```

### العَرَض: FPS متذبذب (60 ثم 30 ثم 60)

| السبب | الحل |
|-------|------|
| Dynamic Resolution scaler يتفاعل جدًّا | الإعدادات → الأداء → اضبط "Deadband" إلى 5 FPS |
| Thermal throttle | راجع تبويب الأداء لمستوى الحرارة |
| BG apps تسرق CPU | أغلق التطبيقات الأخرى |

### العَرَض: انخفاض الأداء بعد 30 دقيقة من اللعب

| السبب | الحل |
|-------|------|
| تراكم ذاكرة Wine (memory leak) | أعد تشغيل الجلسة كل ساعة |
| DXVK shader cache تكدّس | امسح `<filesDir>/prefixes/<slug>/drive_c/.../DXVKStateCache/` |
| الحرارة المتراكمة | خفّض تردد CPU (للأجهزة المروتية فقط) |

---

## مشاكل التحكم

### العَرَض: الماوس يرتجف أو يتحرك ببطء

| السبب | الحل |
|-------|------|
| حساسية منخفضة جدًّا | الإعدادات → التحكم → حساسية الماوس → ارفع إلى 1.5 |
| Smoothing مرتفع جدًّا | خفّض "Mouse smoothing" إلى 0.1 |
| ضغط أصابع كثيرة | استخدم إصبعاً واحدة للماوس الأساسي |

### العَرَض: السحب لا يختار الوحدات

| السبب | الحل |
|-------|------|
| قفل السحب معطل | الإعدادات → التحكم → فعّل "قفل السحب" |
| حساسية عالية تتجاوز touchSlop | خفّض الحساسية |
| Multi-touch (إصبعان) ألغى السحب | استخدم إصبعاً واحدة فقط للسحب |

### العَرَض: الزر الأيمن لا يعمل

| السبب | الحل |
|-------|------|
| إيماءة right-click غير معرّفة | الإعدادات → التحكم → "Right click gesture" → اختر إيماءة |
| حساسية الإيماءة عالية | خفّف "TAP_MAX_MS" في GestureHandler |

### العَرَض: تكبير الخريطة (pinch) لا يعمل

| السبب | الحل |
|-------|------|
| إصبعان قريبان جدًّا | ابدأ الإيماءة بإصبعين متباعدين ≥1 cm |
| Pinch معطل في الإعدادات | الإعدادات → التحكم → تحقّق من "Zoom gesture" |
| Touchscreen لا يدعم multi-touch | تحقّق من مواصفات الجهاز |

---

## مشاكل الحفظ

### العَرَض: "Save state failed" في الـ UI

| السبب المحتمل | الحل |
|---------------|------|
| مساحة فارغة غير كافية | حرّر 100 MB+ على الأقل |
| أذونات FileProvider | تحقّق من `file_provider_paths.xml` |
| عملية Wine معلّقة | أعد تشغيل الجلسة قبل الحفظ |

### العَرَض: الاستعادة لا تعمل (تظهر شاشة menu بدل الحالة)

| السبب | الحل |
|-------|------|
| إصدار Wine مختلف عن وقت الحفظ | استخدم نفس إصدار Wine لحفظ واستعادة |
| ملف الـ save تالف | جرّب slot آخر |
| Game version mismatch | تحقّق من أن نفس slug لكل من الحفظ والاستعادة |

### العَرَض: شاشة سوداء بعد الاستعادة

| السبب المحتمل | الحل |
|---------------|------|
| Surface buffer لم يُعاد إنشاؤه | اضغط على الشاشة (لإجبار WM_PAINT) |
| Vulkan swapchain لم يُعاد إنشاؤه | أعد تشغيل GameManagerActivity (Back مرتين) |

---

## مشاكل الـ Gamepad

### العَرَض: Gamepad لا يُكتشف

| السبب المحتمل | الحل |
|---------------|------|
| إصدار أندرويد قديم | الإعدادات → الأجهزة المتصلة → تحقّق من ظهور Gamepad |
| Bluetooth معطل | فعّل Bluetooth، اربط Gamepad من إعدادات النظام |
| Layout معطل | الإعدادات → التحكم → "Keyboard layout" → اختر QWERTY |

### العَرَض: الـ sticks ترتجف

| السبب | الحل |
|-------|------|
| Dead zone صغيرة جدًّا | الإعدادات → التحكم → ارفع "Stick dead zone" إلى 0.15 |
| Gamepad تالف | جرّب gamepad آخر |
| Bluetooth interference | قرّب Gamepad من الهاتف |

### العَرَض: أزرار معكوسة (A/B مبدّلة)

| السبب | الحل |
|-------|------|
| Layout Nintendo-style (Switch) بدل Xbox-style | الإعدادات → التحكم → بدّل "Layout" إلى "Nintendo" |

---

## مشاكل الاستقرار

### العَرَض: Crash عشوائي بعد 30+ دقيقة

| السبب المحتمل | الحل |
|---------------|------|
| 1. تراكم ذاكرة Wine | حفظ الحالة، إعادة التشغيل، استعادة |
| 2. تسرّب ذاكرة DXVK shader cache | امسح cache يدوياً |
| 3. تحمّل حراري | راجع تبويب الأداء |
| 4. لا fsync (معطل) | تحقّق من `WINEFSYNC=1` في env list |
| 5. نسخة box64 قديمة | حدّث box64 إلى آخر إصدار |

### العَرَض: Crash فوري عند بدء معركة

| السبب | الحل |
|-------|------|
| Wine dsound voices limit (SC Extreme) | الإعدادات → الصوت → فعّل "Aggressive voice pooling" |
| VRAM overflow (SC Extreme) | الإعدادات → الأداء → اضبط minScale إلى 0.6 |
| DirectX 9 shader compilation error | سجل السطر في logcat، أبلغ عنه |

---

## تجميع الـ crash dumps

للتشخيص المتقدم، اجمع:

1. **Logcat**: `adb logcat -d > /tmp/logcat.txt`
2. **حالة Wine process**: `adb shell run-as com.strongholddroid.emulator.debug ls filesDir/crashes/`
3. **حرارة الجهاز**: من تبويب الأداء، صور الشاشة وقت الـ crash
4. **إصدار النظام**: `adb shell getprop ro.build.fingerprint`

ثم أبلغ عن المشكلة في [GitHub Issues](https://github.com/your-org/StrongholdDroid/issues)
مع جميع المعلومات المذكورة.

### نصائح لجمع crash dump نظيف

- لا تُعِد تشغيل الجهاز قبل جمع الـ logs (تمسح الـ buffer)
- استخدم `adb logcat -c` قبل بدء الجلسة لإفراغ الـ buffer القديم
- استخدم `-b crash` للحصول على native crash stacktrace:

```bash
adb logcat -b crash -d > /tmp/crash.txt
```

---

## الأسئلة المتكررة

### كيف أعرف إن كانت ملفات اللعبة مثبتة بشكل صحيح؟

الإعدادات → المكتبة → اختر اللعبة → "تحقق من التثبيت". يطبع التطبيق
المسار الصحيح للـ `wine_prefix/drive_c` ويقارنه مع الحجم المتوقع.

### كيف أنقل save-states إلى جهاز آخر؟

```bash
adb shell run-as com.strongholddroid.emulator.debug \
    cp -r files/saves/stronghold_crusader_hd/ /sdcard/saves-sc-hd/

# ثم على الجهاز الجديد
adb push /sdcard/saves-sc-hd/ /sdcard/saves-sc-hd/
adb shell run-as com.strongholddroid.emulator.debug \
    cp -r /sdcard/saves-sc-hd/ files/saves/stronghold_crusader_hd/
```

### هل أحتاج إعادة تشغيل الهاتف بعد تحديث التطبيق؟

نعم موصى به — يضمن عدم وجود عمليات Wine قديمة في الخلفية.

---

## الخطوة التالية

إذا لم تجد حل مشكلتك هنا، اقرأ:

- [ARCHITECTURE.md](ARCHITECTURE.md) — لفهم أعمق للبنية
- [PERFORMANCE.md](PERFORMANCE.md) — لتحسين الأداء
