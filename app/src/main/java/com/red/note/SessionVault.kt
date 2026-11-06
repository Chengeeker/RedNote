package com.red.note

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SessionVault(context: Context) {
    private val preferences = context.getSharedPreferences("rednote_session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("rednote.session.v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("rednote.session.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): JSONObject? {
        val encoded=preferences.getString("session",null) ?: return null
        require(encoded.length<=1024*1024) {"会话存储异常，请重新登录"}
        val bytes=Base64.decode(encoded,Base64.NO_WRAP)
        require(bytes.size>28) { "会话存储损坏，请重新登录" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
    }
    fun save(session: JSONObject) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val bytes=cipher.iv+cipher.doFinal(session.toString().toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("session",Base64.encodeToString(bytes,Base64.NO_WRAP)).commit()) { "无法保存登录会话" }
    }
    fun clear() { check(preferences.edit().clear().commit()) { "无法清除登录会话" } }
}
