# Contributing

Run `git config core.hooksPath .githooks` and `git config core.fileMode false` once per clone. The hooks check commits and pushed tags. With `fileMode` off, set a script's executable bit with `git update-index --chmod=+x`.

`./lint [--fix] [path...]` runs the hooks' code checks on the tree. Each error says what to change. To accept an intended exception, give the reason on the line: `// grows: <reason>`.

Agent skills live in `.agents/skills/`. `.claude/skills` links there.

## Follow the rules no check catches

- Put client logic in `app/core`: signing in, the server and Cloudflare APIs, deploying, and transfers. `app/shared` adds only state and screens.
- Fetch data before the screen that shows it opens. Show what the last run kept at once, and fade in only what a refresh changes. With nothing kept, hold the room the data will take, and show a spinner or skeleton only after `INDICATOR_DELAY_MILLIS`.
- Write comments only as short docs on public API and as one description at the top of each module.
- Write commit messages that say what changed for someone using realm: "Log in with Cloudflare from the app", not "Add CloudflareSession bearer to Api".

## Release

Push a tag `vX.Y.Z`, or `vX.Y.Z-rc.N` for a release candidate. A release candidate becomes a GitHub pre-release, which the app never deploys. `.github/workflows/release.yml` builds the worker bundle, the APK, a Linux AppImage, and a desktop jar, each versioned from the tag.

The workflow signs the APK with the key in the secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Phones refuse an update signed with another key, so never change the key.
