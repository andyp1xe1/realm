# How the app loads data

A screen that fills in row by row, or flashes a skeleton for a tenth of a second, feels slower than one that waits a moment. So the app shows fetched data in this order:

1. **What the last run kept, at once.** Then it refreshes in place. A refresh that finds the same data changes nothing on screen. One that finds a change fades only the row, tile, or text that changed.
2. **Data fetched ahead.** The app fetches as it starts, on signing in, and as a realm opens, so the data is kept before a screen needs it. The overview fetches the first page of each feed whose tile is in view, two at a time, item metadata only.
3. **Nothing kept yet.** The area keeps the room the data will take, and the data fades in when it arrives.
4. **Still loading after 400 ms.** A skeleton or spinner fades in only then, after `INDICATOR_DELAY_MILLIS`.

Data never grows the layout as it arrives. The `.githooks/grows` lint refuses `expandVertically`, `expandIn`, `expandHorizontally`, and `animateContentSize` in `app/shared`, unless the line gives a reason.

## The parts that do it

- `Fetched<T>` in `app/shared/.../Loaded.kt` holds one piece of fetched data. It tells never known from known, keeps the last value when a fetch fails, and publishes only a value that differs.
- `Remembered<T>` in `app/core` keeps a value between runs: over `Platform.prefs` for small values, and over `Platform.cache` for larger ones that can be fetched again.
- `FeedCache` keeps the first page of each feed: item metadata, never file contents.
- `Loading`, `Delayed`, `Spinner`, and `Changing` in `ui/Loading.kt` draw the steps above.

`FetchedTest`, `RememberedTest`, `FeedCacheTest`, `KeptTest`, and `LoadingTest` check this behavior. The `kept-*` and `settings-*` previews in `:desktopApp:preview` count the frames that show a skeleton, and fail if a sheet changes height.
