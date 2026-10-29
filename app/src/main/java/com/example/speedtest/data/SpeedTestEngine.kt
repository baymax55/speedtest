package com.example.speedtest.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * 基于中国大陆公共镜像的测速引擎。
 *
 * 下载：对镜像站的大文件发 Range 请求，多连接并行拉取
 * 上传：POST 到可写接口（见 [SpeedTestConfig.UPLOAD_URL]）
 * 延迟：请求 1 字节数据的往返时间
 */
class SpeedTestEngine {

    /**
     * 选择节点：对各节点做一次短时下载，按**实测吞吐**择优。
     *
     * 不能只按 RTT 挑。延迟最低的镜像往往不是最快的——公共镜像普遍对单 IP 限速，
     * 实测阿里云镜像单连接只有 ~0.5 MB/s，而腾讯 / 清华 / 华为能到 8~30 MB/s。
     * 按 RTT 选很容易正好选中被限得最狠的那个。
     */
    suspend fun selectNode(nodes: List<SpeedTestNode> = SpeedTestConfig.NODES): SpeedTestNode =
        withContext(Dispatchers.IO) {
            val probed = coroutineScope {
                nodes.map { node ->
                    async {
                        val mbps = runCatching {
                            download(
                                node,
                                durationMs = SpeedTestConfig.SELECT_PROBE_MS,
                                connections = SpeedTestConfig.SELECT_PROBE_CONNECTIONS
                            )
                        }.getOrDefault(0.0)
                        node to mbps
                    }
                }.awaitAll()
            }
            probed.maxByOrNull { it.second }?.takeIf { it.second > 0.0 }?.first
                ?: nodes.first()
        }

    /** 返回 [PingResult]；onSample 会在每次有效采样后回调，便于 UI 实时展示 */
    suspend fun ping(
        node: SpeedTestNode,
        samples: Int = SpeedTestConfig.PING_SAMPLES,
        onSample: (index: Int, ms: Double) -> Unit = { _, _ -> }
    ): PingResult = withContext(Dispatchers.IO) {
        val rtts = ArrayList<Double>(samples)
        repeat(samples + 1) { i ->
            val ms = measureRtt(node.pingUrl)
            if (i == 0) return@repeat // 丢弃预热样本
            rtts += ms
            onSample(i - 1, ms)
        }
        if (rtts.isEmpty()) {
            return@withContext PingResult(0.0, 0.0, 0.0, 0)
        }
        val avg = rtts.average()
        var jitterSum = 0.0
        for (i in 1 until rtts.size) jitterSum += abs(rtts[i] - rtts[i - 1])
        val jitter = if (rtts.size > 1) jitterSum / (rtts.size - 1) else 0.0
        PingResult(minMs = rtts.min(), avgMs = avg, jitterMs = jitter, samples = rtts.size)
    }

    private fun measureRtt(pingUrl: String): Double {
        val conn = URL(pingUrl).openConnection() as HttpURLConnection
        val start = System.nanoTime()
        return try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 6_000
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false // 只测第一跳，避免把跳转算进 RTT
            conn.setRequestProperty("User-Agent", SpeedTestConfig.USER_AGENT)
            conn.setRequestProperty("Range", "bytes=0-0")
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("Cache-Control", "no-store")
            // 复用连接（keep-alive），避免把 TLS 握手时间算进 RTT
            conn.setRequestProperty("Connection", "keep-alive")
            conn.inputStream.use { it.readBytes() }
            (System.nanoTime() - start) / 1_000_000.0
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
    }

    /**
     * 下载测速。
     *
     * 每条连接负责文件的一段，发一个 Range 请求后**连续读到** deadline 或文件末尾，
     * 不再按固定块大小截断重连；读完后再申请下一轮，连接由 [HttpURLConnection]
     * 的连接池复用，省掉重复的 TCP/TLS 握手。
     *
     * @param onTick (瞬时速度 Mbps, 进度 0..1)
     * @return 平均速度 Mbps
     */
    suspend fun download(
        node: SpeedTestNode,
        durationMs: Long = SpeedTestConfig.DOWNLOAD_DURATION_MS,
        connections: Int = SpeedTestConfig.DOWNLOAD_CONNECTIONS,
        onTick: (mbps: Double, progress: Float) -> Unit = { _, _ -> }
    ): Double = withContext(Dispatchers.IO) {
        val total = AtomicLong(0)
        val start = System.currentTimeMillis()
        val deadline = start + durationMs
        // 先取文件大小，才能把文件切成互不重叠的若干段分给各连接
        val fileSize = contentLength(node.downloadUrl)

        coroutineScope {
            repeat(connections) { idx ->
                launch { downloadWorker(node.downloadUrl, fileSize, connections, idx, total, deadline) }
            }
            launch { ticker(total, start, deadline, onTick) }
        }

        val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
        toMbps(total.get(), elapsed)
    }

    /** 读取远端文件大小；失败返回 -1，此时退化为所有连接从 0 开始读整个文件 */
    private fun contentLength(url: String): Long {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", SpeedTestConfig.USER_AGENT)
            conn.responseCode
            conn.contentLengthLong
        } catch (e: IOException) {
            -1L
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun downloadWorker(
        url: String,
        fileSize: Long,
        connections: Int,
        idx: Int,
        total: AtomicLong,
        deadline: Long
    ) {
        val buffer = ByteArray(SpeedTestConfig.READ_CHUNK)
        val sliceStart = if (fileSize > 0) fileSize / connections * idx else 0L
        val sliceEnd = when {
            fileSize <= 0 -> -1L
            idx == connections - 1 -> fileSize - 1
            else -> fileSize / connections * (idx + 1) - 1
        }
        val range = if (sliceEnd >= sliceStart) "bytes=$sliceStart-$sliceEnd" else "bytes=$sliceStart-"

        while (coroutineContext.isActive && System.currentTimeMillis() < deadline) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("User-Agent", SpeedTestConfig.USER_AGENT)
                conn.setRequestProperty("Range", range)
                conn.setRequestProperty("Accept-Encoding", "identity")
                conn.setRequestProperty("Cache-Control", "no-store")
                // 一个请求连续读到 deadline 或这段的末尾，中间不打断
                conn.inputStream.use { input ->
                    while (coroutineContext.isActive && System.currentTimeMillis() < deadline) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total.addAndGet(n.toLong())
                    }
                }
            } catch (e: IOException) {
                conn?.disconnect()
                if (System.currentTimeMillis() < deadline) delay(200)
            }
            // 正常读完不调用 disconnect()：连接回到池里，下一轮直接复用，跳过 TCP/TLS 握手
        }
    }

    /**
     * 上传测速。
     * @return 平均速度 Mbps
     */
    suspend fun upload(
        url: String = SpeedTestConfig.UPLOAD_URL,
        durationMs: Long = SpeedTestConfig.UPLOAD_DURATION_MS,
        connections: Int = SpeedTestConfig.UPLOAD_CONNECTIONS,
        onTick: (mbps: Double, progress: Float) -> Unit = { _, _ -> }
    ): Double = withContext(Dispatchers.IO) {
        val total = AtomicLong(0)
        val start = System.currentTimeMillis()
        val deadline = start + durationMs

        coroutineScope {
            repeat(connections) { idx ->
                launch { uploadWorker(url, total, deadline, idx) }
            }
            launch { ticker(total, start, deadline, onTick) }
        }

        val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
        toMbps(total.get(), elapsed)
    }

    private suspend fun uploadWorker(url: String, total: AtomicLong, deadline: Long, idx: Int) {
        val chunk = ByteArray(SpeedTestConfig.READ_CHUNK) { (it % 251).toByte() }
        val payload = SpeedTestConfig.UPLOAD_BYTES_PER_REQUEST
        while (coroutineContext.isActive && System.currentTimeMillis() < deadline) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", SpeedTestConfig.USER_AGENT)
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setFixedLengthStreamingMode(payload)
                conn.outputStream.use { out ->
                    var sent = 0
                    while (sent < payload && coroutineContext.isActive &&
                        System.currentTimeMillis() < deadline
                    ) {
                        val n = minOf(chunk.size, payload - sent)
                        out.write(chunk, 0, n)
                        sent += n
                        total.addAndGet(n.toLong())
                    }
                    out.flush()
                }
                conn.responseCode
                conn.inputStream?.close()
            } catch (e: IOException) {
                if (System.currentTimeMillis() < deadline) delay(200)
            } finally {
                conn?.disconnect()
            }
        }
    }

    /** 每 150ms 采样一次累计字节数，换算瞬时速度并做指数平滑 */
    private suspend fun ticker(
        total: AtomicLong,
        start: Long,
        deadline: Long,
        onTick: (Double, Float) -> Unit
    ) {
        var lastBytes = 0L
        var lastTs = System.currentTimeMillis()
        var smoothed = 0.0
        while (System.currentTimeMillis() < deadline) {
            delay(150)
            val now = System.currentTimeMillis()
            val bytes = total.get()
            val instant = toMbps(bytes - lastBytes, (now - lastTs).coerceAtLeast(1L))
            smoothed = if (smoothed == 0.0) instant else smoothed * 0.6 + instant * 0.4
            lastBytes = bytes
            lastTs = now
            onTick(smoothed, ((now - start).toFloat() / (deadline - start)).coerceIn(0f, 1f))
        }
    }

    private fun toMbps(bytes: Long, elapsedMs: Long): Double =
        if (elapsedMs <= 0L) 0.0 else bytes * 8.0 / (elapsedMs / 1000.0) / 1_000_000.0
}
