# realm

A personal dump: type, paste, drop, record, or snap something and it lands in one place. Search it when you want to.
Hand an agent or a friend a link and they can read from it or drop into it. No login, no bot limits.

```
worker/   Cloudflare Worker: the API. R2 holds files, D1 (SQLite + FTS5) holds items and search
cli/      Go CLI: one static binary for laptops, servers, Termux
app/      Compose Multiplatform: Android + desktop (Linux/macOS/Windows)
```

## 0. Toolchain

On NixOS (or anywhere with Nix), run `nix-shell` in the repo root. It provides JDK 21 (with `JAVA_HOME` set), Go and Node.
Elsewhere: any JDK 17+ with `JAVA_HOME` set, Go 1.24+, Node 22+.

## 1. Deploy the worker

```sh
cd worker
npm install
npx wrangler login
npm run deploy                          # first deploy creates the R2 bucket + D1 database → https://realm.<you>.workers.dev
npm run db:init                         # creates tables + full-text index
openssl rand -base64 32 | tee /dev/stderr | npx wrangler secret put REALM_TOKEN   # note the token it prints
```

A daily cron (in `wrangler.toml`) aborts multipart uploads left unfinished for a day and deletes expired or used-up links.

Local hacking without Cloudflare: `npm run serve` (in-memory D1/R2, token `dev`), `npm test`.

## 2. CLI

```sh
cd cli && make                      # or: make dist  → linux/mac/windows/android binaries
realm login https://realm.you.workers.dev <token>
realm login https://realm.you.workers.dev/s/<link>   # or log in with just a share link

realm add buy oat milk              # a note
echo "$(date) deployed" | realm add # stdin
realm add ~/Downloads/*.pdf -t "tax stuff"
realm ls                            # newest at the bottom
realm ls invoice -k file            # search + filter
realm get last                      # download into cwd
realm cat 0mvva9o3h6kyp | jq .
realm link 0mvva9o3h6kyp --ttl 1d --uses 3     # one-item download link
realm link --drop --label bob --ttl 7d --max-size 2GB   # a link someone can dump into
realm link --read                               # give an agent read access
realm links / realm revoke <url>
```

`REALM_URL` / `REALM_TOKEN` env vars override the saved config (`~/.config/realm/config.json`).

## 3. Apps

```sh
cd app
./gradlew :desktopApp:run                   # desktop
./gradlew :desktopApp:packageDeb            # or packageDmg / packageMsi / packageAppImage
./gradlew :androidApp:installDebug          # phone over adb
./gradlew :androidApp:assembleRelease       # sideloadable APK (signed with the debug key)
```

First launch asks for the server URL + token (or a share link).

- **Composer always ready**: Enter sends on desktop (Shift+Enter for a newline). Long text (4+ lines) or the
  ⛶ button opens a full-height editor over the feed, where Enter is a newline and Ctrl+Enter sends.
- **Attachments wait for you**: 📎 picker, 📷 camera (Android), Ctrl+V with files/images, drag & drop onto the
  desktop window, and *Share → realm* on Android all land in a tray in the composer. Type a caption, remove
  what you don't want, then send.
- 🎙 voice notes with a live waveform (m4a on Android, wav on desktop); these send when you stop.
- Tap or long-press (or right-click) an item: open, save, copy, edit, share link with expiry, delete.
- 🔍 search + filter chips (notes, links, images, audio/video, files). Ctrl+F on desktop.
- 🔗 share-link manager: create drop/read/full links with expiry, see uses, revoke.
- Other devices' dumps show up within ~8s.

## Links, agents, friends

There are four kinds of link, all at `https://…/s/<token>`. Each has an optional expiry and use limit, and
links that can upload can also have a byte budget (`max_bytes` / `--max-size`):

| scope  | can                                                     | typical use                          |
|--------|---------------------------------------------------------|--------------------------------------|
| `item` | download one item; the URL *is* the file                | send a friend a 2 GB video           |
| `drop` | add notes/files, nothing else                           | "upload your stuff here", agent output |
| `read` | list, search, download                                  | "take X from my inbox"               |
| `full` | read + add + edit + delete (not manage links)           | an agent that tidies the dump        |

Paste a link to an agent and say "curl this". Without a browser, the link answers with a markdown
cheat-sheet for exactly what that link can do (search, download, upload, multipart for big files).
In a browser, a drop link is a small drag-and-drop upload page and a read link is a searchable list.

The owner API is the same surface at `/api/...` with `Authorization: Bearer $REALM_TOKEN`.
`curl -H "Authorization: Bearer $REALM_TOKEN" https://…/api/` prints the full cheat-sheet.

## Limits worth knowing

- One request to a Worker can carry ~100 MB (free/pro plan), so bigger files go up as R2 multipart
  uploads (CLI: 32 MiB parts, app: 16 MiB). Downloads stream with no size limit and support Range/resume.
- On use-limited links every download is a whole download (Range is ignored), so it costs a use;
  starting an upload costs one use too.
- Files that could run script (html, svg, xml, …) are always served as downloads, with `nosniff` and a sandbox CSP.
- Search is SQLite FTS5 prefix matching over note text, captions, and file names (not file contents).
- `wrangler d1 export` skips FTS virtual tables; after restoring a backup, re-run
  `INSERT INTO items_fts(items_fts) VALUES('rebuild')`.
