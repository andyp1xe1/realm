# Contributing

Run `git config core.hooksPath .githooks` and `git config core.fileMode false` once per clone. The hooks check every commit and every pushed tag. With `fileMode` off, git keeps a script's executable bit from the index, so set it with `git update-index --chmod=+x`.

`./lint` runs the code checks on the whole tree, or on the paths you give it, like `./lint app`. `./lint --fix` formats first. The code checks cover formatting, file length, app text, what `app/core` may import, layouts that grow as data arrives, and script permissions. The hooks also check commit messages and tag names. Each error says what to change.

## Follow the rules no check catches

- Put client logic in `app/core`: signing in, the server and Cloudflare APIs, deploying, and transfers. A Kotlin CLI will build on it. `app/shared` adds only state and screens.
- Load data the way [docs/loading.md](docs/loading.md) describes. Fetch it before the screen that shows it opens.
- Write comments only as short docs on a module's public API, where the name and signature don't say enough, and as one description at the top of each module. If code needs a comment to explain itself, fix the code.
- Keep the Go CLI in `cli/` working, but add no features to it.
- Write commit messages that say what changed for someone using realm: "Log in with Cloudflare from the app", not "Add CloudflareSession bearer to Api".

To make a lint accept an intended exception, give the reason on the line: `// grows: <reason>`.

## Release

See [docs/releases.md](docs/releases.md).

## Agent skills

Skills live in `.agents/skills/<name>/SKILL.md`, the cross-tool [Agent Skills](https://agentskills.io) location. `.claude/skills` is a symlink to that folder, so Claude Code loads the same files.
