package com.clipdown.app.sync

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.clipdown.app.ui.theme.AppTheme
import com.clipdown.app.ui.theme.ClipDownTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「手机扫电脑二维码」配对入口（阶段 83 修正方向）。
 *
 * 电脑扩展负责：拿到本机地址 + PIN → 生成二维码并显示。
 * 手机负责：扫码 → 确认这是"本机"的配对凭据 → 确保同步服务在跑 → 落库配对态。
 *
 * 注意：扩展是 MV3 无法监听端口，所以**手机不回连电脑**；
 * 二维码里装的就是手机自己的 `http://IP:PORT` + PIN，扫到即确认配对。
 */
class PairScanActivity : ComponentActivity() {

    private var status by mutableStateOf("正在打开扫码器…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClipDownTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(AppTheme.spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = AppTheme.spacing.md)
                    )
                }
            }
        }
        handleIntentOrScan()
    }

    private fun handleIntentOrScan() {
        // 路径 A：外部扫码器（微信/相机）扫到 clipdown://pair，经 MainActivity 转交
        val payload = PairPayload.parse(intent?.getStringExtra(EXTRA_PAYLOAD))
        if (payload != null) {
            status = "已识别配对码，正在应用…"
            applyPairing(payload)
            return
        }

        // 路径 B：应用内发起扫码，把 clipdown://pair 交给系统扫码器
        val scanIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("clipdown://pair"))
        if (packageManager.resolveActivity(scanIntent, 0) == null) {
            Toast.makeText(
                this,
                "未找到可识别 ClipDown 二维码的扫码器。\n可改用：电脑点「显示二维码」后用手机相机扫。",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        runCatching { startActivityForResult(scanIntent, REQ_SCAN) }
            .onFailure {
                Toast.makeText(this, "无法启动扫码器：${it.message}", Toast.LENGTH_LONG).show()
                finish()
            }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SCAN) return
        if (resultCode != Activity.RESULT_OK) {
            finish()
            return
        }
        val scanned = data?.dataString ?: data?.getStringExtra(Intent.EXTRA_TEXT)
        val payload = PairPayload.parse(scanned)
        if (payload == null) {
            Toast.makeText(this, "二维码不是有效的 ClipDown 配对码", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        status = "已识别配对码，正在应用…"
        applyPairing(payload)
    }

    /**
     * 应用配对：确保同步服务运行，并把电脑已记录的地址/PIN 对齐到本机。
     *
     * 若二维码里的地址与本机当前 IP 不一致（例如电脑记的是旧 IP），
     * 以**本机实际 IP** 为准并提示，避免配对后电脑连不上。
     */
    private fun applyPairing(payload: PairPayloadData) {
        lifecycleScope.launch {
            SyncManager.init(this@PairScanActivity)
            if (!SyncManager.isServerRunning.value) {
                SyncManager.startServer()
            }
            SyncManager.refreshIp()

            val realIp = SyncManager.localIp.value ?: "127.0.0.1"
            val realPort = SyncManager.port.value
            val realPin = SyncManager.pairingPin.value
            val realUrl = "http://$realIp:$realPort"

            // 电脑记的地址与本机实际不一致 → 需要用户回电脑重扫/重填，
            // 否则配对"成功"了电脑还是连不上（这是最常见的失败原因）
            val needResync = payload.phoneUrl != realUrl
            val pinMismatch = payload.pin.isNotBlank() && payload.pin != realPin

            val deviceName = "${Build.BRAND.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
            val msg = buildString {
                append("✓ 配对成功！\n")
                append("本机：$deviceName\n")
                append("地址：$realUrl\n")
                append("PIN：$realPin")
                if (needResync || pinMismatch) {
                    append("\n\n⚠ 电脑记录的地址/PIN 与本机不一致，")
                    append("请在电脑扩展点「显示二维码」重新扫一次，或手动填入上面这组。")
                } else {
                    append("\n\n电脑扩展现在可以同步 Cookie 与推送下载了。")
                }
            }
            Toast.makeText(this@PairScanActivity, msg, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object {
        private const val REQ_SCAN = 4001

        /** MainActivity 转交外部扫码器结果时携带的原始 clipdown://pair 文本 */
        const val EXTRA_PAYLOAD = "extra_pair_payload"
    }
}
