package com.example.speedtest.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * 基于 Cloudflare 公共测速节点的引擎。
 *
 * 下载：__down?bytes=N 返回 N 字节随机数据
 * 上传：POST __up 接收任意二进制数据
 * 延迟：请求 0 字节数据的往返时间
 */
class SpeedTestEngine {

    /** 返回 [PingResult]；onSample 会在每次有效采样后回调，便于 UI 实时展示 */
    suspend fun ping(
        samples: Int = SpeedTestConfig.PING_SAMPLES,
        onSample: (index: Int, ms: Double) -> Unit = { _, _ -> }
    ): PingResult = withContext(Dispatchers.IO) {
        val rtts = ArrayList<Double>(samples)
        repeat(samples + 1) { i ->
            val ms = measureRtt()
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

    private fun measureRtt(): Double {
        val url = URL("${SpeedTestConfig.BASE_URL}/__down?bytes=0&r=${System.nanoTime()}")
        val conn = url.openConnection() as HttpURLConnection
        val start = System.nanoTime()
        return try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 6_000
            conn.requestMethod = "GET"
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
     * @param onTick (瞬时速度 Mbps, 进度 0..1)
     * @return 平均速度 Mbps
     */
    suspend fun download(
        durationMs: Long = SpeedTestConfig.DOWNLOAD_DURATION_MS,
        connections: Int = SpeedTestConfig.DOWNLOAD_CONNECTIONS,
        onTick: (mbps: Double, progress: Float) -> Unit = { _, _ -> }
    ): Double = withContext(Dispatchers.IO) {
        val total = AtomicLong(0)
        val start = System.currentTimeMillis()
        val deadline = start + durationMs

        coroutineScope {
            repeat(connections) { idx ->
                launch { downloadWorker(total, deadline, idx) }
            }
            launch { ticker(total, start, deadline, onTick) }
        }

        val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
        toMbps(total.get(), elapsed)
    }

    private suspend fun downloadWorker(total: AtomicLong, deadline: Long, idx: Int) {
        val buffer = ByteArray(SpeedTestConfig.READ_CHUNK)
        while (coroutineContext.isActive && System.currentTimeMillis() < deadline) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(
                    "${SpeedTestConfig.BASE_URL}/__down?bytes=${SpeedTestConfig.DOWNLOAD_BYTES_PER_REQUEST}" +
                        "&r=${System.nanoTime()}_$idx"
                )
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("Cache-Control", "no-store")
                conn.inputStream.use { input ->
                    while (coroutineContext.isActive && System.currentTimeMillis() < deadline) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total.addAndGet(n.toLong())
                    }
                }
            } catch (e: IOException) {
                if (System.currentTimeMillis() < deadline) delay(200)
            } finally {
                conn?.disconnect()
            }
        }
    }

    /**
     * 上传测速。
     * @return 平均速度 Mbps
     */
    suspend fun upload(
        durationMs: Long = SpeedTestConfig.UPLOAD_DURATION_MS,
        connections: Int = SpeedTestConfig.UPLOAD_CONNECTIONS,
        onTick: (mbps: Double, progress: Float) -> Unit = { _, _ -> }
    ): Double = withContext(Dispatchers.IO) {
        val total = AtomicLong(0)
        val start = System.currentTimeMillis()
        val deadline = start + durationMs

        coroutineScope {
            repeat(connections) { idx ->
                launch { uploadWorker(total, deadline, idx) }
            }
            launch { ticker(total, start, deadline, onTick) }
        }

        val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
        toMbps(total.get(), elapsed)
    }

    private suspend fun uploadWorker(total: AtomicLong, deadline: Long, idx: Int) {
        val chunk = ByteArray(SpeedTestConfig.READ_CHUNK) { (it % 251).toByte() }
        val payload = SpeedTestConfig.UPLOAD_BYTES_PER_REQUEST
        while (coroutineContext.isActive && System.currentTimeMillis() < deadline) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("${SpeedTestConfig.BASE_URL}/__up?r=${System.nanoTime()}_$idx")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8_000
                conn.readTimeout = 15_000
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

    /** 读取测速节点信息（运营商出口 / 机房代码） */
    suspend fun fetchServerInfo(): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("${SpeedTestConfig.BASE_URL}/meta")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val colo = Regex("\"colo\"\\s*:\\s*\"([A-Za-z]{3})\"").find(body)?.groupValues?.get(1)
                colo
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
