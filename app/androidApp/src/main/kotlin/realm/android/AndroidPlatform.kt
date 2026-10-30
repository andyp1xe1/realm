package realm.android

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import realm.ByteReader
import realm.Incoming
import realm.LocalFile
import realm.Platform
import realm.Prefs
import realm.Recording
import realm.jvm.Dates
import realm.jvm.JFile
import realm.jvm.StreamReader
import realm.jvm.download
import realm.jvm.guessMime
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt

class AndroidPlatform(private val activity: ComponentActivity) : Platform {
    override val name = "android"
    override val canTakePhoto = true
    override val isForeground get() = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    init {
        // Opened files, photos and voice notes are already uploaded; keep a day's worth, then drop them.
        thread(isDaemon = true) {
            val cutoff = System.currentTimeMillis() - 24 * 3600 * 1000
            listOf("open", "camera", "voice").forEach { dir ->
                File(activity.cacheDir, dir).walkBottomUp().forEach { f ->
                    if (f.isFile && f.lastModified() < cutoff) f.delete()
                    else if (f.isDirectory && f.list()?.isEmpty() == true) f.delete()
                }
            }
        }
    }

    private val sp = activity.getSharedPreferences("realm", Context.MODE_PRIVATE)
    override val prefs = object : Prefs {
        override fun get(key: String) = sp.getString(key, null)
        override fun set(key: String, value: String?) {
            val e = sp.edit()
            if (value == null) e.remove(key) else e.putString(key, value)
            e.apply()
        }
    }

    private val incomingChannel = Channel<Incoming>(Channel.UNLIMITED)
    override val incoming: Flow<Incoming> = incomingChannel.receiveAsFlow()

    // Activity-result launchers have to be registered up front; each call awaits its own deferred.
    private var picked: CompletableDeferred<List<Uri>>? = null
    private val pick = activity.registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { picked?.complete(it) }
    private var shot: CompletableDeferred<Boolean>? = null
    private val photo = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { shot?.complete(it) }
    private var granted: CompletableDeferred<Boolean>? = null
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted?.complete(it) }

    fun handleShare(intent: Intent?) {
        intent ?: return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelable(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelables(Intent.EXTRA_STREAM)
            else -> return
        }
        if (uris.isNotEmpty()) incomingChannel.trySend(Incoming.Files(uris.map { UriFile(activity, it) }, text))
        else if (!text.isNullOrBlank()) incomingChannel.trySend(Incoming.Text(text))
        intent.action = null // don't re-send on recreation
    }

    override suspend fun pickFiles(): List<LocalFile> {
        val d = CompletableDeferred<List<Uri>>().also { picked = it }
        pick.launch("*/*")
        return d.await().map { UriFile(activity, it) }
    }

    override suspend fun takePhoto(): LocalFile? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(activity.cacheDir, "camera/IMG_$stamp.jpg").apply { parentFile?.mkdirs() }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val d = CompletableDeferred<Boolean>().also { shot = it }
        photo.launch(uri)
        return if (d.await() && file.length() > 0) JFile(file, "image/jpeg") else null.also { file.delete() }
    }

    override suspend fun startRecording(): Recording? {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val d = CompletableDeferred<Boolean>().also { granted = it }
            permission.launch(Manifest.permission.RECORD_AUDIO)
            if (!d.await()) return null
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(activity.cacheDir, "voice/voice_$stamp.m4a").apply { parentFile?.mkdirs() }
        return runCatching { AndroidRecording(activity, file) }.getOrNull()
    }

    override fun clipboardFiles(): List<LocalFile> {
        val clip = activity.getSystemService(ClipboardManager::class.java).primaryClip ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri?.let { u -> UriFile(activity, u) } }
    }

    override fun copyText(text: String) {
        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("realm", text))
    }

    override suspend fun save(url: String, auth: String?, name: String): String {
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        auth?.let { req.addRequestHeader("Authorization", it) }
        activity.getSystemService(DownloadManager::class.java).enqueue(req)
        return "Downloading to Downloads/$name"
    }

    override suspend fun open(url: String, auth: String?, name: String) {
        val file = File(activity.cacheDir, "open/${url.hashCode().toUInt()}/$name")
        withContext(Dispatchers.IO) { if (!file.exists()) download(url, auth, file) }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, guessMime(name) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            throw IOException("No app can open $name")
        }
    }

    override fun decodeImage(bytes: ByteArray, maxSize: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val rotation = runCatching {
            when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val out = if (rotation == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        return out.asImageBitmap()
    }

    override fun formatTime(epochMs: Long) = Dates.time(epochMs)
    override fun formatDay(epochMs: Long) = Dates.day(epochMs)
}

private class UriFile(private val context: Context, private val uri: Uri) : LocalFile {
    override val name: String
    override val size: Long
    override val mime: String?

    init {
        var n: String? = null
        var s = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    n = c.getString(0)
                    if (!c.isNull(1)) s = c.getLong(1)
                }
            }
        }
        if (s < 0 && uri.scheme == "file") s = uri.path?.let { File(it).length() } ?: -1
        mime = context.contentResolver.getType(uri) ?: n?.let { guessMime(it) }
        name = n ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        size = s
    }

    override fun reader(): ByteReader =
        StreamReader(context.contentResolver.openInputStream(uri) ?: throw IOException("can't read $name"))
}

private class AndroidRecording(context: Context, private val file: File) : Recording {
    @Suppress("DEPRECATION")
    private val rec = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()).apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioEncodingBitRate(96_000)
        setAudioSamplingRate(44_100)
        setOutputFile(file.absolutePath)
        prepare()
        start()
    }

    override fun level() = sqrt(rec.maxAmplitude / 32767f)

    override suspend fun stop(): LocalFile? {
        val ok = runCatching { rec.stop() }.isSuccess // throws if stopped right after start
        rec.release()
        return if (ok && file.length() > 0) JFile(file, "audio/mp4") else null.also { file.delete() }
    }

    override fun cancel() {
        runCatching { rec.stop() }
        rec.release()
        file.delete()
    }
}

@Suppress("DEPRECATION")
private fun Intent.parcelable(key: String): Uri? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Uri::class.java) else getParcelableExtra(key)

@Suppress("DEPRECATION")
private fun Intent.parcelables(key: String): List<Uri> =
    (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, Uri::class.java) else getParcelableArrayListExtra(key)) ?: emptyList()
