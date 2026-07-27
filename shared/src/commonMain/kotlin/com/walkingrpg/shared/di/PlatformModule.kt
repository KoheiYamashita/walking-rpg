package com.walkingrpg.shared.di

import org.koin.core.module.Module

/**
 * プラットフォーム層（expect/actual）のDI定義。
 *
 * 測位・権限・Foreground Service・共有・DBドライバの実装をここで束ね、
 * [sharedModule] から include する。上位層はインターフェースしか見ない。
 */
expect val platformModule: Module
