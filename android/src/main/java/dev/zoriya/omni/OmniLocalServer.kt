package dev.zoriya.omni

import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import com.google.android.gms.cast.framework.CastContext
import com.margelo.nitro.NitroModules
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object OmniLocalServer {
    private val files = ConcurrentHashMap<String, Uri>()
    private var server: NanoHTTPD? = null
    private val ctx get() = NitroModules.applicationContext!!

    private fun openable(uri: Uri): Uri? = when (uri.scheme) {
        null -> uri.path?.takeIf { it.startsWith("/") }?.let { Uri.fromFile(File(it)) }
        ContentResolver.SCHEME_FILE, ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_ANDROID_RESOURCE -> uri
        else -> null
    }

    private fun InetAddress.toInt() = ByteBuffer.wrap(address).int

    // the url receivers can fetch `uri` from, null if it's not local or we aren't casting
    @Synchronized
    fun urlFor(uri: Uri): Uri? {
        if (openable(uri) == null) return null
        val device = CastContext.getSharedInstance(ctx).sessionManager.currentCastSession
            ?.castDevice?.inetAddress as? Inet4Address ?: return null
        // our ip on the device's network
        val host = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.interfaceAddresses }
            .find {
                val mask = -1 shl (32 - it.networkPrefixLength)
                it.address is Inet4Address && (it.address.toInt() and mask) == (device.toInt() and mask)
            }
            ?.address?.hostAddress ?: return null

        val port = (server ?: Server().also { it.start(); server = it }).listeningPort
        val id = files.entries.find { it.value == uri }?.key
            ?: UUID.randomUUID().toString().also { files[it] = uri }
        return "http://$host:$port/$id".toUri()
    }

    // the reverse of `urlFor`: `http://<ip>:<port>/<id>` -> the local uri.
    // receivers report the served url as contentId, we need ours back to recognize our media.
    fun localFor(url: String): String? = url.toUri().lastPathSegment?.let { files[it] }?.toString()

    fun mimeTypeOf(uri: Uri): String? {
        val file = openable(uri) ?: return null
        return ctx.contentResolver.getType(file)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.lastPathSegment?.substringAfterLast('.')?.lowercase())
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        files.clear()
    }

    private class Server : NanoHTTPD(0) {
        override fun serve(session: IHTTPSession): Response {
            val uri = files[session.uri.removePrefix("/")]
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, null, "")
            val file = ctx.contentResolver.openAssetFileDescriptor(openable(uri)!!, "r")!!

            // receivers seek with `Range: bytes=start-[end]`
            val range = session.headers["range"]?.let { Regex("""bytes=(\d+)-(\d*)""").find(it) }
            val start = range?.groupValues?.get(1)?.toLong() ?: 0
            val end = minOf(range?.groupValues?.get(2)?.toLongOrNull() ?: Long.MAX_VALUE, file.length - 1)
            val input = file.createInputStream().apply { skip(start) }
            val status = if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT
            return newFixedLengthResponse(status, mimeTypeOf(uri), input, end - start + 1).apply {
                addHeader("Accept-Ranges", "bytes")
                if (range != null) addHeader("Content-Range", "bytes $start-$end/${file.length}")
                addHeader("Access-Control-Allow-Origin", "*")
            }
        }
    }
}
