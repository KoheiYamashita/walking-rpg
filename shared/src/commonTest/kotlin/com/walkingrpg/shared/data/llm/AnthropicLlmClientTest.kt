package com.walkingrpg.shared.data.llm

import com.walkingrpg.shared.domain.llm.LlmFailureKind
import com.walkingrpg.shared.domain.llm.LlmUnavailableException
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Anthropic Messages API 実装（[AnthropicLlmClient]）。
 *
 * 見たいのは3つ：リクエストの形（URL・必須ヘッダ・ボディ）、応答から本文を取り出せるか、
 * 失敗の分類。**例外に秘密（APIキー・URL）を載せない**ことも併せて見る。
 */
class AnthropicLlmClientTest {

    @Test
    fun messagesへ必須ヘッダを付けて投げる() = runTest {
        val (httpClient, requests) = respondingClient(anthropicBody("木陰が涼しい。"))

        AnthropicLlmClient(httpClient).generate(testRequest(), anthropicSettings)

        val request = requests.single()
        assertEquals("https://anthropic.example.invalid/v1/messages", request.url.toString())
        assertEquals("dummy-key-for-test", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        // 認可方式が混ざっていないこと
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun systemはトップレベルで送る() = runTest {
        val (httpClient, requests) = respondingClient(anthropicBody("一言。"))

        AnthropicLlmClient(httpClient).generate(
            testRequest(systemPrompt = "情景だけを書く", userPrompt = "場所の種類: 公園"),
            anthropicSettings,
        )

        val body = requests.single().body.toByteArray().decodeToString()
        assertTrue("\"model\":\"test-model\"" in body, body)
        assertTrue("\"max_tokens\":300" in body, body)
        assertTrue("\"system\":\"情景だけを書く\"" in body, body)
        assertTrue("\"role\":\"user\"" in body, body)
        assertTrue("場所の種類: 公園" in body, body)
    }

    @Test
    fun systemが空なら送らない() = runTest {
        // 疎通確認（system無しのping）でも通ること
        val (httpClient, requests) = respondingClient(anthropicBody("a"))

        AnthropicLlmClient(httpClient).generate(
            testRequest(systemPrompt = "", maxTokens = 1),
            anthropicSettings,
        )

        val body = requests.single().body.toByteArray().decodeToString()
        assertFalse("\"system\"" in body, body)
    }

    @Test
    fun ベースURLの末尾スラッシュを二重にしない() = runTest {
        val (httpClient, requests) = respondingClient(anthropicBody("一言。"))

        AnthropicLlmClient(httpClient).generate(
            testRequest(),
            anthropicSettings.copy(baseUrl = "https://anthropic.example.invalid/"),
        )

        assertEquals(
            "https://anthropic.example.invalid/v1/messages",
            requests.single().url.toString(),
        )
    }

    @Test
    fun テキストブロックから本文を取り出す() = runTest {
        val (httpClient, _) = respondingClient(anthropicBody("木陰が涼しい。"))

        val response = AnthropicLlmClient(httpClient).generate(testRequest(), anthropicSettings)

        assertEquals("木陰が涼しい。", response.text)
    }

    @Test
    fun 複数のブロックはテキストだけ繋ぐ() = runTest {
        val body = """
            {"content":[
              {"type":"thinking","thinking":"考え中"},
              {"type":"text","text":"前半。"},
              {"type":"text","text":"後半。"}
            ]}
        """.trimIndent()
        val (httpClient, _) = respondingClient(body)

        val response = AnthropicLlmClient(httpClient).generate(testRequest(), anthropicSettings)

        assertEquals("前半。後半。", response.text)
    }

    @Test
    fun 本文の取り出し先が無ければ形式の問題として扱う() = runTest {
        val (httpClient, _) = respondingClient("{}")

        val error = assertFailsWith<LlmUnavailableException> {
            AnthropicLlmClient(httpClient).generate(testRequest(), anthropicSettings)
        }

        assertEquals(LlmFailureKind.MALFORMED_RESPONSE, error.reason)
    }

    @Test
    fun 本文が空なら空応答として扱う() = runTest {
        // max_tokens=1 で1トークンも出なかった場合など
        val (httpClient, _) = respondingClient("""{"content":[]}""")

        val error = assertFailsWith<LlmUnavailableException> {
            AnthropicLlmClient(httpClient).generate(testRequest(), anthropicSettings)
        }

        assertEquals(LlmFailureKind.EMPTY_RESPONSE, error.reason)
    }

    @Test
    fun JSONでない応答も形式の問題として扱う() = runTest {
        // ベースURLがLLM APIではない何か（プロキシのログイン画面など）を指している場合
        val (httpClient, _) = recordingClient {
            respond("<html>login</html>", HttpStatusCode.OK, jsonHeaders)
        }

        val error = assertFailsWith<LlmUnavailableException> {
            AnthropicLlmClient(httpClient).generate(testRequest(), anthropicSettings)
        }

        assertEquals(LlmFailureKind.MALFORMED_RESPONSE, error.reason)
        assertFalse("login" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun エラー応答を分類する() = runTest {
        val cases = mapOf(
            HttpStatusCode.Unauthorized to LlmFailureKind.UNAUTHORIZED,
            HttpStatusCode.Forbidden to LlmFailureKind.UNAUTHORIZED,
            HttpStatusCode.NotFound to LlmFailureKind.NOT_FOUND,
            HttpStatusCode.TooManyRequests to LlmFailureKind.RATE_LIMITED,
            HttpStatusCode.BadRequest to LlmFailureKind.UNEXPECTED,
        )

        cases.forEach { (status, expected) ->
            val (httpClient, _) = recordingClient { respondError(status) }
            val error = assertFailsWith<LlmUnavailableException> {
                AnthropicLlmClient(httpClient).generate(testRequest(), anthropicSettings)
            }
            assertEquals(expected, error.reason, "$status の分類が違う")
            assertEquals(status.value, error.statusCode)
        }
    }

    @Test
    fun 例外にURLとAPIキーを載せない() = runTest {
        val (httpClient, _) = recordingClient { respondError(HttpStatusCode.Unauthorized) }

        val error = assertFailsWith<LlmUnavailableException> {
            AnthropicLlmClient(httpClient).generate(testRequest(), anthropicSettings)
        }

        val text = error.message.orEmpty() + error.responseExcerpt.orEmpty()
        assertFalse("dummy-key-for-test" in text, text)
        assertFalse("anthropic.example.invalid" in text, text)
        assertNull(error.responseExcerpt, "401の本文はキーの一部をechoしうるので持ち帰らない")
    }

    @Test
    fun モデル名の綴り間違いは応答本文の先頭で切り分けられる() = runTest {
        // 400 の本文には「そのモデルは無い」と書いてあることが多い（issue #6 からの引き継ぎ）
        val (httpClient, _) = recordingClient {
            respond("""{"error":{"message":"model: test-model not found"}}""", HttpStatusCode.BadRequest)
        }

        val error = assertFailsWith<LlmUnavailableException> {
            AnthropicLlmClient(httpClient).generate(testRequest(), anthropicSettings)
        }

        assertTrue("not found" in error.responseExcerpt.orEmpty(), error.responseExcerpt.orEmpty())
    }

    @Test
    fun 通信できなければネットワーク不達として扱う() = runTest {
        val (httpClient, _) = recordingClient { throw IllegalStateException(RAW_MESSAGE) }

        val error = assertFailsWith<LlmUnavailableException> {
            AnthropicLlmClient(httpClient).generate(
                testRequest(allowRetry = false),
                anthropicSettings,
            )
        }

        assertEquals(LlmFailureKind.NETWORK, error.reason)
        assertNull(error.statusCode)
        // 例外の生メッセージには接続先URLやプロキシ設定が混ざりうる
        assertFalse(RAW_MESSAGE in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun 通信失敗は例外の種別から定型文言に落とす() = runTest {
        val cases = listOf(
            Triple(
                UnresolvedAddressException(),
                LlmFailureKind.NETWORK,
                "ホスト名を解決できませんでした",
            ),
            Triple(SocketTimeoutException(), LlmFailureKind.TIMEOUT, null),
            Triple(SSLHandshakeException(), LlmFailureKind.NETWORK, "TLS"),
            Triple(ConnectException(), LlmFailureKind.NETWORK, "接続できませんでした"),
        )

        cases.forEach { (thrown, expectedReason, expectedHint) ->
            val (httpClient, _) = recordingClient { throw thrown }
            val error = assertFailsWith<LlmUnavailableException> {
                AnthropicLlmClient(httpClient).generate(
                    testRequest(allowRetry = false),
                    anthropicSettings,
                )
            }

            assertEquals(expectedReason, error.reason, "${thrown::class.simpleName} の分類が違う")
            assertFalse(RAW_MESSAGE in error.message.orEmpty(), error.message.orEmpty())
            if (expectedHint != null) {
                assertTrue(expectedHint in error.message.orEmpty(), error.message.orEmpty())
            }
        }
    }

    @Test
    fun 原因側の例外種別も見る() = runTest {
        // エンジンが数段ラップしてくることがある
        val (httpClient, _) = recordingClient {
            throw IllegalStateException(RAW_MESSAGE, UnresolvedAddressException())
        }

        val error = assertFailsWith<LlmUnavailableException> {
            AnthropicLlmClient(httpClient).generate(
                testRequest(allowRetry = false),
                anthropicSettings,
            )
        }

        assertTrue("ホスト名を解決できませんでした" in error.message.orEmpty())
    }

    @Test
    fun サーバ側のエラーは再試行する() = runTest {
        // 混雑・一時的な障害は間を置けば通ることが多い（生成は家の中で走るので粘ってよい）
        val (httpClient, requests) = recordingClient {
            respondError(HttpStatusCode.ServiceUnavailable)
        }

        assertFailsWith<LlmUnavailableException> {
            AnthropicLlmClient(httpClient).generate(testRequest(), anthropicSettings)
        }

        assertEquals(3, requests.size, "初回＋2回の再試行")
    }

    @Test
    fun 認証エラーは再試行しない() = runTest {
        // キーが違うのに何度も投げない（レート制限を踏みに行かない）
        val (httpClient, requests) = recordingClient { respondError(HttpStatusCode.Unauthorized) }

        assertFailsWith<LlmUnavailableException> {
            AnthropicLlmClient(httpClient).generate(testRequest(), anthropicSettings)
        }

        assertEquals(1, requests.size)
    }

    @Test
    fun 再試行しない依頼は一度だけ投げる() = runTest {
        // 疎通確認（セットアップ画面）でユーザーを待たせないための経路
        val (httpClient, requests) = recordingClient {
            respondError(HttpStatusCode.ServiceUnavailable)
        }

        assertFailsWith<LlmUnavailableException> {
            AnthropicLlmClient(httpClient).generate(
                testRequest(allowRetry = false),
                anthropicSettings,
            )
        }

        assertEquals(1, requests.size)
    }
}

/**
 * 分類は例外の**型名**で行う（DNS失敗やTLSエラーの型はJVM / Darwinで違い、
 * commonTest から実物を投げ分けられない）。ここでは本物と同じ名前のダミーを使う。
 */
private const val RAW_MESSAGE = "raw-message-should-not-be-shown"

private class UnresolvedAddressException : Exception(RAW_MESSAGE)

private class SocketTimeoutException : Exception(RAW_MESSAGE)

private class SSLHandshakeException : Exception(RAW_MESSAGE)

private class ConnectException : Exception(RAW_MESSAGE)
