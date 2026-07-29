package com.walkingrpg.shared.di

import android.content.Context
import io.ktor.client.engine.HttpClientEngine
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify
import kotlin.test.Test

/**
 * `sharedModule`（＋ `platformModule` のAndroid実装）のDIグラフを、
 * アプリを起動せずに検証する。
 *
 * Koinの解決は実行時なので、`get()` の相手を登録し忘れても
 * コンパイルは通ってしまう（PR #22 の申し送り）。`verify()` は各定義の
 * コンストラクタ引数を走査して、対応する定義が無ければテストを落とす。
 *
 * ## 置き場所が androidUnitTest な理由
 * `verify()` はリフレクションを使うJVM専用API（koin-test）なので commonTest には置けない。
 * Androidターゲットのunit testに置くことで、`platformModule` のAndroid actual まで
 * 含めた「実際にアプリが組む形」のグラフを検証できる（iOS actual は同じ形の定義なので、
 * ここで登録漏れを潰せば実質カバーできる）。
 */
class SharedModuleVerifyTest {

    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun sharedModuleIsFullyResolvable() {
        sharedModule.verify(extraTypes = EXTRA_TYPES)
    }

    private companion object {
        /**
         * Koin管理外（＝モジュールに定義が無くて当然）の型。
         * ここに挙げないと `verify()` が「未提供」と誤検知して落ちる。
         *
         * - [Context]: `androidContext()` 経由で、`startKoin { androidContext(...) }` が
         *   Koin本体に直接登録する。モジュール定義としては現れない
         * - [HttpClientEngine]: `HttpClient` はKtorのファクトリ関数が
         *   プラットフォーム既定のエンジンを内部で選ぶ。`verify()` は定義の型
         *   （＝`HttpClient`）のコンストラクタ引数を見るので、DIでは渡していない
         *   エンジンを「未提供」と誤検知する
         */
        val EXTRA_TYPES = listOf(Context::class, HttpClientEngine::class)
    }
}
