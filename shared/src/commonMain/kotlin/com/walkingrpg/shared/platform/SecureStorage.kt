package com.walkingrpg.shared.platform

/**
 * 秘密の値（APIキー・自宅座標）を端末のセキュアストレージに置く境界。
 *
 * - Android：Android Keystore の鍵で暗号化して保存
 * - iOS：Keychain
 *
 * **OSのバックアップ・端末間移行に乗せない。** 実装側で除外設定を入れること
 * （Androidは backup rules、iOSは Keychain のアクセシビリティ属性）。
 * 鍵はKeystore/Keychainの外へ出ないので、バックアップに暗号文だけ乗っても
 * 復号できないが、そもそも運ばないほうが事故がない。
 *
 * 実装はプラットフォーム層に閉じ、上位層はこのインターフェースしか見ない。
 */
interface SecureStorage {

    /** 未保存なら `null`。復号に失敗した場合も `null`（鍵が失われたケース）。 */
    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)
}

/**
 * 秘密でない設定（フォーマット選択・ベースURL・モデル名・完了フラグ）の保存先。
 *
 * SharedPreferences / NSUserDefaults の薄い口。DBに入れるほどの構造がないので
 * キー・バリューで済ませる（設定が増えて構造が要るようになったら移す）。
 */
interface AppSettings {

    fun getString(key: String): String?

    fun putString(key: String, value: String)

    fun getBoolean(key: String, defaultValue: Boolean): Boolean

    fun putBoolean(key: String, value: Boolean)

    fun remove(key: String)
}
