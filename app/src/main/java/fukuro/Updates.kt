package fukuro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tagName: String = "",
    val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
private data class GhAsset(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0,
)

/** A published release newer than the build that's running. */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
    val pageUrl: String,
)

/**
 * Checks the project's GitHub releases and fetches the APK.
 *
 * Android never lets an ordinary sideloaded app install anything on its own, so this
 * goes as far as it is allowed — notice the release, download it — and hands the last
 * step to the system installer, which is what asks the user. That confirmation is not
 * something the app can skip; [canInstall] only reports whether the user has allowed
 * this app to *ask*.
 */
class Updater(private val context: Context, private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Where a downloaded APK waits to be installed. Nothing is kept here long. */
    private val dir: File get() = File(context.cacheDir, "updates").apply { mkdirs() }

    /** The newest release, or null when this build is already it (or has no APK attached). */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("GitHub returned HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        val release = json.decodeFromString<GhRelease>(body)
        val version = release.tagName.trimStart('v', 'V')
        if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0) return@withContext null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return@withContext null
        UpdateInfo(
            version = version,
            notes = release.body.trim(),
            apkUrl = apk.url,
            apkName = apk.name,
            sizeBytes = apk.size,
            pageUrl = release.htmlUrl,
        )
    }

    /** Downloads the APK, reporting 0..1 as it goes. */
    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            dir.listFiles()?.forEach { it.delete() } // only ever keep the one being installed
            val out = File(dir, info.apkName)
            // the shared client is tuned for a local server; an APK over mobile data is not
            val client = http.newBuilder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .build()
            client.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("Download failed: HTTP ${resp.code}")
                val stream = resp.body?.byteStream() ?: error("Empty download")
                val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: info.sizeBytes
                stream.use { input ->
                    out.outputStream().use { sink ->
                        val buf = ByteArray(128 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            out
        }

    /** Has the user allowed this app to hand APKs to the installer? */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system page where that permission lives. */
    fun requestInstallPermission() {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Hands the APK to the system installer; it, not the app, asks the user to confirm. */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Compares dotted versions numerically, so 1.3.10 beats 1.3.9 (a string compare would
 * not). Anything unparseable counts as 0, which makes a malformed tag look older than
 * the running build rather than prompting an endless update.
 */
fun compareVersions(a: String, b: String): Int {
    fun parts(v: String) = v.trim().split('.', '-').mapNotNull { it.toIntOrNull() }
    val x = parts(a)
    val y = parts(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d
    }
    return 0
}
