package com.elina.assistant.rag

/**
 * Tiny in-memory retriever seeded with the **Eight Wonders of the World**
 * (古代七大奇迹 + 兵马俑「世界第八大奇迹」). Suitable for kiosks at
 * world-history museums, school classrooms, and travel-themed venues.
 *
 * Retrieval strategy (no external tokenizer required):
 *   1. Latin/space-separated tokens of length ≥ 2
 *   2. Chinese character bigrams (e.g. "金字塔" → "金字", "字塔")
 *   3. Single CJK characters (low weight, fallback)
 *
 * For each snippet we sum hit counts in (title + text + aliases), weighting
 * multi-character matches above single-char fallbacks. This is a meaningful
 * upgrade over plain `contains` for Chinese, while staying small/dependency-free.
 *
 * To replace this with a production index (BM25 + embedding fusion), implement
 * the [RagRetriever] interface and swap in MainActivity.
 */
class InMemoryRagRetriever(
    private val snippets: List<RagSnippet> = eightWonders(),
) : RagRetriever {

    override suspend fun retrieve(query: String, topK: Int): List<RagSnippet> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()

        val terms = mutableMapOf<String, Double>()
        // 1. Latin/space-separated tokens
        for (tok in q.split(Regex("[\\s，。、？！?!,.\":：；;'']+"))) {
            if (tok.length >= 2 && tok.any { !it.isCJK() }) terms[tok] = 2.0
        }
        // 2. Chinese bigrams (catch most queries: "金字塔" / "兵马俑")
        for (i in 0 until q.length - 1) {
            val a = q[i]; val b = q[i + 1]
            if (a.isCJK() && b.isCJK()) terms["$a$b"] = 2.0
        }
        // 3. Single CJK chars (low-weight fallback so 1-char questions still work)
        for (c in q) if (c.isCJK()) terms.merge(c.toString(), 0.4) { a, _ -> a }

        return snippets
            .map { snippet ->
                val hay = (snippet.title + " " + snippet.text + " " +
                    snippet.aliases.joinToString(" ")).lowercase()
                var score = 0.0
                for ((term, weight) in terms) {
                    val n = countOccurrences(hay, term)
                    if (n > 0) score += n * weight
                }
                snippet.copy(score = score)
            }
            .filter { it.score >= 1.0 }   // require at least one bigram hit (or several singles)
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var idx = 0
        var count = 0
        while (true) {
            val found = haystack.indexOf(needle, idx)
            if (found < 0) break
            count++
            idx = found + needle.length
        }
        return count
    }

    private fun Char.isCJK(): Boolean =
        code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF

    companion object {
        /**
         * Sample knowledge base. Replace with your venue's catalog
         * (load from JSON/CSV in assets, or query a SQLite DB).
         */
        fun eightWonders(): List<RagSnippet> = listOf(

            RagSnippet(
                id = "wonder-pyramid",
                title = "古代七大奇迹 · 吉萨大金字塔（胡夫金字塔）",
                text = "建于约公元前 2560 年，位于埃及吉萨高原，是法老胡夫的陵墓。" +
                    "原高 146.6 米（现 138.8 米），底边 230 米见方，由约 230 万块巨石垒成，" +
                    "最大单块重达 80 吨。是古代七大奇迹中**唯一保存至今**的，也是现存最古老的。" +
                    "墓室通过狭长的甬道与王后殿、大画廊连接，建造工艺之精至今仍是工程学界的研究课题。",
                aliases = listOf("金字塔", "胡夫", "Khufu", "Giza", "Pyramid", "吉萨", "法老"),
            ),

            RagSnippet(
                id = "wonder-hanging-gardens",
                title = "古代七大奇迹 · 巴比伦空中花园",
                text = "据传由新巴比伦王国国王尼布甲尼撒二世（约公元前 600 年）为其王后阿米提斯所建，" +
                    "因王后思念故乡米底山区的绿色山林。花园采用阶梯式立体结构，每层种植树木花草，" +
                    "依靠从幼发拉底河抽水的复杂灌溉系统维持。其确切位置考古界至今未达成共识，" +
                    "一说位于今伊拉克巴比伦遗址，亦有学者认为可能与亚述国王辛那赫里布在尼尼微的花园混淆。",
                aliases = listOf("空中花园", "巴比伦", "Babylon", "Hanging Gardens", "尼布甲尼撒"),
            ),

            RagSnippet(
                id = "wonder-zeus",
                title = "古代七大奇迹 · 奥林匹亚宙斯巨像",
                text = "由古希腊雕刻家菲狄亚斯于公元前 435 年完成，供奉在伯罗奔尼撒半岛奥林匹亚的宙斯神庙内。" +
                    "高约 12 米，以木为骨架，外覆象牙肌肤与黄金长袍，宝座上镶嵌乌木与宝石。" +
                    "宙斯左手持权杖，右手托胜利女神尼姬。公元 5 世纪后毁于战火或地震，" +
                    "但留下了无数古希腊钱币与文献描绘，是西方造型艺术的源头之一。",
                aliases = listOf("宙斯", "奥林匹亚", "Olympia", "Zeus", "菲狄亚斯", "Phidias"),
            ),

            RagSnippet(
                id = "wonder-artemis",
                title = "古代七大奇迹 · 以弗所阿尔忒弥斯神庙",
                text = "位于今土耳其塞尔丘克附近的古城以弗所，建于约公元前 550 年，由吕底亚国王克罗伊斯出资重建。" +
                    "供奉狩猎与生育女神阿尔忒弥斯，全大理石建造，长 115 米、宽 55 米，前后排列 127 根爱奥尼亚柱。" +
                    "公元前 356 年被纵火毁，传说是亚历山大大帝出生之夜；后重建，再于公元 268 年遭哥特人洗劫。" +
                    "今存遗址仅剩一根孤柱。",
                aliases = listOf("阿尔忒弥斯", "阿耳忒弥斯", "以弗所", "Artemis", "Ephesus", "神庙"),
            ),

            RagSnippet(
                id = "wonder-mausoleum",
                title = "古代七大奇迹 · 哈利卡纳苏斯陵墓",
                text = "卡里亚总督摩索拉斯之妻阿尔特米西娅二世于公元前 351 年为亡夫所建，位于今土耳其博德鲁姆。" +
                    "高约 45 米，由白色大理石建成，分基座、柱廊、金字塔顶三层，雕饰繁复。" +
                    "「Mausoleum」（陵墓）一词正源于此。" +
                    "12-15 世纪连续地震使其坍塌，残石被十字军骑士团运去筑圣彼得城堡。",
                aliases = listOf("哈利卡纳苏斯", "摩索拉斯", "陵墓", "Mausoleum", "Halicarnassus", "博德鲁姆"),
            ),

            RagSnippet(
                id = "wonder-colossus",
                title = "古代七大奇迹 · 罗德岛太阳神巨像",
                text = "建造于公元前 292-280 年，纪念罗德岛人击退马其顿大军。青铜铸造，高约 33 米，" +
                    "矗立于罗德港入口，被认为是当时世界上最高的雕像。神话中常误传其双脚跨立港口两岸，" +
                    "实为单足立于港边基座。仅屹立 54 年，公元前 226 年因地震膝盖断裂而倒塌；" +
                    "公元 654 年被入侵者熔铸成金属销售。",
                aliases = listOf("罗得岛", "罗德岛", "太阳神", "Colossus", "Rhodes", "巨像", "赫利俄斯"),
            ),

            RagSnippet(
                id = "wonder-lighthouse",
                title = "古代七大奇迹 · 亚历山大灯塔",
                text = "由托勒密王朝建于公元前 280-247 年，位于埃及亚历山大港外的法罗斯岛，" +
                    "因此也称「法罗斯灯塔」。塔高约 100-130 米，是当时世界上最高的建筑之一，" +
                    "塔顶燃烧大火，借助青铜镜反射，光线据传可远达 50 公里外。" +
                    "公元 956、1303、1323 年三次大地震后彻底毁坏，1480 年原址改建为盖特贝堡。" +
                    "「pharos」一词成为多种语言中「灯塔」的词根。",
                aliases = listOf("亚历山大", "灯塔", "法罗斯", "Pharos", "Alexandria", "Lighthouse", "托勒密"),
            ),

            RagSnippet(
                id = "wonder-terracotta",
                title = "世界第八大奇迹 · 秦始皇兵马俑",
                text = "公元前 246-208 年间，秦始皇陵东侧 1.5 公里处，约 8000 件陶制武士俑、骑兵、车马组成的地下军阵。" +
                    "1974 年陕西临潼村民打井时偶然发现。每尊兵俑面容各异、发饰服色逼真，原本通体彩绘，" +
                    "出土后受空气氧化迅速褪色。1987 年列入世界文化遗产，被法国前总统希拉克誉为「世界第八大奇迹」。" +
                    "目前发掘的一、二、三号坑可参观，墓主秦始皇陵主体仍未开启。",
                aliases = listOf("兵马俑", "秦始皇", "秦俑", "Terracotta", "Qin", "临潼", "西安", "第八大奇迹"),
            ),
        )
    }
}
