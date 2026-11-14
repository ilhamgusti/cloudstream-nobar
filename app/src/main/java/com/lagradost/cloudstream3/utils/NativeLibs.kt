package com.lagradost.cloudstream3.utils

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.lagradost.cloudstream3.BuildConfig
import dalvik.system.BaseDexClassLoader
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Native libraries that are not shipped in the APK but downloaded on first use, to keep the app
 * light (see app/build.gradle.kts, `nativeGroups`):
 *  - [FFMPEG]: nextlib's FFmpeg decoders (software decoding). Fetched in the background after the
 *    first launch; until then the player simply runs without the FFmpeg renderers.
 *  - [TORRENT]: the torrent server (libgojni). Fetched the first time a torrent is played.
 *
 * The libraries' own Java code calls `System.loadLibrary(name)`, which only looks in the app's
 * native library path. So once the files are verified we (1) `System.load` them by absolute path in
 * dependency order, so each library's dependencies are already loaded when the next one links,
 * and (2) add their folder to the class loader's native library path, so that later
 * `System.loadLibrary` calls find them. Any failure leaves the feature unavailable, never a crash.
 */
object NativeLibs {
    private const val TAG = "NativeLibs"
    const val FFMPEG = "ffmpeg"
    const val TORRENT = "torrent"

    /** Load order inside a group (dependencies first); unlisted files load last. */
    private val order = mapOf(
        FFMPEG to listOf("libavutil.so", "libswresample.so", "libswscale.so", "libavcodec.so", "libmedia3ext.so"),
        TORRENT to listOf("libgojni.so"),
    )

    data class Spec(val group: String, val abi: String, val name: String, val sha256: String, val size: Long)

    fun parse(spec: String): List<Spec> = spec.split(',').mapNotNull { item ->
        val p = item.split(':')
        if (p.size != 5) return@mapNotNull null
        Spec(p[0], p[1], p[2], p[3], p[4].toLongOrNull() ?: return@mapNotNull null)
    }

    /** The ABI whose libraries this process can load: arm64 in a 64-bit process, else armv7. */
    fun abi(): String? {
        val is64 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Process.is64Bit() else false
        val supported = if (is64) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return listOf("arm64-v8a", "armeabi-v7a").firstOrNull { it in supported }
    }

    private val specs by lazy { parse(BuildConfig.NATIVE_LIBS) }
    private var root: File? = null
    private val loaded = mutableSetOf<String>()
    private val tag get() = BuildConfig.NATIVE_URL.substringAfterLast('/')

    fun init(context: Context) {
        val base = File(context.noBackupFilesDir, "natives")
        root = File(base, tag)
        // Libraries of other app versions are dead weight.
        base.listFiles()?.filter { it.name != tag }?.forEach { it.deleteRecursively() }
    }

    private fun files(group: String): List<Spec> {
        val abi = abi() ?: return emptyList()
        val list = specs.filter { it.group == group && it.abi == abi }
        val o = order[group].orEmpty()
        return list.sortedBy { o.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    fun isLoaded(group: String) = synchronized(this) { group in loaded }

    fun isDownloaded(group: String): Boolean {
        val dir = root ?: return false
        val list = files(group)
        return list.isNotEmpty() && list.all { File(dir, it.name).let { f -> f.isFile && f.length() == it.size } }
    }

    /**
     * Downloads (if needed) and loads a group. Blocking: call it off the main thread.
     * [onProgress] gets 0..100 while downloading. Returns whether the group is usable.
     */
    @Synchronized
    fun ensure(group: String, onProgress: (Int) -> Unit = {}): Boolean {
        if (group in loaded) return true
        val dir = root ?: return false
        val list = files(group)
        if (list.isEmpty()) return false
        return try {
            dir.mkdirs()
            val total = list.sumOf { it.size }.coerceAtLeast(1)
            var done = 0L
            for (spec in list) {
                val target = File(dir, spec.name)
                if (!(target.isFile && target.length() == spec.size)) {
                    download(spec, target) { n -> onProgress(((done + n) * 100 / total).toInt()) }
                }
                done += spec.size
            }
            onProgress(100)
            load(group, dir, list)
        } catch (t: Throwable) {
            Log.w(TAG, "$group unavailable", t)
            false
        }
    }

    /** Loads a group only if its files are already on the device (no network). */
    @Synchronized
    fun loadIfDownloaded(group: String): Boolean {
        if (group in loaded) return true
        val dir = root ?: return false
        if (!isDownloaded(group)) return false
        return try {
            load(group, dir, files(group))
        } catch (t: Throwable) {
            Log.w(TAG, "$group failed to load", t)
            false
        }
    }

    private fun load(group: String, dir: File, list: List<Spec>): Boolean {
        addNativePath(dir)
        for (spec in list) System.load(File(dir, spec.name).absolutePath)
        loaded += group
        Log.i(TAG, "$group loaded from $dir")
        return true
    }

    private fun download(spec: Spec, target: File, onBytes: (Long) -> Unit) {
        val url = "${BuildConfig.NATIVE_URL}/${spec.abi}-${spec.name}"
        val tmp = File(target.parentFile, target.name + ".part")
        var conn = URL(url).openConnection() as HttpURLConnection
        // GitHub answers with a redirect to its file host.
        var hops = 0
        while (true) {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                val next = conn.getHeaderField("Location") ?: throw IOException("redirect without location")
                conn.disconnect()
                conn = URL(URL(url), next).openConnection() as HttpURLConnection
                continue
            }
            if (code != 200) throw IOException("HTTP $code for $url")
            break
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    read += n
                    onBytes(read)
                }
            }
        }
        conn.disconnect()
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (read != spec.size || sha != spec.sha256) {
            tmp.delete()
            throw IOException("${spec.name}: checksum mismatch")
        }
        tmp.setReadOnly()
        if (!tmp.renameTo(target)) throw IOException("could not move ${spec.name}")
    }

    /** Adds [dir] to this app's class loader native library path, so System.loadLibrary finds it. */
    @Suppress("UNCHECKED_CAST", "DiscouragedPrivateApi")
    private fun addNativePath(dir: File) {
        val loader = NativeLibs::class.java.classLoader as? BaseDexClassLoader
            ?: throw IllegalStateException("unexpected class loader")
        val pathList = BaseDexClassLoader::class.java.getDeclaredField("pathList")
            .apply { isAccessible = true }.get(loader)!!
        val dplClass = pathList.javaClass

        // Android 9+: DexPathList.addNativePath(Collection<String>) does all the bookkeeping.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                dplClass.getDeclaredMethod("addNativePath", Collection::class.java)
                    .apply { isAccessible = true }.invoke(pathList, listOf(dir.absolutePath))
                return
            } catch (_: Throwable) {
                // Fall through to the field update below.
            }
        }

        val dirsField = dplClass.getDeclaredField("nativeLibraryDirectories").apply { isAccessible = true }
        val dirs = (dirsField.get(pathList) as List<File>).toMutableList()
        if (dirs.none { it == dir }) dirs.add(0, dir)
        dirsField.set(pathList, ArrayList(dirs))

        val systemDirs = dplClass.getDeclaredField("systemNativeLibraryDirectories")
            .apply { isAccessible = true }.get(pathList) as List<File>
        val all = ArrayList<File>(dirs).apply { addAll(systemDirs) }
        val elements = try {
            dplClass.getDeclaredMethod("makePathElements", List::class.java)
                .apply { isAccessible = true }.invoke(null, all)
        } catch (_: NoSuchMethodException) {
            dplClass.getDeclaredMethod("makePathElements", List::class.java, File::class.java, List::class.java)
                .apply { isAccessible = true }.invoke(null, all, null, ArrayList<IOException>())
        }
        dplClass.getDeclaredField("nativeLibraryPathElements").apply { isAccessible = true }.set(pathList, elements)
    }
}
