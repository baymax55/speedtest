package com.example.speedtest.data

/** 测速阶段 */
enum class Phase {
    IDLE, PING, DOWNLOAD, UPLOAD, DONE, ERROR
}

/** 一次延迟测试的结果 */
data class PingResult(
    val minMs: Double,
    val avgMs: Double,
    val jitterMs: Double,
    val samples: Int
)

/** 测速可调参数 */
object SpeedTestConfig {
    const val BASE_URL = "https://speed.cloudflare.com"

    /** 延迟采样次数（首次请求用于 DNS/TLS 预热，不计入统计） */
    const val PING_SAMPLES = 12

    /** 下载 / 上传测试时长 */
    const val DOWNLOAD_DURATION_MS = 10_000L
    const val UPLOAD_DURATION_MS = 8_000L

    /** 并发连接数（多线程才能跑满高带宽链路） */
    const val DOWNLOAD_CONNECTIONS = 4
    const val UPLOAD_CONNECTIONS = 3

    /** 单次请求的数据量 */
    const val DOWNLOAD_BYTES_PER_REQUEST = 25_000_000
    const val UPLOAD_BYTES_PER_REQUEST = 8_000_000

    const val READ_CHUNK = 64 * 1024
}
