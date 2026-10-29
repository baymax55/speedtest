package com.example.speedtest.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.speedtest.data.Phase
import com.example.speedtest.data.SpeedTestConfig
import com.example.speedtest.data.SpeedTestEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

/** 界面状态 */
data class UiState(
    val phase: Phase = Phase.IDLE,
    val speedMbps: Double = 0.0,
    val peakMbps: Double = 0.0,
    val progress: Float = 0f,
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val connection: String = "检测中",
    val server: String = "Cloudflare",
    val error: String? = null
) {
    val isRunning: Boolean
        get() = phase == Phase.PING || phase == Phase.DOWNLOAD || phase == Phase.UPLOAD

    val phaseLabel: String
        get() = when (phase) {
            Phase.IDLE -> "准备就绪"
            Phase.PING -> "测量延迟"
            Phase.DOWNLOAD -> "下载测速"
            Phase.UPLOAD -> "上传测速"
            Phase.DONE -> "测试完成"
            Phase.ERROR -> "测试失败"
        }
}

class SpeedTestViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = SpeedTestEngine()
    private val _state = MutableStateFlow(UiState(connection = connectionLabel()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            engine.fetchServerInfo()?.let { colo ->
                _state.update { it.copy(server = "Cloudflare · $colo") }
            }
        }
    }

    fun start() {
        if (_state.value.isRunning) return
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = UiState(
                phase = Phase.PING,
                connection = connectionLabel(),
                server = _state.value.server
            )
            try {
                val ping = engine.ping { index, ms ->
                    _state.update {
                        it.copy(
                            pingMs = ms,
                            progress = (index + 1).toFloat() / SpeedTestConfig.PING_SAMPLES
                        )
                    }
                }
                _state.update {
                    it.copy(pingMs = ping.avgMs, jitterMs = ping.jitterMs)
                }

                _state.update { it.copy(phase = Phase.DOWNLOAD, progress = 0f, speedMbps = 0.0) }
                val down = engine.download { mbps, progress ->
                    _state.update {
                        it.copy(
                            speedMbps = mbps,
                            progress = progress,
                            peakMbps = max(it.peakMbps, mbps)
                        )
                    }
                }
                _state.update { it.copy(downloadMbps = down) }

                _state.update { it.copy(phase = Phase.UPLOAD, progress = 0f, speedMbps = 0.0) }
                val up = engine.upload { mbps, progress ->
                    _state.update {
                        it.copy(
                            speedMbps = mbps,
                            progress = progress,
                            peakMbps = max(it.peakMbps, mbps)
                        )
                    }
                }

                _state.update {
                    it.copy(
                        phase = Phase.DONE,
                        uploadMbps = up,
                        speedMbps = down,
                        progress = 1f
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(phase = Phase.ERROR, error = e.message ?: "测速失败，请检查网络连接")
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(phase = Phase.IDLE, progress = 0f, speedMbps = 0.0) }
    }

    private fun connectionLabel(): String {
        val cm = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "无网络连接"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "其他网络"
        }
    }
}
