package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.reanime

import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object FlixcloudDecryptor {

    private fun sha256Hex(input: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(input: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(input)
        return hash.joinToString("") { "%02x".format(it) }
    }

    data class FieldMapping(
        val keyField: String,
        val ivField: String,
        val containerName: String,
        val arrayName: String,
        val objectName: String,
        val tokenField: String,
        val keyFrag2Field: String,
    )

    fun resolveFieldMapping(seed: String): FieldMapping {
        var e = seed
        for (i in 0 until 3) e = sha256Hex(e + i.toString())
        var o = e
        for (i in 0 until 3) o = sha256Hex(o + i.toString())
        return FieldMapping(
            keyField = "kf_${e.substring(8, 16)}",
            ivField = "ivf_${e.substring(16, 24)}",
            containerName = "cd_${e.substring(24, 32)}",
            arrayName = "ad_${e.substring(32, 40)}",
            objectName = "od_${e.substring(40, 48)}",
            tokenField = "${e.substring(48, 64)}_${e.substring(56, 64)}",
            keyFrag2Field = "${o.substring(0, 16)}_${o.substring(16, 24)}",
        )
    }

    /**
     * Pure-Kotlin reimplementation of the flixcloud WASM _r function.
     * Combines 3 byte-array key fragments with per-byte transformations.
     */
    private fun wasmCombine(
        frag1: ByteArray,
        frag2: ByteArray,
        frag3: ByteArray,
        seed: Int,
    ): ByteArray {
        val len = frag1.size
        val out = ByteArray(len)
        for (i in 0 until len) {
            var t = (frag1[i].toInt() and 0xFF) xor
                (frag2[i].toInt() and 0xFF) xor
                (frag3[i].toInt() and 0xFF)
            t = (t + 214) and 0xFF
            t = ((t shl 6) and 0xFF) or (t shr 2)   // rotl8(6)
            t = t xor 240
            t = (t - 238) and 0xFF
            t = (t - 138) and 0xFF
            t = ((t shl 6) and 0xFF) or (t shr 2)   // rotl8(6)
            t = (t + 179) and 0xFF
            t = t xor ((i + 1 + seed) and 0xFF)
            out[i] = t.toByte()
        }
        return out
    }

    /**
     * Raw-bytes PBKDF2-HMAC-SHA256.
     * Java's PBEKeySpec mangles bytes >127 via char encoding,
     * so we implement it directly to match Web Crypto's importKey("raw",…).
     */
    private fun pbkdf2(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int,
    ): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(password, "HmacSHA256"))
        val result = ByteArray(keyLengthBytes)
        var offset = 0
        var block = 1
        while (offset < keyLengthBytes) {
            val input = salt + byteArrayOf(
                (block shr 24).toByte(),
                (block shr 16).toByte(),
                (block shr 8).toByte(),
                block.toByte(),
            )
            var u = hmac.doFinal(input)
            val xored = u.copyOf()
            for (iter in 1 until iterations) {
                u = hmac.doFinal(u)
                for (j in xored.indices) {
                    xored[j] = (xored[j].toInt() xor u[j].toInt()).toByte()
                }
            }
            val n = minOf(xored.size, keyLengthBytes - offset)
            System.arraycopy(xored, 0, result, offset, n)
            offset += n
            block++
        }
        return result
    }

    /**
     * Full decryption pipeline.
     *
     * @param seed          obfuscation_seed from embed page data
     * @param cryptoData    obfuscated_crypto_data JsonObject
     * @param pageData      full embed page data map (all key-value pairs)
     * @param apiResponse   JSON from GET /api/m3u8/{tokenRef}
     * @return decrypted m3u8 URL
     */
    fun decrypt(
        seed: String,
        cryptoData: JsonObject,
        pageData: Map<String, String>,
        apiResponse: JsonObject,
    ): String {
        // 1. Resolve field names
        val m = resolveFieldMapping(seed)

        // 2. Navigate obfuscated_crypto_data → frag1 + IV
        val container = cryptoData[m.containerName]?.jsonObject
            ?: throw Exception("cd container missing")
        val arr = container[m.arrayName]?.jsonArray
            ?: throw Exception("ad array missing")
        val obj = arr.firstOrNull()?.jsonObject?.get(m.objectName)?.jsonObject
            ?: throw Exception("od object missing")
        val frag1B64 = obj[m.keyField]?.jsonPrimitive?.content
            ?: throw Exception("kf missing")
        val ivB64 = obj[m.ivField]?.jsonPrimitive?.content
            ?: throw Exception("ivf missing")

        // 3. Key fragment 2 from page data
        val frag2B64 = pageData[m.keyFrag2Field]
            ?: throw Exception("keyFrag2 missing")

        // 4. Token ref → find in page data
        val tokenRef = pageData[m.tokenField]
            ?: throw Exception("tokenRef missing")

        // 5. API response fields
        val vidKey = sha256Hex(tokenRef + "vid").substring(0, 10)
        val keyKey = sha256Hex(tokenRef + "key").substring(0, 10)
        val encVideoB64 = apiResponse[vidKey]?.jsonPrimitive?.content
            ?: throw Exception("encrypted video missing")
        val frag3B64 = apiResponse[keyKey]?.jsonPrimitive?.content
            ?: throw Exception("frag3 missing")

        // 6. WASM combine
        val frag1 = Base64.decode(frag1B64, Base64.DEFAULT)
        val frag2 = Base64.decode(frag2B64, Base64.DEFAULT)
        val frag3 = Base64.decode(frag3B64, Base64.DEFAULT)
        val seedInt = seed.substring(0, 8).toLong(16).toInt()
        val combined = wasmCombine(frag1, frag2, frag3, seedInt)

        // 7. PBKDF2
        val derived = pbkdf2(
            password = combined,
            salt = seed.toByteArray(Charsets.UTF_8),
            iterations = 1000,
            keyLengthBytes = 32,
        )

        // 8. XOR with seed (cycling)
        for (i in derived.indices) {
            derived[i] = (derived[i].toInt() xor seed[i % seed.length].code).toByte()
        }

        // 9. Final SHA-256 → AES key
        val aesKey = MessageDigest.getInstance("SHA-256").digest(derived)

        // 10. AES-256-CBC decrypt
        val iv = Base64.decode(ivB64, Base64.DEFAULT)
        val ciphertext = Base64.decode(encVideoB64, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(aesKey, "AES"),
            IvParameterSpec(iv),
        )
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8).trim()
    }
}
