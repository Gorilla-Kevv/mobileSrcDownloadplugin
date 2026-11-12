package com.clipdown.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.clipdown.app.ui.theme.AppTheme
import com.clipdown.app.ui.theme.Brand600
import com.clipdown.app.ui.theme.BrandPrimary
import com.clipdown.app.ui.theme.DarkBg
import com.clipdown.app.ui.theme.DarkOutline
import com.clipdown.app.ui.theme.DarkSurface
import com.clipdown.app.ui.theme.DarkTextPrimary
import com.clipdown.app.ui.theme.DarkTextSecondary
import com.clipdown.app.ui.theme.SuccessFg

/**
 * 电脑端 Edge / Chrome 插件配对指引弹窗。
 * 指引用户如何在电脑浏览器上加载 ClipDown 插件，并配置局域网直连或扫码导入。
 *
 * 视觉：全部取 `ui/theme` 语义令牌（HANDOFF §UI 硬规则：页面禁止裸 `Color(0xFF…)`
 * 与随手 dp 值），圆角统一走 `AppTheme.radius`。
 */
@Composable
fun SyncPairingDialog(
    ip: String?,
    port: Int,
    pin: String,
    isRunning: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val connectUrl = "http://${ip ?: "127.0.0.1"}:$port"
    val cardShape = RoundedCornerShape(AppTheme.radius.card)
    val controlShape = RoundedCornerShape(AppTheme.radius.control)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(cardShape)
                .border(1.dp, DarkOutline, cardShape),
            shape = cardShape,
            color = DarkBg,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(AppTheme.spacing.lg)
                    .verticalScroll(rememberScrollState())
            ) {
                // 顶部标题
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "🖥️ 电脑端 Edge / Chrome 插件配对",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = DarkTextPrimary
                        )
                        Spacer(Modifier.height(AppTheme.spacing.xs))
                        Text(
                            text = "共享网页登录态 · 解决 Cookie 提取与下载记录同步",
                            style = MaterialTheme.typography.labelSmall,
                            color = BrandPrimary
                        )
                    }
                    StatusPill(
                        text = if (isRunning) "服务运行中" else "服务未启动",
                        tone = if (isRunning) Tone.Success else Tone.Danger
                    )
                }

                VSpace(AppTheme.spacing.md)
                Hairline()
                VSpace(AppTheme.spacing.md)

                // 当前配对凭据卡片（地址 + PIN，供手动输入或旧版电脑扫码兜底）
                val pairPayload = "clipdown://pair?url=$connectUrl&pin=$pin"

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(controlShape)
                        .background(DarkSurface)
                        .border(1.dp, DarkOutline, controlShape)
                        .padding(AppTheme.spacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "局域网服务地址",
                                style = MaterialTheme.typography.labelSmall,
                                color = DarkTextSecondary
                            )
                            Spacer(Modifier.height(AppTheme.spacing.xs))
                            Text(
                                text = connectUrl,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Brand600,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "配对 PIN 码",
                                style = MaterialTheme.typography.labelSmall,
                                color = DarkTextSecondary
                            )
                            Spacer(Modifier.height(AppTheme.spacing.xs))
                            Text(
                                text = pin,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                color = SuccessFg,
                                style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 1.sp)
                            )
                        }
                    }
                }

                VSpace(AppTheme.spacing.md)

                // 推荐方式：手机扫电脑二维码（阶段 83 修正方向）
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(context, com.clipdown.app.sync.PairScanActivity::class.java)
                        )
                    },
                    shape = controlShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Brand600,
                        contentColor = DarkTextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Text("📷 扫电脑二维码连接（推荐）", style = MaterialTheme.typography.labelLarge)
                }
                VSpace(AppTheme.spacing.xs)
                Text(
                    text = "在电脑扩展「配对设置」点击「显示二维码」，用手机扫它即可自动配对。",
                    style = MaterialTheme.typography.labelSmall,
                    color = DarkTextSecondary
                )

                VSpace(AppTheme.spacing.md)

                // 3步极简流程
                Text(
                    text = "使用三步法：",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkTextPrimary
                )

                VSpace(AppTheme.spacing.xs)

                StepItem(
                    num = "1",
                    title = "电脑加载扩展并显示二维码",
                    desc = "打开 Edge/Chrome 扩展管理页（勾选「开发者模式」）→「加载解压缩的扩展」→ 选择 extension 文件夹；在扩展「配对设置」点击「显示二维码」。"
                )
                StepItem(
                    num = "2",
                    title = "手机扫电脑二维码",
                    desc = "点击上方「扫电脑二维码连接」，对准电脑屏幕上的二维码；手机会自动回连电脑完成配对，无需手输 IP 与 PIN。"
                )
                StepItem(
                    num = "3",
                    title = "一键灌入 Cookie 与双向同步",
                    desc = "在电脑正常登录 Instagram / 小红书后，在插件内点击「一键同步所有 Cookie 到手机」，瞬时搞定登录态，彻底告别手机端风控验证码！"
                )

                VSpace(AppTheme.spacing.lg)

                // 操作按钮组
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = controlShape,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = DarkTextSecondary
                        ),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("关闭", style = MaterialTheme.typography.labelMedium)
                    }

                    Spacer(Modifier.width(AppTheme.spacing.sm))

                    Button(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("ClipDown Sync Config", pairPayload))
                            Toast.makeText(context, "已复制一键配对口令！在电脑扩展点击「粘贴口令」即可秒连", Toast.LENGTH_SHORT).show()
                        },
                        shape = controlShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Brand600,
                            contentColor = DarkTextPrimary
                        ),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("📋 复制一键配对口令", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepItem(num: String, title: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppTheme.spacing.xs),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = RoundedCornerShape(AppTheme.radius.control),
            color = DarkOutline,
            modifier = Modifier.size(20.dp)
        ) {
            Text(
                text = num,
                color = DarkTextPrimary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 1.dp, start = 6.dp)
            )
        }
        Spacer(Modifier.width(AppTheme.spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = DarkTextPrimary
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = DarkTextSecondary
            )
        }
    }
}
