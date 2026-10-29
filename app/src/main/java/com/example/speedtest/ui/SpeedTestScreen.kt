package com.example.speedtest.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.speedtest.data.Phase
import com.example.speedtest.ui.theme.AccentBlue
import com.example.speedtest.ui.theme.AccentCyan
import com.example.speedtest.ui.theme.AccentPurple
import com.example.speedtest.ui.theme.SpeedtestTheme
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

private const val ARC_START = 135f
private const val ARC_SWEEP = 270f
private const val GAUGE_MAX_MBPS = 1000.0

@Composable
fun SpeedTestScreen(viewModel: SpeedTestViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SpeedTestContent(state = state, onStart = viewModel::start, onCancel = viewModel::cancel)
}

@Composable
private fun SpeedTestContent(
    state: UiState,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(colors.background, colors.surfaceVariant.copy(alpha = 0.6f))
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Header(state)

            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.06f),
                contentAlignment = Alignment.Center
            ) {
                SpeedGauge(state, Modifier.fillMaxSize())
            }
            Spacer(Modifier.weight(1f))

            Metrics(state)

            Spacer(Modifier.height(18.dp))

            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = colors.primary,
                trackColor = colors.surfaceVariant,
                drawStopIndicator = {}
            )

            Spacer(Modifier.height(18.dp))

            if (state.error != null) {
                Text(
                    text = state.error,
                    color = colors.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            Button(
                onClick = { if (state.isRunning) onCancel() else onStart() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(27.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isRunning) colors.surfaceVariant else colors.primary,
                    contentColor = if (state.isRunning) colors.onSurfaceVariant else colors.onPrimary
                )
            ) {
                Text(
                    text = when {
                        state.isRunning -> "停止"
                        state.phase == Phase.DONE -> "重新测试"
                        else -> "开始测速"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun Header(state: UiState) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "SPEED TEST",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                color = colors.onBackground
            )
            Text(
                text = state.server,
                fontSize = 12.sp,
                color = colors.onSurfaceVariant
            )
        }
        Surface(
            shape = CircleShape,
            color = colors.surfaceVariant.copy(alpha = 0.8f)
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(AccentCyan, CircleShape)
                )
                Text(
                    text = state.connection,
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SpeedGauge(state: UiState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val target = gaugeFraction(state.speedMbps)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 350, easing = LinearOutSlowInEasing),
        label = "gauge"
    )

    val trackColor = colors.surfaceVariant
    val needleColor = colors.primary

    val valueText = when (state.phase) {
        Phase.IDLE -> "--"
        Phase.PING -> formatMs(state.pingMs)
        else -> formatSpeed(state.speedMbps)
    }
    val unit = if (state.phase == Phase.PING) "ms" else "Mbps"

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 16.dp.toPx()
            val radius = min(size.width, size.height) / 2f - stroke
            if (radius <= 0f) return@Canvas
            val center = Offset(size.width / 2f, size.height / 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            drawArc(
                color = trackColor,
                startAngle = ARC_START,
                sweepAngle = ARC_SWEEP,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            if (animated > 0.002f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(AccentCyan, AccentBlue, AccentPurple, AccentCyan),
                        center = center
                    ),
                    startAngle = ARC_START,
                    sweepAngle = ARC_SWEEP * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }

            // 刻度
            val tickOuter = radius - stroke - 6.dp.toPx()
            for (i in 0..10) {
                val angle = Math.toRadians((ARC_START + ARC_SWEEP / 10f * i).toDouble())
                val long = i % 5 == 0
                val tickInner = tickOuter - if (long) 14.dp.toPx() else 8.dp.toPx()
                drawLine(
                    color = trackColor,
                    start = Offset(
                        center.x + cos(angle).toFloat() * tickInner,
                        center.y + sin(angle).toFloat() * tickInner
                    ),
                    end = Offset(
                        center.x + cos(angle).toFloat() * tickOuter,
                        center.y + sin(angle).toFloat() * tickOuter
                    ),
                    strokeWidth = if (long) 3.dp.toPx() else 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // 弧线端点光点
            val endAngle = Math.toRadians((ARC_START + ARC_SWEEP * animated).toDouble())
            val dotRadius = radius - stroke / 2f
            drawCircle(
                color = needleColor.copy(alpha = 0.22f),
                radius = stroke * 1.15f,
                center = Offset(
                    center.x + cos(endAngle).toFloat() * dotRadius,
                    center.y + sin(endAngle).toFloat() * dotRadius
                )
            )
            drawCircle(
                color = needleColor,
                radius = stroke * 0.5f,
                center = Offset(
                    center.x + cos(endAngle).toFloat() * dotRadius,
                    center.y + sin(endAngle).toFloat() * dotRadius
                )
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = state.phaseLabel,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                color = colors.onSurfaceVariant
            )
            Text(
                text = valueText,
                fontSize = 58.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onBackground
            )
            Text(
                text = unit,
                fontSize = 14.sp,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Metrics(state: UiState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("延迟", formatMs(state.pingMs), "ms", Modifier.weight(1f))
            MetricCard("抖动", formatMs(state.jitterMs), "ms", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("下载", formatSpeed(state.downloadMbps), "Mbps", Modifier.weight(1f))
            MetricCard("上传", formatSpeed(state.uploadMbps), "Mbps", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.85f))
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = unit,
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
    }
}

/** 对数刻度：低速段分辨率更高，0 → 0，1000Mbps → 1 */
private fun gaugeFraction(mbps: Double): Float {
    val v = mbps.coerceIn(0.0, GAUGE_MAX_MBPS)
    return (ln(1.0 + v) / ln(1.0 + GAUGE_MAX_MBPS)).toFloat()
}

private fun formatSpeed(value: Double?): String = when {
    value == null -> "--"
    value >= 100 -> String.format("%.0f", value)
    value >= 10 -> String.format("%.1f", value)
    else -> String.format("%.2f", value)
}

private fun formatMs(value: Double?): String =
    if (value == null) "--" else String.format("%.0f", value)

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun SpeedTestPreview() {
    SpeedtestTheme(darkTheme = true) {
        SpeedTestContent(
            state = UiState(
                phase = Phase.DONE,
                speedMbps = 128.4,
                downloadMbps = 128.4,
                uploadMbps = 42.7,
                pingMs = 18.0,
                jitterMs = 3.0,
                progress = 1f,
                connection = "Wi-Fi"
            ),
            onStart = {},
            onCancel = {}
        )
    }
}
