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

/** 一次完整的测速记录，用于历史列表持久化 */
data class SpeedTestRecord(
    val id: Long,
    val connection: String,
    val server: String,
    val pingMs: Double,
    val jitterMs: Double,
    val downloadMbps: Double,
    val uploadMbps: Double
)

/**
 * 测速节点。
 *
 * @param name 展示名称
 * @param downloadUrl 支持 HTTP Range 的大文件，用于下载测速
 * @param pingUrl 轻量地址，仅用于 RTT 探测
 */
data class SpeedTestNode(
    val name: String,
    val downloadUrl: String,
    val pingUrl: String
)

object SpeedTestConfig {

    /** 中国大陆公共镜像节点，启动时按延迟自动选优 */
    val NODES = listOf(
        SpeedTestNode(
            name = "腾讯云镜像",
            downloadUrl = "https://mirrors.cloud.tencent.com/ubuntu/ls-lR.gz",
            pingUrl = "https://mirrors.cloud.tencent.com/"
        ),
        SpeedTestNode(
            name = "清华 TUNA",
            downloadUrl = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu/ls-lR.gz",
            pingUrl = "https://mirrors.tuna.tsinghua.edu.cn/"
        ),
        SpeedTestNode(
            name = "华为云镜像",
            downloadUrl = "https://mirrors.huaweicloud.com/ubuntu/ls-lR.gz",
            pingUrl = "https://mirrors.huaweicloud.com/"
        ),
        SpeedTestNode(
            name = "阿里云镜像",
            downloadUrl = "https://mirrors.aliyun.com/ubuntu/ls-lR.gz",
            pingUrl = "https://mirrors.aliyun.com/"
        )
    )

    /**
     * 上传测速地址。
     *
     * 国内公共镜像均为只读，没有可匿名写入的公开接口，因此默认仍使用
     * Cloudflare 的 __up（境外，上行结果会受国际出口带宽限制）。
     * 如需准确的上行数据，可换成自建接口，例如 nginx:
     *     location = /up { client_max_body_size 64m; return 204; }
     */
    const val UPLOAD_URL = "https://speed.cloudflare.com/__up"

    /** 延迟采样次数（首次请求用于 DNS/TLS 预热，不计入统计） */
    const val PING_SAMPLES = 12

    /** 下载 / 上传测试时长 */
    const val DOWNLOAD_DURATION_MS = 10_000L
    const val UPLOAD_DURATION_MS = 8_000L

    /**
     * 选节点时的短时实测下载。
     *
     * 所有节点并发探测，每个节点 1 条连接，总探测流量约等于正式测试 1.5 秒的用量。
     */
    const val SELECT_PROBE_MS = 1_500L
    const val SELECT_PROBE_CONNECTIONS = 1

    /** 并发连接数（多线程才能跑满高带宽链路） */
    const val DOWNLOAD_CONNECTIONS = 4
    const val UPLOAD_CONNECTIONS = 3

    /** 上传单次请求的字节数 */
    const val UPLOAD_BYTES_PER_REQUEST = 8_000_000

    const val READ_CHUNK = 64 * 1024

    const val USER_AGENT = "SpeedTest/1.0 (Android)"
}
