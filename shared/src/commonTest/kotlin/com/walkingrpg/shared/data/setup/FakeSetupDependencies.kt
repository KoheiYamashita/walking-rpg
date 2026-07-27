package com.walkingrpg.shared.data.setup

import com.walkingrpg.shared.platform.AppSettings
import com.walkingrpg.shared.platform.SecureStorage

/**
 * セットアップまわりのテスト用フェイク。
 *
 * 実装（Keystore / SharedPreferences / Keychain）はプラットフォーム層に閉じているので、
 * Repositoryの振り分けロジックはインターフェース越しに検証できる。
 */
class FakeSecureStorage(
    private val values: MutableMap<String, String> = mutableMapOf(),
) : SecureStorage {

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    /** 「秘密がセキュアストレージ側に入ったか」を確かめるための覗き口。 */
    fun snapshot(): Map<String, String> = values.toMap()
}

class FakeAppSettings(
    private val values: MutableMap<String, Any> = mutableMapOf(),
) : AppSettings {

    override fun getString(key: String): String? = values[key] as? String

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        values[key] as? Boolean ?: defaultValue

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    fun snapshot(): Map<String, Any> = values.toMap()
}
