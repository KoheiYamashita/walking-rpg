package com.walkingrpg.shared.di

import org.koin.core.module.Module

/**
 * プラットフォーム層（expect/actual）のDI定義。
 *
 * 測位・権限・Foreground Service・共有・DBドライバ（issue #2）の実装を
 * ここで束ね、[sharedModule] から include する。
 * 実装クラスそのものが android / ios にしか存在しないため、
 * インスタンス生成を expect/actual で分ける。`Context` のような依存が要る実装も
 * ここで解決して渡す。上位層はインターフェースしか見ない。
 */
expect val platformModule: Module
