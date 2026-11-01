# softether (ماژول SoftEther برای بنفش)

کلاینت SoftEther از پروژه‌ی [SoftEther-Android-Module](https://github.com/hoang-rio/SoftEther-Android-Module)
(Apache 2.0، فایل LICENSE همین‌جاست) که به‌عنوان ماژول Gradle به بنفش اضافه شده.

- فقط TCP (بدون RUDP) و فقط دو ABI گوشی: `arm64-v8a` و `armeabi-v7a`
- `src/main/jniLibs/*/libssl.a` و `libcrypto.a`: OpenSSL 3.6.5 که از قبل کامپایل شده (از خود ریپوی ماژول)
- هدرهای OpenSSL توی ریپو نیستن. GitHub Actions خودش با `fetch-openssl-headers.sh` می‌سازدشون.
  برای بیلد روی سیستم خودت یک‌بار این رو اجرا کن:

      bash softether/fetch-openssl-headers.sh

- لازم داری: NDK `27.2.12479018` و CMake `3.22.1` (Android Studio خودش دانلود می‌کنه)
