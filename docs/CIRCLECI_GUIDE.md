# StrongholdDroid على CircleCI — دليل الإعداد العربي

## TL;DR (للمستعجلين)

1. ارفع مجلد `.circleci/` مع `config.yml` إلى المستودع على GitHub
2. ادخل إلى https://app.circleci.com → **Sign in with GitHub**
3. اذهب إلى **Projects** → اختر `ANWAREASDZX/StrongholdDroid` → **Set Up Project**
4. اختر "Existing Config File" → `.circleci/config.yml` من الفرع `main`
5. اضغط **Start Building** — سيبدأ أول pipeline خلال دقيقة

**التكلفة المتوقعة على Free plan (6000 credit/شهر):**
- أول بناء (بارد): ~700 credit (~70 دقيقة × 10 credit/دقيقة على `medium`)
- بناءات لاحقة (مع ccache + Docker layer cache): ~150 credit (~15 دقيقة)
- يمكنك القيام بـ 8 بناءات كاملة و~40 بناء سريع شهرياً — كافية للتطوير النشط

---

## تفصيل الـ pipeline

| المرحلة | المدة المتوقعة | الوصف |
|---------|----------------|-------|
| `native-build` (بارد) | ~60 دقيقة | بناء Wine + Box64 + DXVK + gl4es + PulseAudio |
| `native-build` (دافئ) | ~10-15 دقيقة | مع ccache (يحفظ 80% من وقت إعادة التجميع) |
| `build-apk` | ~5 دقائق | تجميع APK بعد توفر الـ native libs |
| **الإجمالي (بارد)** | **~65-70 دقيقة** | أول بناء بعد كل push لـ main/develop |
| **الإجمالي (دافئ)** | **~15-20 دقيقة** | بناءات متكررة بدون تغيير في native code |

### الكاش متعدد الطبقات

الـ config يستخدم 3 طبقات كاش لتقليل زمن البناء:

1. **Docker layer cache** (via `docker_layer_caching: true`)
   - يحمّي طبقات apt + SDK + NDK (تأخذ 10 دقائق بارد)
   - تُستعيد آلياً في `setup_remote_docker`
2. **ccache** (via `save_cache` / `restore_cache`)
   - يحمّي نتائج تجميع C/C++ (Wine, Box64, DXVK)
   - يخزَّن في `build/ccache/` ويُنقل عبر `persist_to_workspace`
3. **Workspace** (`persist_to_workspace` / `attach_workspace`)
   - ينقل الـ prebuilt libs من `native-build` إلى `build-apk`

---

## كيف تضيف أسرار التوقيع (release variant فقط)

لا تضع الأسرار في `config.yml` — استخدم CircleCI Project Settings:

1. ادخل: https://app.circleci.com/settings/project/github/ANWAREASDZX/StrongholdDroid/environment-variables
2. أضف المتغيرات الأربعة:

| الاسم | القيمة |
|------|-------|
| `STRONGHOLDDROID_KEYSTORE` | base64 من ملف `.jks` (لـ keystore) |
| `STRONGHOLDDROID_STORE_PASSWORD` | كلمة مرور الـ keystore |
| `STRONGHOLDDROID_KEY_ALIAS` | اسم الـ key alias |
| `STRONGHOLDDROID_KEY_PASSWORD` | كلمة مرور الـ key |

لتحويل الـ keystore إلى base64 على Linux/macOS:
```bash
base64 -w0 release.keystore > keystore.b64
# ثم انسخ المحتوى إلى متغير STRONGHOLDDROID_KEYSTORE
```

هذه المتغيرات تُستهلك فقط في بناء `release` (الذي يُشغَّل عند رفع tag مثل `v0.1.0`). أما بناءات `debug` و`ci` فلا تحتاجها.

---

## تنبيه أمني مهم

**لا تضع أي توكن أو كلمة مرور مباشرة في `config.yml` أو أي ملف في المستودع.** كل الـ CircleCI configs مرئية لكل من يصل المستودع. استخدم دائماً Environment Variables من Project Settings.

---

## كيف تشغّل البناء محلياً بنفس الـ config (بدلاً من CI)

```bash
cd /path/to/StrongholdDroid

# نفس ما يفعله native-build:
docker build -t strongholddroid-builder -f scripts/docker/Dockerfile.build scripts/docker
docker run --rm -v "$PWD:/work" -w /work strongholddroid-builder \
    bash -lc './scripts/build_all.sh'

# ثم نفس ما يفعله build-apk:
docker run --rm -v "$PWD:/work" -w /work strongholddroid-builder \
    bash -lc './scripts/build_apk.sh debug'
```

هذا مفيد لاختبار تعديلاتك على الـ Dockerfile أو سكربتات البناء قبل رفعها إلى GitHub.

---

## كم مرة يمكنني البناء شهرياً على Free plan؟

| نوع البناء | Credit لكل بناء | عدد البناءات الشهرية المتاحة |
|------------|----------------|------------------------------|
| بارد كامل (no cache) | ~700 | 8 |
| دافئ (ccache + Docker layers) | ~150 | 40 |
| تحديثات صغيرة فقط للكود | ~50 (debug APK فقط) | 120 |

**نصيحة**: استخدم `resource_class: medium` بدلاً من `large` إذا كنت تستهلك الـ credits بسرعة. وقت البناء سيزداد من 70 → 100 دقيقة لكنك ستحصل على ضعف عدد البناءات.

---

## ماذا عن الـ GitHub Actions الموجودة؟

المستودع يحتوي على `.github/workflows/build.yml` مسبقاً. يمكنك:
- **الإبقاء على كلاهما** — CircleCI يعمل كـ primary، GitHub Actions كـ backup
- **تعطيل GitHub Actions** — Settings → Actions → General → Disable Actions
- **حذف ملف workflow** — `git rm .github/workflows/build.yml`

الأنسب لمشروعك: **حذف GitHub Actions** لأن الـ Actions المجاني (2000 دقيقة/شهر) يستهلك 70 دقيقة × ~30 بناء = يفنى سريعاً، بينما CircleCI Free plan أكرم للمشاريع كثيفة البناء.
