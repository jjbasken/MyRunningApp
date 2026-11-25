package com.myrunningapp.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.myrunningapp.BuildConfig
import com.myrunningapp.data.location.RunTracker
import com.myrunningapp.domain.update.AppVersion
import com.myrunningapp.domain.update.DigestingCopy
import com.myrunningapp.domain.update.ReleaseInfo
import com.myrunningapp.domain.update.ReleaseParser
import com.myrunningapp.domain.update.UpdateCheckResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks GitHub Releases for a newer signed APK, downloads it, verifies it and
 * hands it to the system installer.
 *
 * Nothing here runs unless asked: a tap on "Check for updates", or the daily
 * [UpdateCheckWorker] when the user has switched automatic checks on. The only
 * request ever made is to GitHub, and it carries no identifier beyond the
 * package name in the User-Agent GitHub's API requires.
 *
 * Installing replaces the running app, which kills its process, so the run
 * check happens here, immediately before the session is committed — a
 * download takes long enough for a run to start in the meantime.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runTracker: RunTracker,
) {

    private val _state = MutableStateFlow<UpdateState>(
        if (SUPPORTED) UpdateState.Idle else UpdateState.Unsupported,
    )
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val mutex = Mutex()

    /**
     * Downloads outlive the screen that started them: leaving Profile halfway
     * through a download shouldn't cancel it.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            // The updater is never worth crashing the app over.
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Updater coroutine failed", e) },
    )

    private var installJob: Job? = null

    /** [check], from a tap. */
    fun checkInBackground() {
        scope.launch { check() }
    }

    /**
     * [downloadAndInstall], from a tap. A second tap while one is downloading
     * is ignored rather than queued behind it as a duplicate install.
     */
    @Synchronized
    fun installInBackground(release: ReleaseInfo) {
        if (installJob?.isActive == true) return
        installJob = scope.launch { downloadAndInstall(release) }
    }

    /** Whether Android will let this app open its installer, or the user must allow it first. */
    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Asks GitHub for the latest release. Safe to call from a worker or the UI. */
    suspend fun check(): UpdateCheckResult {
        if (!SUPPORTED) return UpdateCheckResult.Failed("Updates are only available in release builds")
        return mutex.withLock {
            // Don't clobber a download or install that is already under way.
            val busy = _state.value.let {
                it is UpdateState.Downloading || it is UpdateState.Installing || it is UpdateState.AwaitingConfirmation
            }
            if (!busy) _state.value = UpdateState.Checking
            val result = try {
                val body = withContext(Dispatchers.IO) { fetchLatestRelease() }
                UpdateCheckResult.compare(AppVersion.parse(BuildConfig.VERSION_NAME), ReleaseParser.parse(body))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitedException) {
                Log.w(TAG, "Update check rate-limited", e)
                UpdateCheckResult.Failed("GitHub is limiting requests from your network. Try again in an hour.")
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                UpdateCheckResult.Failed("Couldn't reach GitHub. Check your connection.")
            }
            if (!busy) {
                _state.value = when (result) {
                    is UpdateCheckResult.Available -> UpdateState.Available(result.release)
                    UpdateCheckResult.UpToDate -> UpdateState.UpToDate
                    is UpdateCheckResult.Failed -> UpdateState.Failed(result.reason, null)
                }
            }
            result
        }
    }

    /** Downloads [release], checks it, and opens a package-installer session for it. */
    suspend fun downloadAndInstall(release: ReleaseInfo) {
        if (!SUPPORTED) return
        mutex.withLock {
            _state.value = UpdateState.Downloading(release, 0f)
            try {
                val apk = withContext(Dispatchers.IO) { download(release) }
                withContext(Dispatchers.IO) { checkArchive(apk, release) }
                if (runTracker.snapshot.value.isActive) {
                    cleanDownloads()
                    _state.value = UpdateState.Failed(
                        "Update postponed: an activity started during the download. Install it after you finish.",
                        release,
                    )
                    return
                }
                _state.value = UpdateState.Installing(release)
                withContext(Dispatchers.IO) { install(apk) }
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(release)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update install failed", e)
                _state.value = UpdateState.Failed(e.message ?: "Update failed", release)
            }
        }
    }

    /** Called by [UpdateInstallReceiver] when Android wants the user to confirm. */
    internal fun onConfirmationNeeded(confirmIntent: Intent) {
        val release = _state.value.installableRelease ?: return
        _state.value = UpdateState.AwaitingConfirmation(release, confirmIntent)
    }

    /** Called by [UpdateInstallReceiver] with the installer's final verdict. */
    internal fun onInstallResult(status: Int, message: String?) {
        _state.value = UpdateState.afterInstall(status, message, _state.value.installableRelease)
        cleanDownloads()
    }

    private fun fetchLatestRelease(): String {
        val connection = (URL(ReleaseParser.LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", context.packageName)
        }
        try {
            val code = connection.responseCode
            // Unauthenticated API calls are limited to 60 an hour per IP address,
            // which a shared network can exhaust.
            if (code == HttpURLConnection.HTTP_FORBIDDEN || code == 429) throw RateLimitedException(code)
            if (code != HttpURLConnection.HTTP_OK) throw IOException("GitHub answered HTTP $code")
            return connection.inputStream.use { stream ->
                val bytes = stream.readNBytesCompat(MAX_RELEASE_JSON_BYTES)
                String(bytes, Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(release: ReleaseInfo): File {
        if (release.sizeBytes > MAX_APK_BYTES) throw IOException("The update is unexpectedly large")
        cleanDownloads()
        val dir = File(context.cacheDir, DOWNLOAD_DIR).apply { mkdirs() }
        // A fixed name: nothing from the network goes into a path.
        val target = File(dir, "update.apk")

        // GitHub redirects release downloads to its asset CDN, HTTPS to HTTPS,
        // which HttpURLConnection follows on its own. The host serving the bytes
        // doesn't matter: they must hash to the digest the API gave us.
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", context.packageName)
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("Download failed: HTTP $code")
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    var lastReported = 0L
                    DigestingCopy.copyVerified(input, output, release.sizeBytes, release.sha256) { written ->
                        // Every ~1% is plenty for a progress bar.
                        if (written - lastReported >= release.sizeBytes / 100 || written == release.sizeBytes) {
                            lastReported = written
                            _state.value = UpdateState.Downloading(release, written.toFloat() / release.sizeBytes)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            connection.disconnect()
        }
        return target
    }

    /**
     * The installer would reject these too, but only after showing the user a
     * dialog. Signature mismatches are left to the installer, which is the
     * authority on them; [onInstallResult] explains that failure.
     */
    private fun checkArchive(apk: File, release: ReleaseInfo) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, 0)
            ?: throw IOException("The downloaded file is not a valid APK")
        if (archive.packageName != context.packageName) {
            throw IOException("The download is for a different app (${archive.packageName})")
        }
        val installed = pm.getPackageInfo(context.packageName, 0)
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
            throw IOException("Release ${release.tag} is not newer than the installed build")
        }
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Once this app installed itself, Android 12+ lets later updates
                // through without a second prompt. The very first one still asks.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        // A retry after a stuck confirmation starts over; drop the old sessions
        // so they don't pile up holding a copy of the APK each.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, UpdateInstallReceiver::class.java),
                    // Mutable: the installer fills in the status extras. The
                    // intent is explicit, so nothing else can claim it.
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
            // The session holds its own copy now.
            apk.delete()
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    private fun cleanDownloads() {
        File(context.cacheDir, DOWNLOAD_DIR).listFiles()?.forEach { it.delete() }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) throw IOException("Release description is unexpectedly large")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private class RateLimitedException(code: Int) : IOException("GitHub answered HTTP $code")

    companion object {
        private const val TAG = "UpdateManager"

        /**
         * Release builds only. A debug build is `com.myrunningapp.debug`, signed
         * with the debug key, so a release APK could never install over it.
         */
        val SUPPORTED: Boolean = !BuildConfig.DEBUG

        private const val TIMEOUT_MS = 20_000
        private const val DOWNLOAD_DIR = "updates"
        private const val MAX_RELEASE_JSON_BYTES = 1 shl 20
        private const val MAX_APK_BYTES = 200L shl 20
    }
}
