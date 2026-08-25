# تقرير الأداء ومنهجية القياس لـ StrongholdDroid

> هذا المستند يوضح منهجية قياس الأداء وأهداف FPS لكل فئة أجهزة، مع
> تقديرات مرجعية مستندة إلى بيانات Winlator وBox64 المنشورة علناً.
> **تنبيه**: الأرقام التقديرية بحاجة إلى تحقق عملي على الأجهزة المذكورة.

---

## جدول المحتويات

1. [الأهداف الكمية](#الأهداف-الكمية)
2. [منهجية القياس](#منهجية-القياس)
3. [سيناريوهات الاختبار](#سيناريوهات-الاختبار)
4. [أجهزة الاختبار المرجعية](#أجهزة-الاختبار-المرجعية)
5. [تقديرات مرجعية](#تقديرات-مرجعية)
6. [تحليل النتائج](#تحليل-النتائج)
7. [تحسينات مستقبلية](#تحسينات-مستقبلية)

---

## الأهداف الكمية

| الفئة | مثال المعالج | هدف FPS | الدقة المستهدفة | زمن الاستجابة الصوتي |
|-------|--------------|---------|-----------------|---------------------|
| **Low-end** | Snapdragon 665, Mali-G52 | 30 | 960×540 | <80 ms |
| **Mid-range** | Snapdragon 730G, Mali-G76 | 45 | 1280×720 | <70 ms |
| **High-end** | Snapdragon 855+, Adreno 650 | 60 | 1920×1080 | <60 ms |

### أهداف الفرعية

- **Load time (للأول مرة)**: <15 ثانية على كل الفئات
- **Save state save**: <3 ثانية
- **Save state restore**: <5 ثانية
- **Crash rate**: <1% من الجلسات (1% = "غير مستقر")
- **Steady-state CPU usage**: <70% من core 0 على الأجهزة المنخفضة
- **Memory peak**: <600 MB للجلسة كاملة

---

## منهجية القياس

### الأدوات المستخدمة

| الأداة | الغرض | المصدر |
|--------|------|--------|
| `adb logcat -s FpsMonitor:V` | قراءة FPS | مدمج في التطبيق |
| `adb shell dumpsys gfxinfo` | إحصائيات GPU | Android Studio |
| `simpleperf stat -e cycles,instructions` | CPU profiling | NDK |
| `cat /sys/class/thermal/thermal_zone*/temp` | حرارة | Linux kernel |
| `cat /proc/<pid>/status` | VmRSS (memory) | /proc |
| Android Studio CPU Profiler | method tracing | IDE |

### إعداد القياس

```bash
# 1. ابدأ التطبيق
adb shell am start -n com.strongholddroid.emulator.debug/com.strongholddroid.emulator.ui.MainActivity

# 2. ابدأ اللعبة
adb shell am start -n com.strongholddroid.emulator.debug/com.strongholddroid.emulator.ui.GameManagerActivity \
    --es profile_slug stronghold_crusader_hd

# 3. سجّل FPS والحرارة لمدة 30 دقيقة
adb logcat -s strongholddroid-wine:V FpsMonitor:V ThermalManager:V | \
    tee /tmp/strongholddroid-perf-$(date +%s).log

# 4. قياس ذاكرة Wine
adb shell run-as com.strongholddroid.emulator.debug \
    cat /proc/$(pidof wine64)/status | grep -E 'VmRSS|VmPeak'
```

### ساعات التشغيل

كل قياس يجب أن يستمر على الأقل:

- **مثال بسيط**: 5 دقائق (للتحقق من الـ cold start)
- **معركة صغيرة** (50 وحدة): 10 دقائق
- **معركة كبيرة** (500 وحدة): 20 دقيقة
- **معركة ضخمة** (Extreme, >2000 وحدة): 30 دقيقة
- **جلسة طويلة** (مرجع): 2 ساعة

---

## سيناريوهات الاختبار

### السيناريو 1: Cold Start

- **الحالة**: أول تشغيل للعبة (Wine prefix fresh)
- **المقياس**: الزمن حتى ظهور الـ main menu
- **الهدف**: <15 ثانية (low-end), <8 ثانية (high-end)

### السيناريو 2: حملة ساندي (Skirmish)

- **الحالة**: خريطة `siege_of_acre`، 50 وحدة على كل جبهة
- **المدة**: 10 دقائق لعب نشط
- **المقياس**: متوسط FPS، 1% low FPS، حرارة مستقرة

### السيناريو 3: معركة Extreme

- **الحالة**: خريطة `extreme_crusader_path`، 2000+ وحدة
- **المدة**: 20 دقيقة
- **المقياس**: متوسط FPS، 1% low FPS، VmRSS trend

### السيناريو 4: جلسة طويلة

- **الحالة**: حملة كاملة لمدة ساعتين
- **المدة**: 120 دقيقة
- **المقياس**: thermal drift، VmRSS drift، save-state time

---

## أجهزة الاختبار المرجعية

### Low-end

| الجهاز | المعالج | GPU | RAM |
|--------|---------|-----|-----|
| Xiaomi Redmi Note 8 | SD 665 | Adreno 610 | 4 GB |
| Samsung Galaxy A50 | Exynos 9611 | Mali-G72 | 4 GB |
| Realme C3 | Helio G70 | Mali-G52 | 3 GB |

### Mid-range

| الجهاز | المعالج | GPU | RAM |
|--------|---------|-----|-----|
| Poco X3 | SD 730G | Adreno 618 | 6 GB |
| Samsung Galaxy A52 | SD 720G | Adreno 618 | 6 GB |
| OnePlus Nord | SD 765G | Adreno 620 | 8 GB |

### High-end

| الجهاز | المعالج | GPU | RAM |
|--------|---------|-----|-----|
| Samsung Galaxy S20 | SD 865 | Adreno 650 | 12 GB |
| OnePlus 8 Pro | SD 865 | Adreno 650 | 8 GB |
| Xiaomi Mi 11 | SD 888 | Adreno 660 | 8 GB |

---

## تقديرات مرجعية

> **تنبيه**: هذه الأرقام تقديرات مستندة إلى:
> - بيانات أداء Winlator المنشورة في GitHub Issues
> - مقاييس أداء Box64 المنشورة في README.md
> - عتبات أداء DXVK على Adreno/Mali (من مستودع DXVK)
>
> **يجب التحقق منها ميدانياً قبل الإفراج عن نسخة 1.0**.

### Low-end (Snapdragon 665 + Adreno 610)

| السيناريو | FPS متوسط | 1% low | حرارة مستقرة | تقييم |
|-----------|---------|--------|--------------|-------|
| Cold start | — | — | — | 12-15 ثانية |
| Skirmish (50) | 30-35 | 22-26 | 70°C | ✅ playable |
| معركة كبيرة (500) | 22-28 | 16-20 | 78°C | ⚠️ تخفيض دقة مطلوب |
| Extreme (2000) | 12-18 | 8-12 | 85°C | ❌ غير قابل للعب |

### Mid-range (Snapdragon 730G + Adreno 618)

| السيناريو | FPS متوسط | 1% low | حرارة مستقرة | تقييم |
|-----------|---------|--------|--------------|-------|
| Cold start | — | — | — | 8-10 ثانية |
| Skirmish (50) | 45-55 | 35-40 | 65°C | ✅ playable |
| معركة كبيرة (500) | 38-45 | 28-35 | 72°C | ✅ playable |
| Extreme (2000) | 22-30 | 18-22 | 80°C | ⚠️ خفض الدقة |

### High-end (Snapdragon 855+ + Adreno 650)

| السيناريو | FPS متوسط | 1% low | حرارة مستقرة | تقييم |
|-----------|---------|--------|--------------|-------|
| Cold start | — | — | — | 6-8 ثانية |
| Skirmish (50) | 60 (locked) | 58 | 60°C | ✅ ممتاز |
| معركة كبيرة (500) | 55-60 | 45-50 | 68°C | ✅ playable |
| Extreme (2000) | 35-45 | 28-35 | 75°C | ✅ playable |

### SC 1.1 مقابل SC HD

| الإصدار | المسار الرسومي | FPS نسبي | السبب |
|---------|-----------------|---------|-------|
| SC 1.1 | wined3d + Zink | 0.7× من SC HD | DirectDraw إضافي translation |
| SC HD | DXVK | 1.0× | الأمثل |
| SC Extreme | DXVK | 0.85× من SC HD | عدد الوحدات |

---

## تحليل النتائج

### النقاط الساخنة (Hotspots)

بناءً على التحليل الابتدائي، نتوقع أن تكون النقاط الساخنة:

1. **Box64 dynarec** (~30% من CPU): تعليمات SSE2 في SC
2. **DXVK shader JIT** (~15% من CPU during shader compile)
3. **DXVK present queue** (~10% من CPU when synchronous present)
4. **Wine ddraw lock** (~5% during menu transitions in SC 1.1)
5. **PulseAudio stub** (~3% — FIFO copy overhead)

### ضبط المعايير

عند تحديد الـ bottleneck:

```bash
# Capture simpleperf لمدة 30 ثانية
adb shell run-as com.strongholddroid.emulator.debug \
    simpleperf record -p $(pidof wine64) -o /sdcard/perf.data --duration 30
adb pull /sdcard/perf.data .

# تحليل
adb shell run-as com.strongholddroid.emulator.debug \
    simpleperf report -i /sdcard/perf.data | head -50
```

---

## تحسينات مستقبلية

### ممرات التحسين المخطط لها (بعد v0.1.0)

| التحسين | الأثر المتوقع | الجهد |
|---------|--------------|------|
| Cache DXVK shaders مع التطبيق | تقليل startup time بـ 5-10 ثوان | منخفض |
| تبديل إلى FEX-Emu بدل Box64 | رفع 10-15% FPS على Adreno 650+ | عالٍ (Porting) |
| Cache رئيسي للـ box64 | رفع 5% FPS بعد ثاني تشغيل | متوسط |
| Vulkan async pipeline compilation | تقليل stutter بنسبة 70% | متوسط |
| CRIU full checkpoint | تقليل save time من 3s إلى 0.5s | عالٍ |
| Multi-thread PulseAudio stub | تقليل CPU audio overhead 50% | متوسط |

### تجارب معطّلة (لم تثبت)

- **Vulkan memory allocator**: لا تحسن الأداء على Adreno (يستخدم VMA داخلياً)
- **Async queue in DXVK**: يسبب visual artifacts في SC (تمر بـ DX9 era engine)
- **Big cores pinning**: يعطي 2-3 FPS إضافية لكن يكسر thermal budget

---

## كيفية المساهمة في تحسين الأداء

إذا كان لديك جهاز غير المذكور أعلاه، الرجاء:

1. جرّب السيناريوهات المذكورة
2. اجمع الـ logs كما في [منهجية القياس](#منهجية-القياس)
3. أبلغ عن النتائج في [GitHub Discussions](https://github.com/your-org/StrongholdDroid/discussions)
   مع:
   - نوع الجهاز والمعالج
   - FPS متوسط و1% low
   - الحرارة المستقرة
   - أي crash dump

### قالب البلاغ

```markdown
## Performance Report: <device>

**Device**: Samsung Galaxy A52 (SD 720G, Adreno 618, 6 GB RAM)
**Profile**: stronghold_crusader_hd
**Backend**: DXVK_VULKAN
**Scenario**: Skirmish (50 units)

**Results**:
- Avg FPS: 42
- 1% low: 32
- Stable temp: 68°C
- VmRSS after 10 min: 280 MB

**Notes**:
- Stutter during first 30s (shader compile)
- After Dynamic Resolution kicked in (1.0 → 0.9), FPS improved to 48
```

---

## خلاصة

- الأهداف المنصوبة (30/45/60 FPS عبر فئات الأجهزة) قابلة للتحقيق
- الأرقام التقديرية مستندة إلى بيانات مشابهة لكنها بحاجة للتحقق الميداني
- أهم التحسينات المستقبلية: DXVK shader pre-cache + box64rc persistence
- المساهمات بتقارير الأداء مرحب بها لتحسين الـ reference data
