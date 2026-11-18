package com.kijeye.fnshare.data.lyrics

/**
 * LRC 歌词解析。纯 Kotlin，不依赖 Android——给它一段文本，还一份带时间轴的行列表。
 *
 * 覆盖的方言（都是实际流通的歌词文件里见得到的写法）：
 * - 行首多个时间戳（`[00:12.00][01:30.50]同一段词`）各自展开成独立一行；
 * - `[offset:+500]` 全局偏移：正数整体提前，负数整体延后（LRC 惯例，符号方向按标准来）；
 * - 增强型逐字时间戳（`<00:12.00>`）直接剥掉，降级为行级同步——逐字高亮是手机 KTV 的事，
 *   电视三米外的三行浮层用不上，为此引入分词复杂度不值；
 * - `[ar:][ti:][by:][al:]` 等元数据行剥掉（任何 `[字母:内容]` 形式都认，不再枚举标签名）；
 * - **署名区整行丢弃**：带时间戳的「作词：… 作曲：… 编曲：…」不是唱出来的词，
 *   见 [isCreditLine]——不能只靠 UI 截断，否则前奏那几十秒屏幕上就是一堆制作名单；
 * - 分隔符秒/厘秒之间 `.` 和 `:` 都认（`[00:12:30]` 也是合法写法）；
 * - 厘秒位数 1~3 位都收，按位数折算毫秒（`12.5` = 500ms，`12.50` = 500ms，`12.500` = 500ms）。
 *
 * 编码：`[decode]` 先按 UTF-8 严格解码，遇到非法字节序列（典型：GBK 编码的中文歌词）
 * 回落 GBK。宽松 UTF-8 解码会把乱码悄悄替换成 U+FFFD，用户看到的就是满屏问号——
 * 必须严格模式才能可靠探测。
 */

/**
 * 定义已移到 `domain/model/LyricsDoc.kt`（结构化重构第 1 批，逐字搬移）。
 * 保留 typealias 是为了让 `LrcParser.parse()` 里的构造调用和 19 个单元测试的
 * `import com.kijeye.fnshare.data.lyrics.LyricsDoc` 全部保持原样。
 */
typealias LrcLine = com.kijeye.fnshare.domain.model.LrcLine
typealias LyricsDoc = com.kijeye.fnshare.domain.model.LyricsDoc

object LrcParser {

    /** 行首时间戳：`[分:秒]` / `[分:秒.厘]` / `[分:秒:厘]`。 */
    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** 增强型逐字时间戳（词级），整段剥掉不参与解析。 */
    private val WORD_TIMESTAMP = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    /**
     * 非时间戳的元数据标签：任何 `[字母:内容]` 都剥。
     *
     * 早先枚举成 `(ar|ti|al|by|length|re|ve|tool|kana)`，结果 `[sign:]`、`[qq:]`、`[total:]`
     * 这些没被枚举到的标签在带时间戳的混排行里会当正文显示出来。时间戳以数字开头
     * （`[00:12.00]`），字母开头的不可能是时间戳，所以放开成通配是安全的。
     * `[offset:]` 在前面单独认领，不依赖这条。
     */
    private val META_TAG = Regex("""\[[A-Za-z][A-Za-z0-9_]{0,15}:[^\]]*]""")

    /**
     * 署名区的「硬词」：正常唱词里不会出现的制作名单用词，**文本包含即整行丢弃**。
     *
     * 收词标准是「唱不出来」——包括 `出品`、`发行`、`原唱` 这三个：它们只在署名区出现，
     * 而且实际流通的歌词文件里大量写成 `出品 某某唱片`（不带冒号），按软词规则会漏网。
     * 「版权」是给 `曲版权管理方：…` 这类只写公司名的行兜底（它前面没有「作词」「作曲」，
     * 光按标签匹配会漏网）。`出品` 已覆盖 `出品人`，不必再单列。
     */
    private val CREDIT_HARD = listOf(
        "作词", "作曲", "编曲", "制作人", "混音", "母带",
        "监制", "配唱", "出品", "发行", "原唱", "版权", "著作", "和声编写",
    )

    /**
     * 署名区的「软词」：本身是普通词，可能真的出现在唱词里（「为你演唱这首歌」），
     * 所以只在**紧跟冒号**（全角/半角、中间容许空格）时才算署名标签——
     * `词：米果`、`演唱 : 王菲`。真实歌词文件里署名标签都带冒号。
     */
    private val CREDIT_SOFT = Regex("""(演唱|词|曲)\s*[:：]""")

    /**
     * 「短标签 + 冒号」的行首形态：`吉他 : 赵亮`、`录音工程师 : 郝宇@Jet Studio`、
     * `和音：窦颖/林灵`、`Bass：李卓`。
     *
     * 乐手/录音名单是无穷枚举的（吉他、贝斯、鼓、钢琴、弦乐、小号、中提琴、录音助理…），
     * 按词表收永远漏，所以改成认**形状**：行首 1~8 个汉字/字母/数字，紧跟冒号。
     * 冒号左边只会是职务名，没有哪句唱词以「两三个字加冒号」开头——拿五份真实歌词
     * （如愿 / 父亲写的散文诗 / 知否知否 / 起风了 / 追梦赤子心）全量过了一遍，
     * 带冒号的行 100% 是署名，一句唱词都没误伤。
     */
    private val CREDIT_LABEL = Regex("""^[\u4e00-\u9fa5A-Za-z][\u4e00-\u9fa5A-Za-z0-9 ]{0,7}\s*[:：]""")

    /**
     * 标题行：`起风了 (原版) - 买辣椒也用券`。有些文件把「歌名 (版本) - 歌手」
     * 也挂上时间戳，不拦的话开唱前那几秒屏幕上飘的是歌名。
     * 只认「带括号的版本号 + 空格连字符 + 名字」这个很窄的形状，唱词里不会有。
     */
    private val CREDIT_TITLE = Regex("""^.+[(（][^)）]{0,20}[)）]\s*-\s*\S""")

    /** 纯音乐/占位提示，不是歌词。整首都是这种 → 解析结果为 null → 浮层整体不挂载。 */
    private val CREDIT_PLACEHOLDER = listOf("暂无歌词", "纯音乐", "没有填词", "此歌曲为")

    /** 英文署名（网易云等偶尔给英文版）。取完整短语，不用单词，防误伤英文唱词。 */
    private val CREDIT_EN = Regex(
        """(?i)(lyrics?\s*[:：]|lyrics by|composed by|arranged by|producer\s*[:：]|mixed by|mastered by)""",
    )

    /**
     * 字节 → 文本。UTF-8 严格解码失败（有非法字节序列）回落 GBK。
     * 两路都失败返回 null——宁可不显示，也不给用户一屏 U+FFFD。
     */
    fun decode(bytes: ByteArray): String? {
        val strictUtf8 = kotlin.text.Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching { strictUtf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { runCatching { String(bytes, charset("GBK")) }.getOrNull() }
    }

    /**
     * 文本 → 歌词文档。没有任何可显示的行（空文件、纯元数据）时返回 null。
     */
    fun parse(text: String): LyricsDoc? {
        var offsetMs = 0L
        val out = ArrayList<LrcLine>()

        // BOM 不是空白字符（Character.isWhitespace('\uFEFF') == false），不去掉的话会
        // 粘在第一行正文前面，变成一个看不见的字符跟着歌词进 UI。
        for (rawLine in text.removePrefix("\uFEFF").lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // [offset:+500] 是全局偏移不是歌词，单独认领。
            // LRC 惯例：正 offset 表示歌词整体提前（实际时刻 = 标签时刻 − offset）。
            Regex("""\[offset:([+-]?\d+)]""", RegexOption.IGNORE_CASE).find(line)?.let {
                offsetMs = it.groupValues[1].toLongOrNull() ?: 0L
                continue
            }

            // 逐字时间戳先剥掉：`[00:12.00]<00:12.00>词<00:14.00>句` → `[00:12.00]词句`。
            // 元数据标签也剥掉，防 `[ti:歌名][00:12.00]词` 这类混排把标签文本带进歌词。
            val withoutWords = WORD_TIMESTAMP.replace(line, "")
            val content = META_TAG.replace(withoutWords, "")

            // 行首可能连着多个时间戳，每个都要展开成独立行。
            val stamps = TIMESTAMP.findAll(content).toList()
            if (stamps.isEmpty()) continue // 纯文本行（没时间轴）不显示——同步歌词只认带时间的行
            // 零宽字符不算空白（U+200B 的 isWhitespace 是 false），但它在屏幕上一个像素都没有：
            // 只由零宽字符构成的「空行」必须当空行扔掉，否则会占掉一个句子位，造成空跳。
            val body = content.substring(stamps.last().range.last + 1).trim().trim('\u200B', '\uFEFF')
            if (body.isEmpty()) continue
            // 署名行在展开时间戳之前就拦掉：一行挂多个时间戳的署名也只算一次，且全部丢弃。
            if (isCreditLine(body)) continue

            for (stamp in stamps) {
                val (min, sec, frac) = stamp.destructured
                val timeMs = min.toLong() * 60_000L + sec.toLong() * 1_000L +
                    fracToMs(frac)
                out += LrcLine(timeMs - offsetMs, body)
            }
        }

        if (out.isEmpty()) return null
        out.sortBy { it.timeMs }
        return LyricsDoc(out)
    }

    /**
     * 这一行是署名/占位信息（不是唱词）吗？
     *
     * 逐项短路，一首歌几十行、每行几次 [String.contains]，相对一次 IO 的解析开销可忽略。
     * 判据按「误伤唱词的代价 > 漏掉一行署名的代价」来分级：硬词只在唱不出来的字眼上开，
     * 普通词（演唱/词/曲…）必须带冒号才算标签。
     */
    private fun isCreditLine(text: String): Boolean =
        CREDIT_HARD.any { text.contains(it) } ||
            CREDIT_SOFT.containsMatchIn(text) ||
            CREDIT_LABEL.containsMatchIn(text) ||
            CREDIT_TITLE.containsMatchIn(text) ||
            CREDIT_PLACEHOLDER.any { text.contains(it) } ||
            CREDIT_EN.containsMatchIn(text)

    /** 厘秒段按位数折算毫秒：缺省（无小数段）为 0，1 位=×100，2 位=×10，3 位=×1。 */
    private fun fracToMs(frac: String): Long = when (frac.length) {
        0 -> 0L
        1 -> frac.toLong() * 100L
        2 -> frac.toLong() * 10L
        else -> frac.take(3).toLong()
    }
}
