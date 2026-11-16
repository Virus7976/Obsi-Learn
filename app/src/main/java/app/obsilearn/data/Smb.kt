package app.obsilearn.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile

/**
 * Windows and NAS shared folders (SMB), on your Wi-Fi. [Source.uri] is like smb://192.168.1.20/Courses/.
 * [Source.user] may be "DOMAIN\\name". A blank user signs in as a guest.
 */
object Smb {
    fun context(s: Source): CIFSContext {
        val base = SingletonContext.getInstance()
        if (s.user.isBlank()) return base.withGuestCrendentials()
        val domain = s.user.substringBefore('\\', "")
        val name = s.user.substringAfter('\\')
        return base.withCredentials(NtlmPasswordAuthenticator(domain, name, s.secret))
    }

    fun root(s: Source): String {
        var u = s.uri.trim().replace('\\', '/')
        if (!u.startsWith("smb://", true)) u = "smb://" + u.trimStart('/')
        return u.trimEnd('/') + "/"
    }

    fun file(s: Source, uri: String): SmbFile = SmbFile(uri, context(s))

    fun list(s: Source): List<Entry> {
        val out = ArrayList<Entry>()
        fun walk(dir: SmbFile, path: List<String>, depth: Int) {
            if (depth > 8) return
            for (f in dir.listFiles()) {
                val name = f.name.trimEnd('/')
                if (name.startsWith(".") || name.endsWith("$")) continue
                if (f.isDirectory) walk(f, path + name, depth + 1) else out += Entry(path + name, f.url.toString(), f.length())
            }
        }
        try {
            walk(file(s, root(s)), emptyList(), 0)
        } catch (e: jcifs.smb.SmbAuthException) {
            throw SourceException("The share did not take that user name and password.")
        } catch (e: jcifs.smb.SmbException) {
            throw SourceException("Could not open the share: " + (e.message ?: "no answer"))
        }
        return out
    }

    /** Lets the phone's decoder read a file on a share, with seeking. */
    fun media(source: Source?, uri: String): android.media.MediaDataSource {
        val f = file(source ?: Source("", "smb", "", uri), uri)
        val raf = SmbRandomAccessFile(f, "r")
        val size = f.length()
        return object : android.media.MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
                if (position >= size) return -1
                synchronized(raf) { raf.seek(position); return raf.read(buffer, offset, count) }
            }
            override fun getSize(): Long = size
            override fun close() = raf.close()
        }
    }

    /** Lets the player read smb:// files, with seeking. [sourceFor] finds the login for an address. */
    class Factory(private val sourceFor: (String) -> Source?) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(sourceFor)
    }

    class SmbDataSource(private val sourceFor: (String) -> Source?) : BaseDataSource(true) {
        private var raf: SmbRandomAccessFile? = null
        private var uri: Uri? = null
        private var left = 0L

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            val u = dataSpec.uri.toString()
            val s = sourceFor(u) ?: Source("", "smb", "", u)
            val f = file(s, u)
            val r = SmbRandomAccessFile(f, "r")
            raf = r
            r.seek(dataSpec.position)
            left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else f.length() - dataSpec.position
            transferStarted(dataSpec)
            return left
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (left == 0L) return C.RESULT_END_OF_INPUT
            val n = raf!!.read(buffer, offset, minOf(length.toLong(), left).toInt())
            if (n < 0) return C.RESULT_END_OF_INPUT
            left -= n
            bytesTransferred(n)
            return n
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            try { raf?.close() } finally {
                raf = null
                if (uri != null) { uri = null; transferEnded() }
            }
        }
    }
}

/** Sends smb:// to the share reader and everything else to the normal one. */
class RoutingDataSource(private val normal: DataSource, private val smb: DataSource) : DataSource {
    private var current: DataSource? = null
    override fun addTransferListener(transferListener: TransferListener) {
        normal.addTransferListener(transferListener); smb.addTransferListener(transferListener)
    }
    override fun open(dataSpec: DataSpec): Long {
        val d = if (dataSpec.uri.scheme.equals("smb", true)) smb else normal
        current = d
        return d.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)
    override fun getUri(): Uri? = current?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()
    override fun close() { try { current?.close() } finally { current = null } }

    class Factory(private val normal: DataSource.Factory, private val smb: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(normal.createDataSource(), smb.createDataSource())
    }
}
