package fukuro

import android.app.Application

class ShelfApp : Application() {
    lateinit var store: Store
        private set
    lateinit var api: AbsApi
        private set
    lateinit var downloads: DownloadRepo
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        api = AbsApi(store)
        downloads = DownloadRepo(this, api, store)
    }

    companion object {
        fun from(app: Application): ShelfApp = app as ShelfApp
    }
}
