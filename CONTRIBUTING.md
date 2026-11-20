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

The app and the worker have their own versions, and each is released only when it changes:

- Push `app/vX.Y.Z` to release the app. `.github/workflows/release-app.yml` builds the APK, a Linux AppImage, and a desktop jar.
- Push `worker/vX.Y.Z` to release the worker. `.github/workflows/release-worker.yml` builds the worker bundle.

Add `-rc.N` for a release candidate. It becomes a GitHub pre-release, which the app never installs or deploys.

Each stable release is marked latest and carries `releases.json`, every release as GitHub lists them. The app reads it through an ordinary download link, because GitHub's API limits how often one network may ask.

The two talk through a protocol, `VERSION` in `worker/src/common.ts` and `Api.VERSION`. When a worker change needs a new protocol, release an app that speaks it first: the worker workflow refuses a worker no released app speaks the protocol of.

The app workflow signs the APK with the key in the secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Phones refuse an update signed with another key, so never change the key.

It also signs `app.json`, the SHA-256 of each app file and the protocol the app speaks, with the ECDSA P-256 key in the secret `APP_SIGNING_KEY`. The app installs only releases signed with the key whose public half is `Api.APP_KEY`, so release a new `Api.APP_KEY` before you sign with a new key.
