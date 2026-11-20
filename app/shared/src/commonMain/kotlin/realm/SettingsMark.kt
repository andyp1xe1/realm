package realm

/** What the settings button shows besides itself. */
enum class SettingsMark {
    None,

    /** Something runs on: a download, a deploy, a change to the server's addresses. */
    Busy,

    /** Something waits for you: an update to start, a question, a restart, or what went wrong. */
    Waiting,
}
