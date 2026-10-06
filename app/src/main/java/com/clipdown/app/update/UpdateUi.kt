package com.clipdown.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clipdown.app.BuildConfig
import kotlinx.coroutines.launch

/**
 * 「关于与更新」区块（设置页）。
 *
 * 更新通道：GitHub Release 的 `releases/latest/download/update.json` 固定链接，
 * 不依赖 GitHub API，也不需要 token（发布仓库必须公开）。
 */
@Composable
fun UpdateSection() {
    val scope = rememberCoroutineScope()
    val state = UpdateCenter.state
    val downloading = UpdateCenter.downloading
    val progress = UpdateCenter.progress

    Card(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("关于与更新", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "当前版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "更新通道：${BuildConfig.UPDATE_REPO}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { scope.launch { UpdateCenter.check() } },
                    enabled = state !is UpdateUiState.Checking && !downloading
                ) {
                    Text(if (state is UpdateUiState.Checking) "检查中…" else "检查更新")
                }
                if (state is UpdateUiState.Available) {
                    Button(
                        onClick = { scope.launch { UpdateCenter.downloadAndInstall() } },
                        enabled = !downloading
                    ) {
                        Text(if (downloading) "下载中…" else "下载并安装")
                    }
                }
            }

            when (val s = state) {
                is UpdateUiState.Idle -> Unit
                is UpdateUiState.Checking -> Unit
                is UpdateUiState.UpToDate -> StatusText("已是最新版本 ✓")
                is UpdateUiState.Failed -> StatusText("检查失败：${s.message}")
                is UpdateUiState.Available -> {
                    Spacer(Modifier.height(8.dp))
                    val info = s.info
                    StatusText(
                        buildString {
                            append("发现新版本 ${info.versionName}（${info.versionCode}）")
                            if (info.sizeText().isNotEmpty()) append("，${info.sizeText()}")
                            if (info.mandatory) append(" · 建议尽快更新")
                        }
                    )
                    if (info.notes.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            info.notes,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            if (downloading) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "正在下载升级包 ${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium
                )
            } else if (state is UpdateUiState.Available && !UpdateCenter.canInstallPackages()) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { UpdateCenter.openInstallPermission() }) {
                    Text("首次更新需允许「安装未知应用」→ 点此去开启")
                }
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary
    )
}

/**
 * 启动自动检查发现新版本时的提示框（MainActivity 挂载）。
 * 用户点「稍后」后本次启动不再打扰。
 */
@Composable
fun UpdateLaunchDialog() {
    val state = UpdateCenter.state
    val info = (state as? UpdateUiState.Available)?.info ?: return
    if (UpdateCenter.launchPromptDismissed) return
    val scope = rememberCoroutineScope()
    val downloading = UpdateCenter.downloading

    AlertDialog(
        onDismissRequest = { UpdateCenter.launchPromptDismissed = true },
        title = { Text("发现新版本 ${info.versionName}") },
        text = {
            Column {
                Text(
                    "当前 ${BuildConfig.VERSION_NAME} → 最新 ${info.versionName}" +
                        if (info.sizeText().isNotEmpty()) "（${info.sizeText()}）" else ""
                )
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(info.notes, style = MaterialTheme.typography.bodySmall)
                }
                if (UpdateCenter.downloading) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { UpdateCenter.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = { scope.launch { UpdateCenter.downloadAndInstall() } }
            ) {
                Text(if (downloading) "下载中…" else "立即更新")
            }
        },
        dismissButton = {
            TextButton(onClick = { UpdateCenter.launchPromptDismissed = true }) { Text("稍后") }
        }
    )
}
