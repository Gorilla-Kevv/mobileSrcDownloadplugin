package com.clipdown.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.clipdown.app.ui.theme.AppTheme

/**
 * 电脑端 Edge / Chrome 插件配对指引弹窗。
 * 指引用户如何在电脑浏览器上加载 ClipDown 插件，并配置局域网直连或扫码导入。
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .border(1.dp, Color(0xFF2E2048), RectangleShape),
            shape = RectangleShape,
            color = Color(0xFF09060E),
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
                            color = Color(0xFFF5F3FF)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "共享网页登录态 · 解决 Cookie 提取与下载记录同步",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFA855F7)
                        )
                    }
                    StatusPill(
                        text = if (isRunning) "服务运行中" else "服务未启动",
                        tone = if (isRunning) Tone.Success else Tone.Danger,
                        shape = RectangleShape
                    )
                }

                VSpace(AppTheme.spacing.md)
                Hairline()
                VSpace(AppTheme.spacing.md)

                // 当前配对凭据卡
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF130E1F))
                        .border(1.dp, Color(0xFF2E2048), RectangleShape)
                        .padding(AppTheme.spacing.md)
                ) {
                    Text(
                        text = "手机局域网服务地址",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = connectUrl,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF8B5CF6),
                        fontSize = 15.sp
                    )

                    VSpace(AppTheme.spacing.sm)

                    Text(
                        text = "6 位安全配对码 (PIN)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = pin,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF10B981),
                        fontSize = 20.sp,
                        letterSpacing = 2.sp
                    )
                }

                VSpace(AppTheme.spacing.md)

                // 3步极简流程
                Text(
                    text = "使用三步法：",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF5F3FF)
                )

                VSpace(AppTheme.spacing.xs)

                StepItem(
                    num = "1",
                    title = "电脑加载扩展",
                    desc = "打开 Edge/Chrome 扩展管理页（勾选「开发者模式」）→ 点击「加载解压缩的扩展」→ 选择项目工程内的 extension 文件夹。"
                )
                StepItem(
                    num = "2",
                    title = "填入服务地址与 PIN",
                    desc = "点击浏览器右上角 ClipDown 图标，在「配对设置」填入上方服务地址与 PIN 码，点击测试连接。"
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
                        shape = RectangleShape,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFF94A3B8)
                        ),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("关闭", style = MaterialTheme.typography.labelMedium)
                    }

                    Spacer(Modifier.width(AppTheme.spacing.sm))

                    Button(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("ClipDown Sync Config", "$connectUrl PIN:$pin"))
                            Toast.makeText(context, "已复制配对地址与 PIN 码到剪贴板", Toast.LENGTH_SHORT).show()
                        },
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF8B5CF6),
                            contentColor = Color(0xFFF5F3FF)
                        ),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("复制配对参数", style = MaterialTheme.typography.labelMedium)
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
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = RectangleShape,
            color = Color(0xFF2E2048),
            modifier = Modifier.width(20.dp).height(20.dp)
        ) {
            Text(
                text = num,
                color = Color(0xFFF5F3FF),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 1.dp, start = 6.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFF5F3FF)
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8),
                lineHeight = 16.sp
            )
        }
    }
}
