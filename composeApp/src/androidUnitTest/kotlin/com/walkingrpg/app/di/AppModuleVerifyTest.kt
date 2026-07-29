package com.walkingrpg.app.di

import android.content.Context
import com.walkingrpg.shared.di.sharedModule
import org.koin.core.annotation.KoinExperimentalAPI
import io.ktor.client.engine.HttpClientEngine
import org.koin.dsl.module
import org.koin.test.verify.verify
import kotlin.test.Test

/**
 * アプリが実際に起動するのと同じ組み合わせ（`sharedModule` + `appModule`）で
 * DIグラフが解決できることを、アプリを起動せずに検証する。
 *
 * ViewModelのコンストラクタ引数（UseCase）を `sharedModule` に登録し忘れても
 * コンパイルは通り、画面を開いた瞬間に初めて落ちる（PR #22 の申し送り）。
 * `verify()` は各定義のコンストラクタ引数を走査して、対応する定義が
 * 無ければここで落とす。
 *
 * ## 置き場所が androidUnitTest な理由
 * `verify()` はリフレクションを使うJVM専用API（koin-test）なので commonTest には置けない。
 * Androidターゲットのunit testなら `platformModule` のAndroid actual まで含めて、
 * 起動時と同じ形のグラフを検証できる。
 *
 * `sharedModule` 単体の検証は `shared` 側（SharedModuleVerifyTest）にもある。
 * shared だけを直したときに composeApp のテストを待たずに気付けるようにするため。
 */
class AppModuleVerifyTest {

    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun appGraphIsFullyResolvable() {
        // `verifyAll()` はモジュールを1つずつ独立に検証するので、
        // appModule のViewModelから sharedModule のUseCaseが見えない（全部「未提供」になる）。
        // includes でまとめた1モジュールにして、initKoin と同じ「全部載せ」の形で検証する。
        module { includes(sharedModule, appModule) }.verify(extraTypes = EXTRA_TYPES)
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
