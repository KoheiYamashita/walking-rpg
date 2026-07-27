package com.walkingrpg.app.ui

import androidx.compose.runtime.Composable

/**
 * 画面の消灯を抑止する（design.md §3「画面はONのまま携行するのが基本」、
 * architecture.md §5 の KEEP_SCREEN_ON）。
 *
 * ウィンドウ／画面そのものに紐づくUIの都合なので、UI層のexpect/actualで扱う
 * （DIで注入するプラットフォーム機能＝platform層とは別の扱い）。
 * 有効にしたコンポジションが消えたら必ず元に戻ること。
 */
@Composable
expect fun KeepScreenOn(enabled: Boolean)
