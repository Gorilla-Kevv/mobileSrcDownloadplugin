package com.clipdown.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clipdown.app.BuildConfig
import com.clipdown.app.ui.components.AppCard
import com.clipdown.app.ui.components.CardHeader
import com.clipdown.app.ui.components.KeyValueRow
import com.clipdown.app.ui.components.NoticeBar
import com.clipdown.app.ui.components.PrimaryButton
import com.clipdown.app.ui.components.SecondaryButton
import com.clipdown.app.ui.components.Tone
import com.clipdown.app.ui.components.VSpace
import com.clipdown.app.ui.theme.AppTheme
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

    AppCard {
        CardHeader(title = "关于与更新", subtitle = "GitHub Release 通道，可原地覆盖安装")
        VSpace(AppTheme.spacing.sm)
        KeyValueRow("当前版本", "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
        KeyValueRow("更新通道", BuildConfig.UPDATE_REPO)
        VSpace(AppTheme.spacing.md)
        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
            SecondaryButton(
                text = if (state is UpdateUiState.Checking) "检查中…" else "检查更新",
                modifier = Modifier.weight(1f),
                enabled = state !is UpdateUiState.Checking && !downloading,
                onClick = { scope.launch { UpdateCenter.check() } }
            )
            if (state is UpdateUiState.Available) {
                PrimaryButton(
                    text = if (downloading) "下载中…" else "下载并安装",
                    modifier = Modifier.weight(1f),
                    enabled = !downloading,
                    onClick = { scope.launch { UpdateCenter.downloadAndInstall() } }
                )
            }
        }

        when (val s = state) {
            is UpdateUiState.Idle, is UpdateUiState.Checking -> Unit
            is UpdateUiState.UpToDate -> {
                VSpace(AppTheme.spacing.md)
                NoticeBar("已是最新版本（${s.currentVersionName}）", Tone.Success)
            }

            is UpdateUiState.Failed -> {
                VSpace(AppTheme.spacing.md)
                NoticeBar("检查失败：${s.message}", Tone.Danger)
            }

            is UpdateUiState.Available -> {
                VSpace(AppTheme.spacing.md)
                val info = s.info
                NoticeBar(
                    buildString {
                        append("发现新版本 ${info.versionName}（${info.versionCode}）")
                        if (info.sizeText().isNotEmpty()) append(" · ${info.sizeText()}")
                        if (info.mandatory) append(" · 建议尽快更新")
                        if (info.notes.isNotBlank()) append("\n${info.notes}")
                    },
                    Tone.Brand
                )
            }
        }

        if (downloading) {
            VSpace(AppTheme.spacing.md)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            VSpace(AppTheme.spacing.xs)
            Text(
                "正在下载升级包 ${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (state is UpdateUiState.Available && !UpdateCenter.canInstallPackages()) {
            VSpace(AppTheme.spacing.xs)
            TextButton(onClick = { UpdateCenter.openInstallPermission() }) {
                Text("首次更新需允许「安装未知应用」→ 点此去开启", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
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
