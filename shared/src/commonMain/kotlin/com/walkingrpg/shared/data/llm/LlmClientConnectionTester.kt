package com.walkingrpg.shared.data.llm

import com.walkingrpg.shared.domain.llm.LlmClientSelector
import com.walkingrpg.shared.domain.llm.LlmFailureKind
import com.walkingrpg.shared.domain.llm.LlmRequest
import com.walkingrpg.shared.domain.llm.LlmUnavailableException
import com.walkingrpg.shared.domain.setup.LlmConnectionFailure
import com.walkingrpg.shared.domain.setup.LlmConnectionSettings
import com.walkingrpg.shared.domain.setup.LlmConnectionTestResult
import com.walkingrpg.shared.domain.setup.LlmConnectionTester
import kotlinx.coroutines.CancellationException

/**
 * [LlmConnectionTester] を [LlmClientSelector] の上に実装した薄いアダプタ（issue #14）。
 *
 * issue #6 では専用の実装（`HttpLlmConnectionTester`）を置いていたが、#14 で
 * 本物の生成経路（[com.walkingrpg.shared.domain.llm.LlmClient]）が入ったので消した。
 * **セットアップ画面が確認するのは「プレイ中に使う経路が通るか」であるべき**で、
 * 疎通専用の別経路を持つと、疎通は通るのに生成だけ落ちる組み合わせを見逃す
 * （URLの組み立て・認可ヘッダ・応答の読み方は全部この向こう側で共有される）。
 *
 * ## 投げるのは最小のリクエスト
 * `max_tokens = 1` ・systemプロンプト無しの `ping` を1回だけ。
 * 生成結果は読まない：知りたいのは「キーが通るか」「接続先とモデル名が実在するか」だけ。
 *
 * リトライもしない（[LlmRequest.allowRetry] = false）。ベースURLの綴り間違いで
 * 名前解決に失敗しているときに粘られても、ユーザーは入力を直したいだけなので待ち時間が伸びる。
 */
internal class LlmClientConnectionTester(
    private val clients: LlmClientSelector,
) : LlmConnectionTester {

    override suspend fun test(settings: LlmConnectionSettings): LlmConnectionTestResult {
        val request = LlmRequest(
            systemPrompt = "",
            userPrompt = "ping",
            maxTokens = 1,
            allowRetry = false,
        )

        return try {
            clients.client(settings.format).generate(request, settings)
            LlmConnectionTestResult.Success
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: LlmUnavailableException) {
            error.toResult()
        } catch (error: Throwable) {
            LlmConnectionTestResult.Failure(LlmConnectionFailure.UNEXPECTED)
        }
    }
}

/**
 * 生成の失敗を、画面に出す疎通結果へ翻訳する。
 *
 * ## 本文が空でも「疎通OK」
 * `max_tokens = 1` で投げているので、モデルが1トークンも出さずに終わる
 * （応答の本文が空）ことは普通に起きる。**2xxが返っている＝キーもURLもモデル名も通った**
 * ということなので、[LlmFailureKind.EMPTY_RESPONSE] は成功として扱う。
 *
 * 一方 [LlmFailureKind.MALFORMED_RESPONSE] は失敗にする：2xxでもJSONの形が違うのは
 * 接続先がLLM APIではない（社内プロキシのログイン画面など）ときに起きるので、
 * ここを通してしまうと「セットアップは通ったのに文章が一切生成されない」状態で
 * プレイが始まってしまう。
 */
private fun LlmUnavailableException.toResult(): LlmConnectionTestResult {
    if (reason == LlmFailureKind.EMPTY_RESPONSE) return LlmConnectionTestResult.Success

    val failure = when (reason) {
        LlmFailureKind.UNAUTHORIZED -> LlmConnectionFailure.UNAUTHORIZED
        LlmFailureKind.NOT_FOUND -> LlmConnectionFailure.NOT_FOUND
        LlmFailureKind.RATE_LIMITED -> LlmConnectionFailure.RATE_LIMITED
        LlmFailureKind.SERVER_ERROR -> LlmConnectionFailure.SERVER_ERROR
        LlmFailureKind.TIMEOUT -> LlmConnectionFailure.TIMEOUT
        LlmFailureKind.NETWORK -> LlmConnectionFailure.NETWORK
        // 設定を引数で渡しているのでここには来ないが、分岐は書いておく
        LlmFailureKind.NOT_CONFIGURED -> LlmConnectionFailure.INVALID_INPUT
        LlmFailureKind.MALFORMED_RESPONSE,
        LlmFailureKind.EMPTY_RESPONSE,
        LlmFailureKind.UNEXPECTED,
        -> LlmConnectionFailure.UNEXPECTED
    }
    return LlmConnectionTestResult.Failure(reason = failure, detail = detailText())
}

/**
 * 原因の切り分けに役立つ補足。
 *
 * - HTTPまで届いた場合：ステータスと、400 / 404 なら応答本文の先頭
 *   （モデル名の綴り間違いはここに書いてある。[LlmUnavailableException] のKDoc）
 * - 届かなかった場合：例外の**種別から作った定型文**だけ（生メッセージは載っていない）
 */
private fun LlmUnavailableException.detailText(): String? {
    val status = statusCode
    if (status != null) {
        return "HTTP $status" + (responseExcerpt?.let { ": $it" } ?: "")
    }
    // 分類そのままの文言は LlmConnectionFailure 側にもあるので重ねない
    return message?.takeIf { it != reason.message }
}
