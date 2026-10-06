package com.music.bitchord.desktop

import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopMacKeychainTest {

    @Test
    fun `a secret round trips through the login keychain and is gone after removal`() {
        if (!DesktopPlatform.isMac) return
        val service = "bitchord-test"
        val account = "round-trip-${System.nanoTime()}"
        // A CI runner's keychain can be locked with nobody to unlock it; there is nothing to test.
        if (!DesktopMacKeychain.store(service, account, "first".toByteArray())) return
        try {
            assertContentEquals("first".toByteArray(), DesktopMacKeychain.find(service, account))
            DesktopMacKeychain.store(service, account, "second".toByteArray())
            assertContentEquals("second".toByteArray(), DesktopMacKeychain.find(service, account))
        } finally {
            DesktopMacKeychain.remove(service, account)
        }
        assertNull(DesktopMacKeychain.find(service, account))
    }

    @Test
    fun `a macOS Chromium cookie decrypts with its keychain password`() {
        val password = "c2FmZS1zdG9yYWdlLXBhc3N3b3Jk".toByteArray()
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(String(password).toCharArray(), "saltysalt".toByteArray(), 1003, 128))
            .encoded
        val body = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16) { ' '.code.toByte() }))
            doFinal("SAPISID-value".toByteArray())
        }
        val sealed = "v10".toByteArray() + body

        assertEquals("SAPISID-value", DesktopBrowserCookies.decryptMac(sealed, "", password))
        assertNull(DesktopBrowserCookies.decryptMac(sealed, "", null))
    }
}
