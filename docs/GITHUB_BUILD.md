# GitHub Build

The Android workflows use Java 17 with Gradle 8.9, which is aligned with Android Gradle Plugin 8.7.3 in this repository.

Because the packaging environment could not fetch binary dependencies, the workflow first downloads the official Gradle 8.9 Wrapper JAR from Gradle's GitHub repository and verifies its published SHA-256 checksum. `gradle/actions/setup-gradle@v6` then performs wrapper validation and caching before the repository's `./gradlew` is used.

## Debug APK

`.github/workflows/android-build.yml` performs:

1. Checkout.
2. Java 17 setup.
3. Official Gradle 8.9 wrapper JAR checksum verification.
4. Gradle wrapper validation/cache setup.
5. Unit tests.
6. Android lint.
7. `:android:app:assembleDebug`.
8. Upload of `app-debug.apk` as the `Hammam-AttendAI-debug-apk` artifact.

The Debug build requires no WhatsApp, Cloud AI, Email, cloud-sync, signing, or backend secrets.

## Release APK

Release signing is optional and must use GitHub Secrets. Suggested secret names:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Never commit `.jks` or `.keystore` files.
