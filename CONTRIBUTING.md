# Contributing

Run `git config core.hooksPath .githooks` once per clone. The hooks check every commit and every pushed tag; `./lint` runs the same code checks on the whole tree or on given paths (`./lint app`, `./lint worker/src`), and `./lint --fix` formats first.

## Code

- Go is gofmt'd and passes `go vet`.
- The worker is oxfmt'd and passes oxlint and `tsc`.
- Kotlin passes ktlint with [compose-rules](https://mrmans0n.github.io/compose-rules/).
- No code file is over 500 lines; split it instead.
- App text follows [docs/ui-text.md](docs/ui-text.md): no stand-in text, and only the listed placeholders.
- `app/core` holds all client logic: signing in, the server and Cloudflare APIs, deploying, transfers. A Kotlin CLI will be built on it, so it has no UI or Android code, and it returns whole results instead of holding UI state. `./lint` and the hook reject androidx, android, and Compose there. New client features go in core. The UI in `app/shared` only adds state and screens on top.
- The Go CLI in `cli/` is in maintenance: keep its commands working, but don't add features to it.

## Commit messages

- One line, starting with a capital letter. No body, no `type:` prefix.
- No agent attribution: no `Co-Authored-By` for an AI, no "Generated with" lines.
- Say what changed for someone using realm, in plain words, not how it was done: "Log in with Cloudflare from the app", not "Add CloudflareSession bearer to Api".

The hook enforces the first two. The third is a review step: read the message as a user would before committing.

## Rules

Each rule, and what makes it hold. "Review" means nothing fails when it's skipped: read for it before committing.

| Rule | Enforced by |
| --- | --- |
| Go is gofmt'd and passes `go vet`. | `./lint`, pre-commit hook |
| The worker is oxfmt'd and passes oxlint and `tsc`. | `./lint`, pre-commit hook |
| Kotlin passes ktlint with [compose-rules](https://mrmans0n.github.io/compose-rules/). | `./lint`, pre-commit hook |
| No code file is over 500 lines; split it instead. | `./lint`, pre-commit hook |
| App text has no stand-in text, and only the placeholders [docs/ui-text.md](docs/ui-text.md) lists. | `.githooks/placeholders`, from `./lint` and the pre-commit hook |
| An empty or loading area stays blank or fades in. | `Loading` for fetched data (below); otherwise review |
| `app/core` has no androidx, android, or Compose code. | `.githooks/core-free`, from `./lint` and the pre-commit hook |
| New client features go in `app/core`; `app/shared` only adds state and screens. | Review |
| Fetched data is held in one `Fetched<T>` (`app/shared/.../Loaded.kt`), which tells never known from known, keeps the last value when asking fails, and keeps it between runs with core's `Remembered<T>`. Don't hand-roll prefs JSON or a nullable "not loaded yet" field. | Types: `Loaded<T>` and `Fetched<T>`; tests: `FetchedTest`, `RememberedTest` |
| Fetched data shows with `Loading(…)` from `ui/Loading.kt`: a skeleton holds its room until it's known, then fades through to it in place. Data arriving never grows the layout: no `expandVertically`, `expandIn`, `expandHorizontally`, or `animateContentSize` in `app/shared`. | `.githooks/grows`, from `./lint` and the pre-commit hook; the `settings-cold` and `settings-warm` checks in `:desktopApp:preview`, which fail if the settings sheet changes height |
| Data a screen shows is fetched before the screen opens (as the app starts, on signing in, as a realm opens), not when it opens. | Review |
| A text button in the flow of the text is `InlineButton` from `ui/Buttons.kt`, never a button with `contentPadding = PaddingValues(0…)`. | `.githooks/grows` |
| The Go CLI in `cli/` is in maintenance: keep its commands working, add no features. | Review |
| A commit message is one line, starting with a capital letter, with no `type:` prefix. | `.githooks/commit-msg` |
| A commit message has no agent attribution. | `.githooks/commit-msg` |
| A commit message says what changed for someone using realm, in plain words. | Review |
| A pushed tag is a release, `vMAJOR.MINOR.PATCH`, or a release candidate, `vMAJOR.MINOR.PATCH-rc.N`. | `.githooks/pre-push`; the release workflow ignores other tags |

An exception to a lint goes on the offending line, with a reason, a human's approval, and the date it lapses: `// grows: <reason> (approved by <human>, until <YYYY-MM-DD>)`. An agent writes `approved by PENDING`, which lasts 14 days at most, and asks.

## Releases

A release is a tag `vMAJOR.MINOR.PATCH`, like `v1.2.0`. To try one out first, tag a release candidate, `v1.2.0-rc.1`: it becomes a GitHub pre-release, which the app never deploys. No other names, like `-test`. Pushing a tag builds the worker and the apps and attaches them to the release: `worker.js` and `worker.json`, `realm-<tag>.apk` signed with the release key, `realm-<tag>-x86_64.AppImage`, and `realm-<tag>-linux-x64.jar`, which runs on Java 17 or later. The apps are versioned from the tag; `app/androidApp/build.gradle.kts` explains the Android versionCode. The pre-push hook refuses any other tag, and the release workflow ignores them.

## Agent skills

Skills live in `.agents/skills/<name>/SKILL.md`, the cross-tool [Agent Skills](https://agentskills.io) location. `.claude/skills` is a symlink to it, so Claude Code loads the same files. Edit them under `.agents/`.
