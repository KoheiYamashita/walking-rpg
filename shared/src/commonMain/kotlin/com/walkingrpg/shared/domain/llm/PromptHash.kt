package com.walkingrpg.shared.domain.llm

/**
 * プロンプトの指紋（`llm_cache.prompt_hash`）。
 *
 * ## 何のためにあるか
 * キャッシュの論理キー（「どの地点のフレーバーか」）だけで引くと、**プロンプトを直したのに
 * 古い文章が出続ける**。逆にプロンプト全文をキーにすると、キーがそのまま数百バイトになり
 * 主キーとしても索引としても扱いにくい。そこで論理キーで引き、
 * ハッシュが一致しなければ「作り直し」と判断する（[LlmCacheRepository] のKDoc）。
 *
 * ## 暗号強度は要らない
 * 秘密を隠すためではなく、**同じプロンプトかどうかを安く判定する**ためのものなので、
 * 衝突耐性が緩くてよい（衝突しても「古い文章が出る」＝プロンプト変更が1件反映されない、が最悪）。
 * そのぶん commonMain で外部依存なしに書ける FNV-1a 64bit を使う（stdlib だけで済む）。
 *
 * ## 安定していること
 * 端末・OS・プロセスをまたいで同じ結果でなければ、キャッシュが毎回作り直される。
 * `String.hashCode()` はKotlin/JVMとNativeで**同じ値になる保証がない**ので使わない。
 * ここでは UTF-8 のバイト列を自前で畳み込む＝規則が全部このファイルに書いてある。
 */
object PromptHash {

    /**
     * プロンプトの各部（system / user）をまとめて1つの指紋にする。
     *
     * @param parts 順序に意味がある。区切り（[SEPARATOR]）を挟むのは
     *  `("ab", "c")` と `("a", "bc")` を同じ指紋にしないため。
     */
    fun of(vararg parts: String): String {
        var hash = FNV_OFFSET_BASIS
        parts.forEachIndexed { index, part ->
            if (index > 0) hash = hash.fold(SEPARATOR)
            part.encodeToByteArray().forEach { byte -> hash = hash.fold(byte) }
        }
        return hash.toHex()
    }

    /** 1バイトぶんの畳み込み（FNV-1a：XOR してから素数を掛ける）。 */
    private fun Long.fold(byte: Byte): Long = (this xor (byte.toLong() and 0xFF)) * FNV_PRIME

    /**
     * 16桁の16進表記に揃える（先頭の0を落とさない）。
     * 桁数が揺れると人が見比べたときに紛らわしいだけで、実害は無いが揃えておく。
     */
    private fun Long.toHex(): String = toULong().toString(16).padStart(HEX_LENGTH, '0')

    /** FNV-1a 64bit の定数（http://www.isthe.com/chongo/tech/comp/fnv/）。 */
    private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME: Long = 1099511628211L
    private const val HEX_LENGTH: Int = 16

    /** 部品の区切りに使うバイト（本文には出てこない制御文字）。 */
    private const val SEPARATOR: Byte = 0x1F
}
