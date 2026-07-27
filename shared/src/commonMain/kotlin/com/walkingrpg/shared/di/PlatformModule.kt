package com.walkingrpg.shared.di

import org.koin.core.module.Module

/**
 * プラットフォーム固有の実装だけを束ねたKoinモジュール。
 *
 * 実装クラスそのものが android / ios にしか存在しないため、
 * インスタンス生成を expect/actual で分ける
 * （`sharedModule` からは [platformModule] を include するだけ）。
 * `Context` のような依存が要る実装も、ここで `get()` して渡せる。
 */
expect val platformModule: Module
