# Contributing

Run `git config core.hooksPath .githooks` once per clone. The hooks check every commit and every pushed tag; `./lint` runs the same code checks on the whole tree or on given paths (`./lint app`, `./lint worker/src`), and `./lint --fix` formats first.

## Code

- Go is gofmt'd and passes `go vet`.
- The worker is oxfmt'd and passes oxlint and `tsc`.
- Kotlin passes ktlint with [compose-rules](https://mrmans0n.github.io/compose-rules/).
- No code file is over 500 lines; split it instead.
- App text follows [docs/ui-text.md](docs/ui-text.md): no stand-in text, and only the listed placeholders.

## Commit messages

- One line, starting with a capital letter. No body, no `type:` prefix.
- No agent attribution: no `Co-Authored-By` for an AI, no "Generated with" lines.
- Say what changed for someone using realm, in plain words, not how it was done: "Log in with Cloudflare from the app", not "Add CloudflareSession bearer to Api".

The hook enforces the first two. The third is a review step: read the message as a user would before committing.

## Releases

A release is a tag `vMAJOR.MINOR.PATCH`, like `v1.2.0`. To try one out first, tag a release candidate, `v1.2.0-rc.1`: it becomes a GitHub pre-release, which the app never deploys. No other names, like `-test`. Pushing a tag builds the worker and attaches it to the release. The pre-push hook refuses any other tag, and the release workflow ignores them.

## Agent skills

Skills live in `.agents/skills/<name>/SKILL.md`, the cross-tool [Agent Skills](https://agentskills.io) location. `.claude/skills` is a symlink to it, so Claude Code loads the same files. Edit them under `.agents/`.
