package com.walkingrpg.shared.platform

/**
 * zipアーカイブの読み書き境界（architecture.md §6「手動エクスポート/インポート」）。
 *
 * ## なぜプラットフォーム側なのか
 * zipの実装はKMP共通に無い（Android: `java.util.zip` / iOS: `libcompression` 等）。
 * 圧縮そのものはドメインの語彙ではないので、バイト列 ⇄ エントリ列の変換だけを
 * この1枚に閉じ、上位層は [BackupEntry] のリストしか見ない。
 *
 * ## 何を知らないか
 * エントリの**中身の意味は知らない**（JSONかPNGかも見ない）。zipの構成と
 * 検証は `BackupCodec`（データ層）の仕事で、ここは「まとめる／ほどく」だけ。
 */
interface BackupArchive {

    /**
     * [entries] を1つのzipのバイト列にまとめる。
     *
     * 並びは渡された順に書く（読み出し側は名前で引くので順序に意味は無いが、
     * 同じ入力から同じバイト列が出るほうが差分を見るときに楽）。
     */
    fun write(entries: List<BackupEntry>): ByteArray

    /**
     * [zipBytes] をほどく。ディレクトリのエントリは落とす。
     *
     * zipとして読めなければ例外を投げる（黙って空を返さない：
     * 「壊れたファイルを選んだ」と「空のバックアップ」は別の話で、
     * 前者を後者として扱うと全削除が走ってしまう）。
     */
    fun read(zipBytes: ByteArray): List<BackupEntry>
}

/**
 * zipの中の1エントリ。
 *
 * @param name zip内のパス（`manifest.json` / `snapshots/2026-06.png`）。
 *  区切りは常に `/`（zipの仕様どおり。プラットフォームのパス区切りは持ち込まない）。
 */
data class BackupEntry(
    val name: String,
    val bytes: ByteArray,
) {
    // ByteArray の equals は参照比較なので、中身で比べる形に直す
    // （テストがエントリ単位の一致を見るため）。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BackupEntry) return false
        return name == other.name && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()

    /** 中身（画像・座標を含む）はログに出さない。 */
    override fun toString(): String = "BackupEntry(name=$name, size=${bytes.size})"
}
