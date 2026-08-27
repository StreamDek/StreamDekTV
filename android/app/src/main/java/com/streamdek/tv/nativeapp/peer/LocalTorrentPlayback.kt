package com.streamdek.tv.nativeapp.peer

import android.content.Context
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Entire native P2P data path: libtorrent, local file choice, buffering and loopback playback. */
object LocalTorrentPlayback {
    private const val POLL_MS = 200L
    private const val METADATA_TIMEOUT_MS = 12_000L
    private const val RANGE_WAIT_MS = 45_000L
    private const val MAX_RESPONSE_BYTES = 4L * 1024L * 1024L
    private const val MAX_CACHE_BYTES = 5L * 1024L * 1024L * 1024L

    private data class PlaybackSession(
        val id: String,
        val infoHash: String,
        val directory: File,
        val handle: TorrentHandle,
        val fileIndex: Int,
        val filePath: String,
        val fileLength: Long,
    )

    private val mutex = Mutex()
    private var manager: SessionManager? = null
    private var server: TorrentLoopbackServer? = null
    @Volatile private var active: PlaybackSession? = null

    suspend fun open(
        context: Context,
        infoHash: String,
        magnetLink: String,
        preferredFilename: String?,
        title: String?,
        season: Int?,
        episode: Int?,
    ): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val normalizedHash = infoHash.trim().lowercase(Locale.US)
            require(normalizedHash.matches(Regex("[a-f0-9]{40}"))) { "This source has an invalid torrent hash." }
            val cacheRoot = File(context.cacheDir, "native-torrents").apply { mkdirs() }
            trimCache(cacheRoot, normalizedHash)

            val sessionManager = manager ?: SessionManager().also {
                it.start()
                manager = it
            }
            val directory = File(cacheRoot, normalizedHash).apply { mkdirs(); setLastModified(System.currentTimeMillis()) }
            val hash = Sha1Hash(normalizedHash)
            val handle = sessionManager.find(hash)?.takeIf { it.isValid } ?: run {
                sessionManager.download(magnetLink, directory)
                waitForHandle(sessionManager, hash)
            }
            handle.resume()
            runCatching { handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }

            val torrentInfo = waitForMetadata(handle)
            val files = torrentInfo.files()
            val selected = selectPeerVideoFile(
                candidates = (0 until torrentInfo.numFiles()).map { index ->
                    PeerFileCandidate(index, files.filePath(index), files.fileSize(index))
                },
                preferredFilename = preferredFilename,
                title = title,
                season = season,
                episode = episode,
            )
            val priorities = Array(torrentInfo.numFiles()) { Priority.IGNORE }
            priorities[selected.index] = Priority.NORMAL
            handle.prioritizeFiles(priorities)

            val playback = PlaybackSession(
                UUID.randomUUID().toString(), normalizedHash, directory, handle,
                selected.index, selected.path, selected.size,
            )
            active = playback
            val loopback = server ?: TorrentLoopbackServer(::currentSession).also {
                it.start()
                server = it
            }
            "http://127.0.0.1:${loopback.port}/torrent/${playback.id}"
        }
    }

    fun release() {
        active = null
        server?.stop()
        server = null
        manager?.stop()
        manager = null
    }

    private fun currentSession(id: String): PlaybackSession? = active?.takeIf { it.id == id }

    private suspend fun waitForHandle(manager: SessionManager, hash: Sha1Hash): TorrentHandle {
        val deadline = System.currentTimeMillis() + METADATA_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            manager.find(hash)?.takeIf { it.isValid }?.let { return it }
            delay(POLL_MS)
        }
        throw IllegalStateException("The local peer engine did not accept this source in time.")
    }

    private suspend fun waitForMetadata(handle: TorrentHandle): com.frostwire.jlibtorrent.TorrentInfo {
        val deadline = System.currentTimeMillis() + METADATA_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            handle.torrentFile()?.let { return it }
            delay(POLL_MS)
        }
        val status = runCatching { handle.status() }.getOrNull()
        throw IllegalStateException(
            if ((status?.numPeers() ?: 0) == 0) "No peers are sharing this source right now."
            else "Peers were found but did not provide the torrent file list in time.",
        )
    }

    private fun trimCache(root: File, keepHash: String) {
        val directories = root.listFiles()?.filter(File::isDirectory).orEmpty().sortedBy(File::lastModified)
        var total = directories.sumOf { directory -> directory.walkTopDown().filter(File::isFile).sumOf(File::length) }
        for (directory in directories) {
            if (total <= MAX_CACHE_BYTES) break
            if (directory.name.equals(keepHash, ignoreCase = true)) continue
            val size = directory.walkTopDown().filter(File::isFile).sumOf(File::length)
            if (directory.deleteRecursively()) total -= size
        }
    }

    private class TorrentLoopbackServer(
        private val sessionProvider: (String) -> PlaybackSession?,
    ) {
        private val running = AtomicBoolean(false)
        private val acceptExecutor = Executors.newSingleThreadExecutor()
        private val requestExecutor = Executors.newCachedThreadPool()
        private var socket: ServerSocket? = null
        @Volatile var port: Int = 0
            private set

        fun start() {
            val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            socket = listener
            port = listener.localPort
            running.set(true)
            acceptExecutor.execute {
                while (running.get()) {
                    try {
                        requestExecutor.execute { handle(listener.accept()) }
                    } catch (_: SocketException) {
                        break
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        fun stop() {
            running.set(false)
            runCatching { socket?.close() }
            socket = null
            acceptExecutor.shutdownNow()
            requestExecutor.shutdownNow()
        }

        private fun handle(client: Socket) = client.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine()?.split(' ') ?: return@use
            val method = requestLine.getOrNull(0)?.uppercase(Locale.US) ?: "GET"
            val id = requestLine.getOrNull(1)?.substringAfter("/torrent/")?.substringBefore('?').orEmpty()
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                val split = line.indexOf(':')
                if (split > 0) headers[line.substring(0, split).trim().lowercase(Locale.US)] = line.substring(split + 1).trim()
            }
            val session = sessionProvider(id) ?: return@use writeError(socket, 404, "Torrent session not found")
            stream(socket, method, headers["range"], session)
        }

        private fun stream(socket: Socket, method: String, range: String?, session: PlaybackSession) {
            val (start, requestedEnd) = parseRange(range, session.fileLength)
            if (start !in 0 until session.fileLength) return writeError(socket, 416, "Invalid range")
            val end = minOf(requestedEnd, start + MAX_RESPONSE_BYTES - 1, session.fileLength - 1)
            prioritize(session, start, end)
            if (!waitForPieces(session, start, end)) return writeError(socket, 503, "Torrent data is not ready")

            val partial = range != null || start > 0L || end < session.fileLength - 1
            val code = if (partial) 206 else 200
            val length = end - start + 1
            val output = socket.getOutputStream()
            val header = buildString {
                append("HTTP/1.1 $code ${if (code == 206) "Partial Content" else "OK"}\r\n")
                append("Content-Type: ${contentType(session.filePath)}\r\n")
                append("Content-Length: $length\r\nAccept-Ranges: bytes\r\n")
                if (partial) append("Content-Range: bytes $start-$end/${session.fileLength}\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(header.toByteArray(StandardCharsets.UTF_8))
            if (method == "HEAD") return output.flush()
            RandomAccessFile(File(session.directory, session.filePath), "r").use { input ->
                input.seek(start)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var remaining = length
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
            output.flush()
        }

        private fun prioritize(session: PlaybackSession, start: Long, end: Long) {
            val info = session.handle.torrentFile() ?: return
            val pieceLength = info.pieceLength().toLong().coerceAtLeast(1L)
            val fileOffset = info.files().fileOffset(session.fileIndex)
            val first = ((fileOffset + start) / pieceLength).toInt()
            val last = ((fileOffset + end) / pieceLength).toInt()
            for (piece in first..last) runCatching { session.handle.setPieceDeadline(piece, 0) }
        }

        private fun waitForPieces(session: PlaybackSession, start: Long, end: Long): Boolean {
            val info = session.handle.torrentFile() ?: return false
            val pieceLength = info.pieceLength().toLong().coerceAtLeast(1L)
            val fileOffset = info.files().fileOffset(session.fileIndex)
            val firstPiece = ((fileOffset + start) / pieceLength).toInt()
            val lastPiece = ((fileOffset + end) / pieceLength).toInt()
            val deadline = System.currentTimeMillis() + RANGE_WAIT_MS
            while (System.currentTimeMillis() < deadline && running.get()) {
                if ((firstPiece..lastPiece).all(session.handle::havePiece)) return true
                Thread.sleep(POLL_MS)
            }
            return false
        }

        private fun parseRange(header: String?, total: Long): Pair<Long, Long> {
            if (header.isNullOrBlank() || !header.startsWith("bytes=")) return 0L to (total - 1)
            return runCatching {
                val parts = header.removePrefix("bytes=").split('-', limit = 2)
                val start = parts[0].toLongOrNull() ?: 0L
                start to (parts.getOrNull(1)?.toLongOrNull() ?: (total - 1))
            }.getOrDefault(0L to (total - 1))
        }

        private fun writeError(socket: Socket, code: Int, message: String) {
            val body = message.toByteArray(StandardCharsets.UTF_8)
            socket.getOutputStream().apply {
                write("HTTP/1.1 $code Error\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                write(body)
                flush()
            }
        }

        private fun contentType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts", "m2ts" -> "video/mp2t"
            else -> "application/octet-stream"
        }
    }
}
