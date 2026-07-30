package com.walkingrpg.shared.domain.llm

/**
 * 応答JSONから「キー1つぶんの文字列」を取り出す（純関数）。
 *
 * ## 手書きにした理由
 * ドメイン層に kotlinx.serialization を持ち込まないため（`shared/build.gradle.kts` の
 * commonMain の注記「domain は外部依存なしの純Kotlin」）。加えて、モデルはJSONの前後に
 * 前置きやコードブロックを付けてくることがあるので、厳格なパーサに通すより
 * 「そのキーの値だけを拾う」ほうがそのまま強い。
 *
 * 生成タスクごとのパーサ（[PoiFlavorResponseParser] /
 * [SpeciesDescriptionResponseParser]）はどれも「JSON 1キーを読む」だけなので、
 * エスケープの解き方をタスクごとに複製しない。タスク側に残るのはキー名と
 * 「読めなかったら定型文」の判断だけ。
 */
internal object LlmJsonText {

    /**
     * `{"<key>": "..."}` の値を返す。
     *
     * @return 取り出せた本文（前後の空白は落とす）。見つからない・空・途中で切れているなら `null`
     *  ＝呼び出し側は定型文で凌ぐ。
     */
    fun value(raw: String, key: String): String? {
        val quotedKey = "\"$key\""
        val keyIndex = raw.indexOf(quotedKey)
        if (keyIndex < 0) return null
        val colonIndex = raw.indexOf(':', startIndex = keyIndex + quotedKey.length)
        if (colonIndex < 0) return null
        val openQuote = raw.indexOf('"', startIndex = colonIndex + 1)
        if (openQuote < 0) return null
        return raw.readJsonString(openQuote + 1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * [from] からJSON文字列の閉じ引用符までを読む（エスケープを解く）。
     *
     * 閉じ引用符に辿り着けない＝出力上限で途中で切れた応答なので `null`
     * （半端な文章をキャッシュに焼き付けたくない）。
     */
    private fun String.readJsonString(from: Int): String? {
        val text = StringBuilder()
        var index = from
        while (index < length) {
            when (val char = this[index]) {
                '"' -> return text.toString()
                '\\' -> {
                    val escaped = getOrNull(index + 1) ?: return null
                    when (escaped) {
                        '"' -> text.append('"')
                        '\\' -> text.append('\\')
                        '/' -> text.append('/')
                        'n' -> text.append('\n')
                        'r' -> text.append('\r')
                        't' -> text.append('\t')
                        'b' -> text.append('\b')
                        'f' -> text.append('\u000C')
                        'u' -> {
                            val code = substring(
                                startIndex = index + 2,
                                endIndex = minOf(index + 6, length),
                            ).takeIf { it.length == 4 }?.toIntOrNull(radix = 16) ?: return null
                            text.append(code.toChar())
                            index += 4
                        }

                        else -> return null // 知らないエスケープ＝JSONとして壊れている
                    }
                    index += 2
                }

                else -> {
                    text.append(char)
                    index++
                }
            }
        }
        return null
    }
}
