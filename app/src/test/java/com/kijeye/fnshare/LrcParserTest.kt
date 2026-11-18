package com.kijeye.fnshare

import com.kijeye.fnshare.data.lyrics.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LrcParserTest {

    @Test
    fun `basic timestamps parse`() {
        val doc = LrcParser.parse("[00:12.00]第一句\n[01:02.50]第二句")
        assertNotNull(doc)
        assertEquals(listOf(12000L to "第一句", 62500L to "第二句"), doc!!.lines.map { it.timeMs to it.text })
    }

    @Test
    fun `multi timestamp line expands`() {
        val doc = LrcParser.parse("[00:10.00][00:20.50]重复段")
        assertEquals(2, doc!!.lines.size)
        assertEquals(listOf(10000L, 20500L), doc.lines.map { it.timeMs })
        assertEquals(listOf("重复段", "重复段"), doc.lines.map { it.text })
    }

    @Test
    fun `offset shifts globally (positive = earlier)`() {
        val doc = LrcParser.parse("[offset:+500]\n[00:10.00]句子")
        assertEquals(9500L, doc!!.lines[0].timeMs)
        val doc2 = LrcParser.parse("[offset:-200]\n[00:10.00]句子")
        assertEquals(10200L, doc2!!.lines[0].timeMs)
    }

    @Test
    fun `word timestamps stripped to line level`() {
        val doc = LrcParser.parse("[00:12.00]<00:12.00>你<00:13.00>好")
        assertEquals("你好", doc!!.lines[0].text)
    }

    @Test
    fun `metadata tags stripped and pure meta file yields null`() {
        assertNull(LrcParser.parse("[ar:歌手]\n[ti:歌名]\n[by:制作]\n"))
        val doc = LrcParser.parse("[ti:歌名][00:05.00]正文")
        assertEquals("正文", doc!!.lines[0].text)
    }

    @Test
    fun `colon fraction separator accepted`() {
        val doc = LrcParser.parse("[00:12:30]句子")
        assertEquals(12300L, doc!!.lines[0].timeMs)
    }

    @Test
    fun `fraction digit count determines ms`() {
        val doc = LrcParser.parse("[00:01.5]a\n[00:02.50]b\n[00:03.500]c")
        assertEquals(listOf(1500L, 2500L, 3500L), doc!!.lines.map { it.timeMs })
    }

    @Test
    fun `empty and timeless lines filtered`() {
        val doc = LrcParser.parse("[00:01.00]\n[00:02.00]有词\n没有时间戳的行")
        assertEquals(listOf("有词"), doc!!.lines.map { it.text })
    }

    @Test
    fun `lines sorted by time`() {
        val doc = LrcParser.parse("[00:20.00]晚\n[00:10.00]早")
        assertEquals(listOf("早", "晚"), doc!!.lines.map { it.text })
    }

    @Test
    fun `indexOf finds current line and -1 before first`() {
        val doc = LrcParser.parse("[00:10.00]一\n[00:20.00]二\n[00:30.00]三")!!
        assertEquals(-1, doc.indexOf(9999))
        assertEquals(0, doc.indexOf(10_000))
        assertEquals(0, doc.indexOf(19_999))
        assertEquals(1, doc.indexOf(20_000))
        assertEquals(2, doc.indexOf(30_000))
        assertEquals(2, doc.indexOf(999_999)) // 最后一行显示到曲终
    }

    @Test
    fun `qq music credit header dropped`() {
        // 真实文件「如愿 - 王菲.lrc」的头部（GBK 解码后的原文，冒号两边带空格）。
        val doc = LrcParser.parse(
            "[00:00.00]作词 : 唐恬 作曲 : 钱雷 编曲 : 钱雷 制作人 : 钱雷 配唱编写 : 窦颖/林灵 如愿 - 王菲\n" +
                "[00:16.53]曲版权管理方：索尼音乐版权代理（北京）有限公司\n" +
                "[00:19.51]\n" +
                "[00:31.52]你是 遥遥的路",
        )
        assertEquals(listOf(31_520L to "你是 遥遥的路"), doc!!.lines.map { it.timeMs to it.text })
    }

    @Test
    fun `soft credit words need a colon`() {
        val doc = LrcParser.parse(
            "[00:05.00]为你演唱这首歌\n" +
                "[00:06.00]演唱：王菲\n" +
                "[00:07.00]词：米果\n" +
                "[00:08.00]曲：钱雷",
        )
        // 只有第一句是唱词：演唱/词/曲不带冒号时是普通唱词，带冒号才算署名标签。
        assertEquals(listOf("为你演唱这首歌"), doc!!.lines.map { it.text })
    }

    @Test
    fun `hard credit words drop even without colon`() {
        val doc = LrcParser.parse(
            "[00:05.00]出品 某某唱片\n" +
                "[00:06.00]发行 某某音乐\n" +
                "[00:07.00]原唱 王菲\n" +
                "[00:08.00]出品人：某某\n" +
                "[00:09.00]真句子",
        )
        // 这三个词只在署名区出现，不带冒号也是署名；`出品` 已覆盖 `出品人`。
        assertEquals(listOf("真句子"), doc!!.lines.map { it.text })
    }

    @Test
    fun `instrument and engineer credit block dropped`() {
        // 真实文件「追梦赤子心 - gala.lrc」的署名区：乐手和录音名单是无穷枚举的，
        // 靠的是「短标签 + 冒号」的形状规则，不是词表。
        val doc = LrcParser.parse(
            "[00:00.00]作词 : GALA\n" +
                "[00:04.00]吉他 : 赵亮\n" +
                "[00:05.00]贝斯 : 石亮\n" +
                "[00:06.00]鼓 : 小达\n" +
                "[00:07.00]钢琴 : 甘虎\n" +
                "[00:08.00]和声 : 苏朵/赵亮/星默\n" +
                "[00:09.00]弦乐 : 中国国家交响乐团\n" +
                "[00:11.00]录音工程师 : 郝宇/石亮@Jet Studio\n" +
                "[00:12.00]录音助理 : 时磊/路路@Jet Studio\n" +
                "[00:14.00]充满鲜花的世界到底在哪里",
        )
        assertEquals(listOf("充满鲜花的世界到底在哪里"), doc!!.lines.map { it.text })
    }

    @Test
    fun `credit label rule only fires at line start`() {
        // 冒号出现在行中间不算署名：只有「行首短标签」才是署名形状。
        val doc = LrcParser.parse("[00:01.00]这一条路要自己走 妈妈说：别回头")
        assertEquals(1, doc!!.lines.size)
    }

    @Test
    fun `title line with version dropped`() {
        val doc = LrcParser.parse(
            "[00:00.00]起风了 (原版) - 买辣椒也用券\n" +
                "[00:28.88]这一路上走走停停",
        )
        assertEquals(listOf("这一路上走走停停"), doc!!.lines.map { it.text })
    }

    @Test
    fun `instrumental placeholder yields null`() {
        assertNull(LrcParser.parse("[00:00.00]纯音乐，请欣赏"))
        assertNull(LrcParser.parse("[00:00.00]此歌曲为没有填词的纯音乐，请您欣赏"))
    }

    @Test
    fun `blank and zero width lines skipped`() {
        val doc = LrcParser.parse("[00:01.00]　\n[00:02.00]\u200B\n[00:03.00]\uFEFF\n[00:04.00]真句子")
        // 全角空格、零宽空格、BOM 都不能占掉一个句子位（否则 UI 上会出现「空跳」）。
        assertEquals(listOf("真句子"), doc!!.lines.map { it.text })
    }

    @Test
    fun `any meta tag name stripped`() {
        val doc = LrcParser.parse("[sign:abc][00:03.00]你好\n[total:5]\n[00:04.00]再见")
        assertEquals(listOf("你好", "再见"), doc!!.lines.map { it.text })
    }

    @Test
    fun `bom at file start does not leak into first line`() {
        val doc = LrcParser.parse("\uFEFF[00:01.00]第一句")
        assertEquals("第一句", doc!!.lines[0].text)
    }

    @Test
    fun `normal lyrics survive credit filter`() {
        val doc = LrcParser.parse(
            "[00:01.00]山野大雾里的灯\n" +
                "[00:02.00]我是孩童啊 走在你的眼眸\n" +
                "[00:03.00]你是 明月清风\n" +
                "[00:04.00]而我将 爱你所爱的人间",
        )
        assertEquals(4, doc!!.lines.size)
    }

    @Test
    fun `gbk bytes decode via fallback`() {
        val gbk = "测试歌词".toByteArray(charset("GBK"))
        assertEquals("测试歌词", LrcParser.decode(gbk))
        assertEquals("测试歌词", LrcParser.decode("测试歌词".toByteArray(Charsets.UTF_8)))
        // GBK 是宽松编码、String 构造器不抛异常，所以兜底路不会返回 null——
        // 这正是我们要的行为：真歌词（UTF-8 或 GBK）总能解出文本，宁出替换符也不丢词。
        assertNotNull(LrcParser.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x81.toByte(), 0x82.toByte())))
    }
}
