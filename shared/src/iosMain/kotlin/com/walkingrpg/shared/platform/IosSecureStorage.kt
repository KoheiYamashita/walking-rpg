package com.walkingrpg.shared.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS の Keychain を使った [SecureStorage]。
 *
 * `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` を指定しているので、
 * **この端末から出ない＝iCloud／暗号化バックアップにも乗らない**（Androidの
 * backup rules による除外と同じ狙い）。
 *
 * TODO(#3/#20): iOS実機・シミュレータでの動作確認が未実施
 *  （動作検証はAndroid優先方針。ビルドと配線だけ先に通してある）。
 *  検証時は「機種変後はKeychainの項目が無く、セットアップをやり直せる」ことまで見る。
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosSecureStorage : SecureStorage {

    override fun get(key: String): String? = memScoped {
        val query = keychainQuery(key) { dictionary ->
            CFDictionaryAddValue(dictionary, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(dictionary, kSecMatchLimit, kSecMatchLimitOne)
        }
        try {
            val result = alloc<CFTypeRefVar>()
            if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) return@memScoped null
            val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
            NSString.create(data = data, encoding = NSUTF8StringEncoding) as String?
        } finally {
            CFRelease(query)
        }
    }

    override fun put(key: String, value: String) {
        // Keychain に「上書き」は無いので、消してから入れ直す。
        remove(key)

        val encoded = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val cfValue = CFBridgingRetain(encoded)
        val query = keychainQuery(key) { dictionary ->
            CFDictionaryAddValue(dictionary, kSecValueData, cfValue)
            CFDictionaryAddValue(
                dictionary,
                kSecAttrAccessible,
                kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            )
        }
        try {
            SecItemAdd(query, null)
        } finally {
            CFRelease(query)
            CFRelease(cfValue)
        }
    }

    override fun remove(key: String) {
        val query = keychainQuery(key)
        try {
            SecItemDelete(query)
        } finally {
            CFRelease(query)
        }
    }

    /**
     * `kSecClassGenericPassword` の共通クエリ。呼び出し側が [CFRelease] する。
     * 値は辞書側が retain するので、ブリッジで作った参照はここで手放す。
     */
    private fun keychainQuery(
        key: String,
        extras: (CFMutableDictionaryRef) -> Unit = {},
    ): CFDictionaryRef {
        val dictionary = CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            DICTIONARY_CAPACITY.convert(),
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )!!
        val cfService = CFBridgingRetain(SERVICE as NSString)
        val cfAccount = CFBridgingRetain(key as NSString)
        CFDictionaryAddValue(dictionary, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dictionary, kSecAttrService, cfService)
        CFDictionaryAddValue(dictionary, kSecAttrAccount, cfAccount)
        extras(dictionary)
        CFRelease(cfService)
        CFRelease(cfAccount)
        return dictionary
    }

    private companion object {
        const val SERVICE = "com.walkingrpg.app.secure"
        const val DICTIONARY_CAPACITY = 6
    }
}

/** iOSの非秘密設定は `NSUserDefaults`。バックアップに乗ってよい値だけを置く。 */
internal class IosAppSettings : AppSettings {

    private val defaults = NSUserDefaults.standardUserDefaults

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        if (defaults.objectForKey(key) == null) defaultValue else defaults.boolForKey(key)

    override fun putBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}
