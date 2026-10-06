package com.music.bitchord.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * The login keychain, for generic passwords filed under a service and an account.
 *
 * Called through Security.framework directly rather than through `/usr/bin/security`, which only
 * takes a password to store as a command-line argument — where every other process could read it.
 */
internal object DesktopMacKeychain {

    @Suppress("FunctionName")
    private interface Security : Library {
        fun SecKeychainFindGenericPassword(
            keychainOrArray: Pointer?,
            serviceNameLength: Int,
            serviceName: ByteArray,
            accountNameLength: Int,
            accountName: ByteArray?,
            passwordLength: IntByReference?,
            passwordData: PointerByReference?,
            itemRef: PointerByReference?,
        ): Int

        fun SecKeychainAddGenericPassword(
            keychain: Pointer?,
            serviceNameLength: Int,
            serviceName: ByteArray,
            accountNameLength: Int,
            accountName: ByteArray,
            passwordLength: Int,
            passwordData: ByteArray,
            itemRef: PointerByReference?,
        ): Int

        fun SecKeychainItemModifyAttributesAndData(
            itemRef: Pointer,
            attrList: Pointer?,
            length: Int,
            data: ByteArray,
        ): Int

        fun SecKeychainItemFreeContent(attrList: Pointer?, data: Pointer?): Int

        fun SecKeychainItemDelete(itemRef: Pointer): Int
    }

    @Suppress("FunctionName")
    private interface CoreFoundation : Library {
        fun CFRelease(reference: Pointer)
    }

    private val security: Security? by lazy {
        if (!DesktopPlatform.isMac) return@lazy null
        runCatching { Native.load("Security", Security::class.java) }
            .onFailure { DesktopTrackLog.log("keychain unavailable: ${it.message}") }
            .getOrNull()
    }

    private val coreFoundation: CoreFoundation? by lazy {
        if (!DesktopPlatform.isMac) return@lazy null
        runCatching { Native.load("CoreFoundation", CoreFoundation::class.java) }.getOrNull()
    }

    val available: Boolean get() = security != null && coreFoundation != null

    /** The password filed under [service], for [account] or for any account when it is null. */
    fun find(service: String, account: String?): ByteArray? {
        val api = security ?: return null
        val serviceName = service.toByteArray(Charsets.UTF_8)
        val accountName = account?.toByteArray(Charsets.UTF_8)
        val length = IntByReference()
        val data = PointerByReference()
        val status = api.SecKeychainFindGenericPassword(
            null,
            serviceName.size,
            serviceName,
            accountName?.size ?: 0,
            accountName,
            length,
            data,
            null,
        )
        if (status != NO_ERROR) {
            if (status != ITEM_NOT_FOUND) DesktopTrackLog.log("keychain lookup for $service failed: OSStatus $status")
            return null
        }
        val pointer = data.value ?: return null
        return try {
            pointer.getByteArray(0, length.value)
        } finally {
            api.SecKeychainItemFreeContent(null, pointer)
        }
    }

    /** Files [secret] under [service] and [account], replacing whatever was there. */
    fun store(service: String, account: String, secret: ByteArray): Boolean {
        val api = security ?: return false
        val serviceName = service.toByteArray(Charsets.UTF_8)
        val accountName = account.toByteArray(Charsets.UTF_8)
        val existing = item(api, serviceName, accountName)
        val status = if (existing != null) {
            try {
                api.SecKeychainItemModifyAttributesAndData(existing, null, secret.size, secret)
            } finally {
                release(existing)
            }
        } else {
            api.SecKeychainAddGenericPassword(
                null,
                serviceName.size,
                serviceName,
                accountName.size,
                accountName,
                secret.size,
                secret,
                null,
            )
        }
        if (status != NO_ERROR) DesktopTrackLog.log("keychain store for $service failed: OSStatus $status")
        return status == NO_ERROR
    }

    /** Deletes what is filed under [service] and [account]; true when nothing is left there. */
    fun remove(service: String, account: String): Boolean {
        val api = security ?: return false
        val existing = item(api, service.toByteArray(Charsets.UTF_8), account.toByteArray(Charsets.UTF_8))
            ?: return true
        return try {
            api.SecKeychainItemDelete(existing) == NO_ERROR
        } finally {
            release(existing)
        }
    }

    /** The item reference itself, which the caller releases. */
    private fun item(api: Security, serviceName: ByteArray, accountName: ByteArray): Pointer? {
        val reference = PointerByReference()
        val status = api.SecKeychainFindGenericPassword(
            null,
            serviceName.size,
            serviceName,
            accountName.size,
            accountName,
            null,
            null,
            reference,
        )
        return if (status == NO_ERROR) reference.value else null
    }

    private fun release(reference: Pointer) {
        coreFoundation?.CFRelease(reference)
    }

    private const val NO_ERROR = 0

    /** errSecItemNotFound: the ordinary answer for a session that was never stored. */
    private const val ITEM_NOT_FOUND = -25300
}
