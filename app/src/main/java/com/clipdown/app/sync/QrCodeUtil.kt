package com.clipdown.app.sync

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * 纯 Kotlin 实现的自包含轻量级 QR Code 二维码生成引擎。
 * 零外部重型依赖，严守 2MB APK 体积红线。
 * 支持 ISO/IEC 18004 规范 Byte 模式与 Reed-Solomon 纠错，专门用于跨端局域网配对协议编码。
 */
object QrCodeUtil {

    /**
     * 生成配对二维码 Bitmap。
     * @param content 配对协议文本，如 "clipdown://pair?url=http://10.252.146.198:8765&pin=839201"
     * @param sizePx 输出位图的边长（像素）
     * @param fgColor 前景色（通常为纯黑或深夜紫黑）
     * @param bgColor 背景色（通常为纯白，保证最大对比度）
     */
    fun createQrBitmap(
        content: String,
        sizePx: Int = 512,
        fgColor: Int = Color.BLACK,
        bgColor: Int = Color.WHITE
    ): Bitmap {
        val matrix = encodeText(content)
        val moduleCount = matrix.size
        val scale = (sizePx / moduleCount).coerceAtLeast(1)
        val realSize = moduleCount * scale

        val bitmap = Bitmap.createBitmap(realSize, realSize, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(realSize * realSize)

        for (y in 0 until realSize) {
            val my = y / scale
            for (x in 0 until realSize) {
                val mx = x / scale
                val isDark = if (my < moduleCount && mx < moduleCount) matrix[my][mx] else false
                pixels[y * realSize + x] = if (isDark) fgColor else bgColor
            }
        }

        bitmap.setPixels(pixels, 0, realSize, 0, 0, realSize, realSize)
        return bitmap
    }

    // ── 纯算法：QR Code 矩阵构建 ──────────────────────────────────────────────
    private fun encodeText(text: String): Array<BooleanArray> {
        val dataBytes = text.toByteArray(StandardCharsets.UTF_8)
        val version = chooseVersion(dataBytes.size)
        val size = 17 + 4 * version

        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        // 1. 放置三个角的探测图形 (Finder Patterns, 7x7)
        drawFinderPattern(modules, isFunction, 0, 0)
        drawFinderPattern(modules, isFunction, size - 7, 0)
        drawFinderPattern(modules, isFunction, 0, size - 7)

        // 2. 放置分隔符 (Separators)
        drawSeparators(modules, isFunction, size)

        // 3. 放置校正图形 (Alignment Patterns, 如果 version >= 2)
        drawAlignmentPatterns(modules, isFunction, version)

        // 4. 放置时钟线 (Timing Patterns)
        for (i in 8 until size - 8) {
            val dark = (i % 2 == 0)
            if (!isFunction[6][i]) {
                modules[6][i] = dark
                isFunction[6][i] = true
            }
            if (!isFunction[i][6]) {
                modules[i][6] = dark
                isFunction[i][6] = true
            }
        }

        // 5. 放置固定暗块 (Dark Module)
        modules[4 * version + 9][8] = true
        isFunction[4 * version + 9][8] = true

        // 6. 预留格式信息区
        for (i in 0..8) {
            isFunction[8][i] = true
            isFunction[i][8] = true
            isFunction[8][size - 1 - i] = true
            isFunction[size - 1 - i][8] = true
        }

        // 7. 编码数据与 Reed-Solomon 纠错码
        val codewords = generateCodewords(dataBytes, version)

        // 8. 数据填充入矩阵 (从右到左，上下蛇形穿越)
        var bitIndex = 0
        val totalBits = codewords.size * 8
        var right = size - 1
        while (right > 0) {
            if (right == 6) right-- // 跳过垂直时钟线
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x]) {
                        var bit = false
                        if (bitIndex < totalBits) {
                            val byteVal = codewords[bitIndex ushr 3].toInt() and 0xFF
                            val bitPos = 7 - (bitIndex and 7)
                            bit = ((byteVal ushr bitPos) and 1) != 0
                            bitIndex++
                        }
                        // 采用标准掩模 Mask 0: (x + y) % 2 == 0
                        val mask = ((x + y) % 2 == 0)
                        modules[y][x] = bit xor mask
                    }
                }
            }
            right -= 2
        }

        // 9. 写入格式信息 (Mask 0, Error Correction Level L)
        writeFormatInfo(modules, size)

        // 10. 增加 4 模块标准静区 (Quiet Zone)
        return addQuietZone(modules, 4)
    }

    private fun chooseVersion(dataLen: Int): Int {
        // 等级 L 纠错各版本最大字节容量
        val capacities = intArrayOf(17, 32, 53, 78, 106, 134, 154, 192, 230, 271)
        for (v in 1..capacities.size) {
            // Byte 模式开销：4 bit 模式指示器 + 8 bit 长度 = 12 bit (1.5 byte)
            if (dataLen + 2 <= capacities[v - 1]) return v
        }
        return 6
    }

    private fun drawFinderPattern(m: Array<BooleanArray>, fn: Array<BooleanArray>, ox: Int, oy: Int) {
        for (y in 0..6) {
            for (x in 0..6) {
                val isBorder = (x == 0 || x == 6 || y == 0 || y == 6)
                val isCenter = (x in 2..4 && y in 2..4)
                m[oy + y][ox + x] = isBorder || isCenter
                fn[oy + y][ox + x] = true
            }
        }
    }

    private fun drawSeparators(m: Array<BooleanArray>, fn: Array<BooleanArray>, size: Int) {
        for (i in 0..7) {
            fn[7][i] = true; fn[i][7] = true
            fn[7][size - 1 - i] = true; fn[i][size - 8] = true
            fn[size - 8][i] = true; fn[size - 1 - i][7] = true
        }
    }

    private fun drawAlignmentPatterns(m: Array<BooleanArray>, fn: Array<BooleanArray>, version: Int) {
        if (version < 2) return
        val pos = getAlignmentPositions(version)
        for (y in pos) {
            for (x in pos) {
                if (fn[y][x]) continue
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val isBorder = (dx == -2 || dx == 2 || dy == -2 || dy == 2)
                        val isCenter = (dx == 0 && dy == 0)
                        m[y + dy][x + dx] = isBorder || isCenter
                        fn[y + dy][x + dx] = true
                    }
                }
            }
        }
    }

    private fun getAlignmentPositions(version: Int): IntArray {
        return when (version) {
            2 -> intArrayOf(6, 18)
            3 -> intArrayOf(6, 22)
            4 -> intArrayOf(6, 26)
            5 -> intArrayOf(6, 30)
            6 -> intArrayOf(6, 34)
            else -> intArrayOf(6, 4 * version + 10)
        }
    }

    private fun generateCodewords(data: ByteArray, version: Int): ByteArray {
        val totalCodewords = getTotalCodewords(version)
        val dataCodewords = getDataCodewords(version)
        val ecCodewords = totalCodewords - dataCodewords

        // 组装模式与长度指示
        val bitOut = BitBuffer()
        bitOut.append(0b0100, 4) // 8-bit byte mode
        bitOut.append(data.size, 8) // length (version 1-9 为 8 bit)
        for (b in data) {
            bitOut.append(b.toInt() and 0xFF, 8)
        }

        // 终止符与对齐字节
        val padZeros = (dataCodewords * 8 - bitOut.length).coerceAtMost(4)
        if (padZeros > 0) bitOut.append(0, padZeros)
        while (bitOut.length % 8 != 0) bitOut.append(0, 1)

        val dataBytes = bitOut.toByteArray().copyOf(dataCodewords)
        // 填充交替垫字 (0xEC, 0x11)
        var padByte = 0xEC
        for (i in bitOut.length / 8 until dataCodewords) {
            dataBytes[i] = padByte.toByte()
            padByte = if (padByte == 0xEC) 0x11 else 0xEC
        }

        // 生成 Reed-Solomon 纠错码
        val ecBytes = calculateReedSolomon(dataBytes, ecCodewords)
        return dataBytes + ecBytes
    }

    private fun getTotalCodewords(version: Int): Int {
        return when (version) {
            1 -> 26; 2 -> 44; 3 -> 70; 4 -> 100; 5 -> 134; 6 -> 172; else -> 172
        }
    }

    private fun getDataCodewords(version: Int): Int {
        // EC Level L 的数据容量
        return when (version) {
            1 -> 19; 2 -> 34; 3 -> 55; 4 -> 80; 5 -> 108; 6 -> 136; else -> 136
        }
    }

    // Reed-Solomon 纠错算法
    private fun calculateReedSolomon(data: ByteArray, ecCount: Int): ByteArray {
        val gen = rsGeneratorPoly(ecCount)
        val res = IntArray(ecCount)

        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor res[0]
            System.arraycopy(res, 1, res, 0, ecCount - 1)
            res[ecCount - 1] = 0
            for (j in 0 until ecCount) {
                res[j] = res[j] xor gfMultiply(gen[j], factor)
            }
        }

        val ecBytes = ByteArray(ecCount)
        for (i in 0 until ecCount) {
            ecBytes[i] = res[i].toByte()
        }
        return ecBytes
    }

    private fun rsGeneratorPoly(ecCount: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until ecCount) {
            val root = gfExp(i)
            val next = IntArray(g.size + 1)
            for (j in g.indices) {
                next[j] = next[j] xor gfMultiply(g[j], root)
                next[j + 1] = next[j + 1] xor g[j]
            }
            g = next
        }
        return g.copyOfRange(0, ecCount)
    }

    // Galois Field GF(2^8) 表运算
    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            GF_EXP[i] = x
            GF_EXP[i + 255] = x
            GF_LOG[x] = i
            x = (x shl 1) xor if (x >= 128) 0x11D else 0
        }
    }

    private fun gfMultiply(x: Int, y: Int): Int {
        if (x == 0 || y == 0) return 0
        return GF_EXP[GF_LOG[x] + GF_LOG[y]]
    }

    private fun gfExp(n: Int): Int = GF_EXP[n % 255]

    private fun writeFormatInfo(m: Array<BooleanArray>, size: Int) {
        // EC Level L (01) + Mask 0 (000) -> 01000 = 8 -> 经过 BCH 编码后为 0x77C4
        val formatBits = 0b111011111000100 // 0x77C4 (已异或掩码 0x5412)
        for (i in 0..14) {
            val bit = ((formatBits ushr (14 - i)) and 1) != 0
            // 左上角周围
            if (i <= 5) m[8][i] = bit
            else if (i == 6) m[8][7] = bit
            else if (i == 7) m[8][8] = bit
            else if (i == 8) m[7][8] = bit
            else m[14 - i][8] = bit

            // 右上角与左下角镜像
            if (i < 8) {
                m[size - 1 - i][8] = bit
            } else {
                m[8][size - 15 + i] = bit
            }
        }
    }

    private fun addQuietZone(m: Array<BooleanArray>, qz: Int): Array<BooleanArray> {
        val oldSize = m.size
        val newSize = oldSize + 2 * qz
        val res = Array(newSize) { BooleanArray(newSize) }
        for (y in 0 until oldSize) {
            System.arraycopy(m[y], 0, res[y + qz], qz, oldSize)
        }
        return res
    }

    private class BitBuffer {
        val out = ByteArrayOutputStream()
        var curByte = 0
        var bitOffset = 0
        var length = 0

        fun append(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                val b = (value ushr i) and 1
                curByte = (curByte shl 1) or b
                bitOffset++
                length++
                if (bitOffset == 8) {
                    out.write(curByte)
                    curByte = 0
                    bitOffset = 0
                }
            }
        }

        fun toByteArray(): ByteArray {
            val buf = out.toByteArray()
            if (bitOffset == 0) return buf
            val result = ByteArray(buf.size + 1)
            System.arraycopy(buf, 0, result, 0, buf.size)
            result[buf.size] = (curByte shl (8 - bitOffset)).toByte()
            return result
        }
    }
}
