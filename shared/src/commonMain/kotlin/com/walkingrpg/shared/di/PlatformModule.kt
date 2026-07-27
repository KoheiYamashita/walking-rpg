package com.walkingrpg.shared.di

import org.koin.core.module.Module

/**
 * プラットフォーム固有の実装だけを束ねたKoinモジュール。
 *
 * Android実装は `Context` のような依存を必要とするため、
 * インスタンス生成そのものを expect/actual で分ける
 * （`sharedModule` からは [platformModule] を include するだけ）。
 */
expect val platformModule: Module
