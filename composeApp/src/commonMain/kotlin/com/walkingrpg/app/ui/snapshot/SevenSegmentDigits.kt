package com.walkingrpg.app.ui.snapshot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * 数字とハイフンだけを線分で引く7セグメント表示。
 *
 * 月次スナップショットの月ラベル（'2026-06'）専用。フォントを使わない理由は
 * `ComposeMonthlySnapshotRenderer.drawMonthLabel` のKDoc。
 * 汎用のテキスト描画にはしない：画面に出す文字は全部Composeの `Text` が担うので、
 * ここが必要になるのは「コンポジションの外で焼き込む1枚」だけ。
 *
 * 扱えない文字は空白として飛ばす（描けない文字で例外を投げても、
 * 絵が1枚作られなくなるだけで誰も得をしない）。
 */
internal object SevenSegmentDigits {

    /** 桁の幅（高さに対する比）。細めにして数字らしく見せる。 */
    private const val DIGIT_WIDTH_RATIO = 0.52f

    /** 桁間の空き（高さに対する比）。 */
    private const val DIGIT_GAP_RATIO = 0.22f

    /** ハイフンの幅（高さに対する比）。数字より狭くする。 */
    private const val SEPARATOR_WIDTH_RATIO = 0.34f

    /**
     * [text] を [origin] を左上として描く。
     *
     * @param digitHeight 1桁の高さ（px）。幅はここから決まる。
     */
    fun draw(
        drawScope: DrawScope,
        text: String,
        origin: Offset,
        digitHeight: Float,
        strokeWidth: Float,
        color: Color,
    ) {
        val digitWidth = digitHeight * DIGIT_WIDTH_RATIO
        val separatorWidth = digitHeight * SEPARATOR_WIDTH_RATIO
        val gap = digitHeight * DIGIT_GAP_RATIO

        var left = origin.x
        for (character in text) {
            when {
                character.isDigit() -> {
                    drawScope.drawDigit(
                        digit = character - '0',
                        left = left,
                        top = origin.y,
                        width = digitWidth,
                        height = digitHeight,
                        strokeWidth = strokeWidth,
                        color = color,
                    )
                    left += digitWidth + gap
                }

                character == '-' -> {
                    val middle = origin.y + digitHeight / 2f
                    drawScope.segment(
                        from = Offset(left, middle),
                        to = Offset(left + separatorWidth, middle),
                        strokeWidth = strokeWidth,
                        color = color,
                    )
                    left += separatorWidth + gap
                }

                // 描けない文字は1桁ぶん空けて飛ばす
                else -> left += digitWidth + gap
            }
        }
    }

    /**
     * 1桁ぶん。
     *
     * 7つのセグメントの並びは
     * ```
     *  --- TOP ---
     * |           |
     * TOP_LEFT  TOP_RIGHT
     * |           |
     *  -- MIDDLE --
     * |           |
     * BOTTOM_LEFT BOTTOM_RIGHT
     * |           |
     *  - BOTTOM ---
     * ```
     */
    private fun DrawScope.drawDigit(
        digit: Int,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        strokeWidth: Float,
        color: Color,
    ) {
        val right = left + width
        val middle = top + height / 2f
        val bottom = top + height
        val segments = SEGMENTS[digit]

        fun line(from: Offset, to: Offset) = segment(from, to, strokeWidth, color)

        if (Segment.TOP in segments) line(Offset(left, top), Offset(right, top))
        if (Segment.MIDDLE in segments) line(Offset(left, middle), Offset(right, middle))
        if (Segment.BOTTOM in segments) line(Offset(left, bottom), Offset(right, bottom))
        if (Segment.TOP_LEFT in segments) line(Offset(left, top), Offset(left, middle))
        if (Segment.TOP_RIGHT in segments) line(Offset(right, top), Offset(right, middle))
        if (Segment.BOTTOM_LEFT in segments) line(Offset(left, middle), Offset(left, bottom))
        if (Segment.BOTTOM_RIGHT in segments) line(Offset(right, middle), Offset(right, bottom))
    }

    private fun DrawScope.segment(from: Offset, to: Offset, strokeWidth: Float, color: Color) {
        drawLine(
            color = color,
            start = from,
            end = to,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }

    private enum class Segment {
        TOP,
        TOP_LEFT,
        TOP_RIGHT,
        MIDDLE,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
        BOTTOM,
    }

    /** 0〜9それぞれで光らせるセグメント（添字＝数字）。 */
    private val SEGMENTS: List<Set<Segment>> = listOf(
        setOf(
            Segment.TOP, Segment.TOP_LEFT, Segment.TOP_RIGHT,
            Segment.BOTTOM_LEFT, Segment.BOTTOM_RIGHT, Segment.BOTTOM,
        ),
        setOf(Segment.TOP_RIGHT, Segment.BOTTOM_RIGHT),
        setOf(
            Segment.TOP, Segment.TOP_RIGHT, Segment.MIDDLE,
            Segment.BOTTOM_LEFT, Segment.BOTTOM,
        ),
        setOf(
            Segment.TOP, Segment.TOP_RIGHT, Segment.MIDDLE,
            Segment.BOTTOM_RIGHT, Segment.BOTTOM,
        ),
        setOf(Segment.TOP_LEFT, Segment.TOP_RIGHT, Segment.MIDDLE, Segment.BOTTOM_RIGHT),
        setOf(
            Segment.TOP, Segment.TOP_LEFT, Segment.MIDDLE,
            Segment.BOTTOM_RIGHT, Segment.BOTTOM,
        ),
        setOf(
            Segment.TOP, Segment.TOP_LEFT, Segment.MIDDLE,
            Segment.BOTTOM_LEFT, Segment.BOTTOM_RIGHT, Segment.BOTTOM,
        ),
        setOf(Segment.TOP, Segment.TOP_RIGHT, Segment.BOTTOM_RIGHT),
        Segment.entries.toSet(),
        setOf(
            Segment.TOP, Segment.TOP_LEFT, Segment.TOP_RIGHT, Segment.MIDDLE,
            Segment.BOTTOM_RIGHT, Segment.BOTTOM,
        ),
    )
}
