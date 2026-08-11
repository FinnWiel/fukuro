package fukuro

import android.app.Application

class ShelfApp : Application() {
    lateinit var store: Store
        private set
    lateinit var api: AbsApi
        private set
    lateinit var downloads: DownloadRepo
        private set
    lateinit var local: LocalLibrary
        private set
    lateinit var cache: LibraryCache
        private set
    lateinit var updater: Updater
        private set

    /**
     * Full item details shared between the UI and the player service. Continue
     * Listening books are fetched into it ahead of time, so pressing play does not
     * wait on a round trip to the server first.
     */
    val itemCache = java.util.concurrent.ConcurrentHashMap<String, LibraryItem>()

    override fun onCreate() {
        super.onCreate()
        installCrashLog()
        store = Store(this)
        api = AbsApi(store)
        local = LocalLibrary(this, store)
        cache = LibraryCache(this)
        downloads = DownloadRepo(this, api, store, local)
        updater = Updater(this, api.http)
    }

    /**
     * Sideloaded builds have no crash reporting, so keep the last stack trace on disk
     * and show it in Settings — otherwise a crash is just "the app closed".
     */
    private fun installCrashLog() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = java.io.StringWriter()
                error.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(filesDir, CRASH_FILE).writeText(
                    "${java.util.Date()}\nthread: ${thread.name}\n\n$sw"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
        fun from(app: Application): ShelfApp = app as ShelfApp
    }
}
