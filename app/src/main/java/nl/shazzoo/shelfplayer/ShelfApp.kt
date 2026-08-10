package nl.shazzoo.shelfplayer

import android.app.Application
import nl.shazzoo.shelfplayer.data.AbsApi
import nl.shazzoo.shelfplayer.data.DownloadRepo
import nl.shazzoo.shelfplayer.data.Store

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
        downloads = DownloadRepo(this, api)
    }

    companion object {
        fun from(app: Application): ShelfApp = app as ShelfApp
    }
}
