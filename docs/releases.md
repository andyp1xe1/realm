# Releases

A release is a tag `vMAJOR.MINOR.PATCH`, like `v1.2.0`. A release candidate is `vMAJOR.MINOR.PATCH-rc.N`, like `v1.2.0-rc.1`. It becomes a GitHub pre-release, which the app never deploys. The pre-push hook refuses other tag names, and the release workflow ignores them.

Pushing a tag runs `.github/workflows/release.yml`. It attaches these files to the release:

| File | What it is |
| --- | --- |
| `worker.js`, `worker.json` | The worker bundle and its manifest, which the app deploys |
| `realm-<tag>.apk` | The Android app, signed with the release key |
| `realm-<tag>-x86_64.AppImage` | The Linux desktop app with its own Java runtime |
| `realm-<tag>-linux-x64.jar` | The desktop app as one jar, for Java 17 or later |

The apps take their version from the tag. `app/androidApp/build.gradle.kts` shows how the tag maps to the Android `versionCode`. A release candidate always gets a lower code than its release.

The workflow signs the APK with the key in the repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Phones refuse an update signed with another key, so the key never changes.
