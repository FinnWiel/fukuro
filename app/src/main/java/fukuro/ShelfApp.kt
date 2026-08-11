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

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        api = AbsApi(store)
        local = LocalLibrary(this, store)
        cache = LibraryCache(this)
        downloads = DownloadRepo(this, api, store, local)
    }

    companion object {
        fun from(app: Application): ShelfApp = app as ShelfApp
    }
}
