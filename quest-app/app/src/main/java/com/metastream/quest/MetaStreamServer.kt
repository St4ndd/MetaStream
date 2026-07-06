package com.metastream.quest

import android.content.res.AssetManager
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLDecoder

/**
 * Embedded file-transfer server.
 *
 * Endpoints:
 *   GET    /                 -> web UI (served from assets/web)
 *   GET    /api/info         -> device + storage info (JSON)
 *   GET    /api/files        -> list of stored files (JSON)
 *   PUT    /api/upload       -> raw streamed upload, filename in "X-Filename" header (fast path)
 *   POST   /api/upload       -> multipart/form-data upload (browser form fallback)
 *   GET    /dl/<name>        -> download file (supports HTTP Range for video seeking)
 *   DELETE /api/files/<name> -> delete a file
 *
 * Uploads and downloads are streamed with 64 KB buffers for high throughput.
 */
class MetaStreamServer(
    port: Int,
    private val storageDir: File,
    private val assets: AssetManager,
    private val deviceName: String
) : NanoHTTPD(port) {

    companion object {
        private const val BUFFER = 1 shl 16 // 64 KB
    }

    init {
        if (!storageDir.exists()) storageDir.mkdirs()
    }

    override fun serve(session: IHTTPSession): Response {
        val resp = try {
            route(session)
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Error: ${e.message}"
            )
        }
        addCors(resp)
        return resp
    }

    private fun addCors(r: Response) {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type, X-Filename")
    }

    private fun route(session: IHTTPSession): Response {
        val method = session.method
        val uri = session.uri

        if (method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "")
        }

        return when {
            uri == "/api/info" -> infoResponse()
            uri == "/api/files" && method == Method.GET -> listResponse()
            uri == "/api/upload" && method == Method.PUT -> rawUpload(session)
            uri == "/api/upload" && method == Method.POST -> multipartUpload(session)
            uri.startsWith("/dl/") -> download(session, decode(uri.removePrefix("/dl/")))
            uri.startsWith("/api/files/") && method == Method.DELETE ->
                deleteFile(decode(uri.removePrefix("/api/files/")))
            else -> serveAsset(uri)
        }
    }

    // ---------- Info & listing ----------

    private fun infoResponse(): Response {
        val free = storageDir.usableSpace
        val json = """{"device":"${escape(deviceName)}","free":$free}"""
        return json(json)
    }

    private fun listResponse(): Response {
        val files = storageDir.listFiles { f -> f.isFile && !f.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        val sb = StringBuilder("[")
        files.forEachIndexed { i, f ->
            if (i > 0) sb.append(",")
            sb.append("{\"name\":\"").append(escape(f.name)).append("\",")
            sb.append("\"size\":").append(f.length()).append(",")
            sb.append("\"modified\":").append(f.lastModified()).append("}")
        }
        sb.append("]")
        return json(sb.toString())
    }

    // ---------- Upload ----------

    /** Fast path: raw request body streamed straight to disk, single write. */
    private fun rawUpload(session: IHTTPSession): Response {
        val rawName = session.headers["x-filename"]
            ?: session.parameters["name"]?.firstOrNull()
            ?: "upload_${System.currentTimeMillis()}"
        val name = sanitize(decode(rawName))
        val total = session.headers["content-length"]?.toLongOrNull() ?: -1L
        val dest = uniqueFile(name)

        val input: InputStream = session.inputStream
        FileOutputStream(dest).use { out ->
            val buf = ByteArray(BUFFER)
            if (total >= 0) {
                var remaining = total
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = input.read(buf, 0, toRead)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            } else {
                var n = input.read(buf)
                while (n >= 0) {
                    out.write(buf, 0, n)
                    n = input.read(buf)
                }
            }
            out.flush()
        }
        return json("""{"ok":true,"name":"${escape(dest.name)}","size":${dest.length()}}""")
    }

    /** Fallback: standard multipart/form-data (e.g. plain HTML form). */
    private fun multipartUpload(session: IHTTPSession): Response {
        val parts = HashMap<String, String>()
        session.parseBody(parts)
        var saved = 0
        for ((field, tmpPath) in parts) {
            val origName = session.parameters[field]?.firstOrNull() ?: continue
            val dest = uniqueFile(sanitize(origName))
            val tmp = File(tmpPath)
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
            }
            saved++
        }
        return json("""{"ok":true,"saved":$saved}""")
    }

    // ---------- Download ----------

    private fun download(session: IHTTPSession, name: String): Response {
        val f = File(storageDir, sanitize(name))
        if (!f.exists() || !f.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        val mime = mimeOf(f.name)
        val length = f.length()
        val range = session.headers["range"]

        if (range != null && range.startsWith("bytes=")) {
            val spec = range.substring(6).split("-")
            val start = spec.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = spec.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (length - 1)
            if (start >= length) {
                val r = newFixedLengthResponse(
                    Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", ""
                )
                r.addHeader("Content-Range", "bytes */$length")
                return r
            }
            val safeEnd = minOf(end, length - 1)
            val contentLen = safeEnd - start + 1
            val fis = FileInputStream(f)
            fis.skip(start)
            val r = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, contentLen)
            r.addHeader("Accept-Ranges", "bytes")
            r.addHeader("Content-Range", "bytes $start-$safeEnd/$length")
            return r
        }

        val fis = FileInputStream(f)
        val r = newFixedLengthResponse(Response.Status.OK, mime, fis, length)
        r.addHeader("Accept-Ranges", "bytes")
        r.addHeader("Content-Disposition", "attachment; filename=\"${f.name}\"")
        return r
    }

    private fun deleteFile(name: String): Response {
        val f = File(storageDir, sanitize(name))
        val ok = f.exists() && f.isFile && f.delete()
        return json("""{"ok":$ok}""")
    }

    // ---------- Static assets ----------

    private fun serveAsset(uri: String): Response {
        var path = uri.removePrefix("/")
        if (path.isEmpty()) path = "index.html"
        return try {
            val stream = assets.open("web/$path")
            newChunkedResponse(Response.Status.OK, mimeOf(path), stream)
        } catch (e: Exception) {
            // SPA-style fallback to index for unknown non-api paths
            if (!path.startsWith("api/") && !path.startsWith("dl/")) {
                try {
                    newChunkedResponse(Response.Status.OK, "text/html", assets.open("web/index.html"))
                } catch (e2: Exception) {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
                }
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404")
            }
        }
    }

    // ---------- Helpers ----------

    private fun json(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", body)

    private fun uniqueFile(name: String): File {
        var candidate = File(storageDir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(storageDir, "$base ($i)$ext")
            i++
        }
        return candidate
    }

    private fun sanitize(raw: String): String {
        val base = raw.replace("\\", "/").substringAfterLast('/')
        val cleaned = base.replace(Regex("[\\r\\n\\t]"), "").trim()
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
            "upload_${System.currentTimeMillis()}"
        } else cleaned
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun mimeOf(name: String): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".html") || n.endsWith(".htm") -> "text/html; charset=utf-8"
            n.endsWith(".css") -> "text/css; charset=utf-8"
            n.endsWith(".js") -> "application/javascript; charset=utf-8"
            n.endsWith(".json") -> "application/json"
            n.endsWith(".png") -> "image/png"
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".gif") -> "image/gif"
            n.endsWith(".webp") -> "image/webp"
            n.endsWith(".svg") -> "image/svg+xml"
            n.endsWith(".mp4") -> "video/mp4"
            n.endsWith(".webm") -> "video/webm"
            n.endsWith(".mov") -> "video/quicktime"
            n.endsWith(".mkv") -> "video/x-matroska"
            n.endsWith(".mp3") -> "audio/mpeg"
            n.endsWith(".wav") -> "audio/wav"
            n.endsWith(".pdf") -> "application/pdf"
            n.endsWith(".zip") -> "application/zip"
            n.endsWith(".txt") -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}
