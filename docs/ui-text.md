# UI text

No stand-in text where content goes: no "Loading…", "Empty…", or captions on empty tiles. An empty or loading area stays blank, or fades its content in when it arrives. Text that says something real is fine, like "Nothing matches." for a search.

`./lint` and the pre-commit hook reject app strings starting with "Loading" or "Empty", and any string with "…" or "..." that isn't listed below. Adding one to the list is the review: ask first. Whether an area stays blank or fades in is checked by eye.

## Allowed

- `Dump something…`
- `Write something…`
- `Add a caption…`
- `Connecting…`
- `Signing in…`
- `Logging in…`
- `Creating…`
- `sending…`
- `uploading…`
- `Share link…`
- `realm.someone.dev/s/…`
