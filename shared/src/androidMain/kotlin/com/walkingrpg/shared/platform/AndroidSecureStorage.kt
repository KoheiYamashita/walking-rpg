package com.walkingrpg.shared.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 裏付けの [SecureStorage]。
 *
 * ## なぜ EncryptedSharedPreferences を使わないか
 * `androidx.security:security-crypto` は 1.1.0-alpha06 以降メンテナンスが止まり
 * 非推奨枠にある。新規に依存を足す先としては選びづらいので、同等のことを
 * 標準APIだけでやる：**AES/GCM の鍵を Android Keystore に生成し（鍵は端末外へ
 * 出ない）、暗号文だけを SharedPreferences に置く**。Keystore に鍵がある以上、
 * ファイルを吸い出しても復号できない、という保証は EncryptedSharedPreferences と同じ。
 *
 * ## バックアップ
 * 保存先の `walking_rpg_secrets.xml` は
 * `composeApp/src/androidMain/res/xml/backup_rules.xml` と
 * `data_extraction_rules.xml` で **バックアップ・端末間移行の対象から除外**してある。
 * Keystoreの鍵は端末を移れないので、運んでも復号できない暗号文が残るだけ。
 *
 * 復号に失敗した値（鍵が失われた・データが壊れた）は `null` を返して捨てる。
 * 呼び出し側からは「未設定」と同じに見え、セットアップをやり直せばよい状態になる。
 */
internal class AndroidSecureStorage(context: Context) : SecureStorage {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            decrypt(stored)
        } catch (error: Exception) {
            // 鍵の消失（端末の画面ロック解除方法の変更等）やデータ破損。
            // ここで落とすとアプリが起動不能になるので、未設定として扱う。
            remove(key)
            null
        }
    }

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, encrypt(value)).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        // IVは毎回ランダム生成される。秘密ではないので暗号文と一緒に置く。
        return "${cipher.iv.encodeBase64()}$SEPARATOR${cipherText.encodeBase64()}"
    }

    private fun decrypt(stored: String): String {
        val separatorIndex = stored.indexOf(SEPARATOR)
        require(separatorIndex > 0) { "保存形式が不正です" }
        val iv = stored.substring(0, separatorIndex).decodeBase64()
        val cipherText = stored.substring(separatorIndex + 1).decodeBase64()

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherText).toString(Charsets.UTF_8)
    }

    /** Keystore内の鍵。無ければ作る（鍵そのものはKeystoreの外に出てこない）。 */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // 画面ロック解除を必須にはしない：記録中や起動直後にキーを読めないと
                // 生成キューが止まる。端末外に鍵が出ないことは変わらない。
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.decodeBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        /** backup rules の `path` と一致させること（変えるなら両方直す）。 */
        const val PREFS_NAME = "walking_rpg_secrets"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "walking_rpg_secure_storage"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val SEPARATOR = ':'
    }
}

/** 秘密でない設定の保存。こちらはバックアップに乗せてよい（再入力の手間を減らす）。 */
internal class AndroidAppSettings(context: Context) : AppSettings {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private companion object {
        const val PREFS_NAME = "walking_rpg_settings"
    }
}
