package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.allanime

import android.content.SharedPreferences
import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.OkHttpClient
import javax.crypto.spec.SecretKeySpec

@Serializable
class AaCryptoBootstrap(
    val epoch: Long,
    val partB: String,
)

@Serializable
class AaApiError(
    val errors: List<GraphQlError>? = null,
) {
    @Serializable
    class GraphQlError(
        val extensions: Extensions? = null,
    ) {
        @Serializable
        class Extensions(
            val code: String? = null,
        )
    }
}

class AllAnimeKeyManager(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences,
    private val siteUrl: String,
    private val streamHash: String,
) {
    class Material(
        val key: SecretKeySpec,
        val epoch: Long,
        val expiresAt: Long,
        val fetchedAt: Long,
    )

    @Volatile
    private var cachedMaterial: Material? = null
    private val materialMutex = Mutex()

    @Volatile
    private var appEntryUrl: String? = null

    private val maskMutex = Mutex()

    suspend fun material(forceRefresh: Boolean = false): Material {
        val enteredAt = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedMaterial?.let { if (!it.isExpired()) return it }
        }

        return materialMutex.withLock {
            cachedMaterial?.let {
                if (it.fetchedAt > enteredAt || (!forceRefresh && !it.isExpired())) return@withLock it
            }

            val html = client.newCall(GET("$siteUrl/", headers))
                .awaitSuccess().bodyString()

            APP_ENTRY_REGEX.find(html)?.groupValues?.get(1)?.let { appEntryUrl = it }

            val json = AA_CRYPTO_REGEX.find(html)?.groupValues?.get(1)
                ?: throw Exception("Unable to obtain AllAnime crypto material")

            val bootstrap = json.parseAs<AaCryptoBootstrap>()
            val partB = runCatching { Base64.decode(bootstrap.partB, Base64.DEFAULT) }
                .getOrElse { throw Exception("AllAnime crypto material changed") }
            require(partB.size >= 32) { "AllAnime crypto material changed" }

            val now = System.currentTimeMillis()
            Material(
                key = AllAnimeCrypto.deriveKey(mask(), partB),
                epoch = bootstrap.epoch,
                expiresAt = now + MATERIAL_TTL_MS,
                fetchedAt = now,
            ).also { cachedMaterial = it }
        }
    }

    fun aaReq(material: Material): String =
        AllAnimeCrypto.buildAaReq(material.key, material.epoch, CLIENT_BUILD_ID, streamHash)

    fun decrypt(tobeparsed: String, material: Material): String? =
        AllAnimeCrypto.decrypt(tobeparsed, material.key)

    fun invalidate() {
        cachedMaterial = null
    }

    fun isCryptoError(body: String): Boolean =
        runCatching { body.parseAs<AaApiError>().errors }.getOrNull()
            ?.any { it.extensions?.code?.startsWith("AA_CRYPTO") == true } == true

    suspend fun healMask(): Boolean = resolveMask() != null

    private suspend fun mask(): ByteArray {
        val stored = preferences.getString(PREF_MASK_KEY, "") ?: ""
        return stored.takeIf { it.length == MASK_HEX_LENGTH }
            ?.let(AllAnimeCrypto::hexToBytesOrNull)
            ?: resolveMask()
            ?: throw Exception("Unable to obtain AllAnime crypto material")
    }

    private suspend fun resolveMask(): ByteArray? = maskMutex.withLock {
        val appUrl = appEntryUrl ?: return@withLock null
        val chunkBase = appUrl.substringBeforeLast("/entry/", "") + "/chunks/"
        if (!chunkBase.startsWith("http")) return@withLock null

        val appJs = runCatching {
            client.newCall(GET(appUrl, headers)).awaitSuccess().bodyString()
        }.getOrNull() ?: return@withLock null

        val chunkNames = CHUNK_REF_REGEX.findAll(appJs)
            .map { it.groupValues[1] }
            .distinct()
            .take(MAX_MASK_CHUNKS)

        val stored = preferences.getString(PREF_MASK_KEY, "") ?: ""

        for (name in chunkNames) {
            val body = runCatching {
                client.newCall(GET(chunkBase + name, headers)).awaitSuccess().bodyString()
            }.getOrNull() ?: continue

            if (!body.contains(CRYPTO_CHUNK_MARKER)) continue

            val hex = HEX64_REGEX.findAll(body)
                .map { it.value }
                .firstOrNull {
                    !it.equals(streamHash, ignoreCase = true) &&
                        !it.equals(stored, ignoreCase = true)
                }
                ?: continue

            val bytes = AllAnimeCrypto.hexToBytesOrNull(hex) ?: continue
            preferences.edit().putString(PREF_MASK_KEY, hex).apply()
            return@withLock bytes
        }
        null
    }

    private fun Material.isExpired(): Boolean = System.currentTimeMillis() >= expiresAt

    companion object {
        private const val CLIENT_BUILD_ID = "12"
        private const val PREF_MASK_KEY = "allanime_client_mask_cache"
        private const val MAX_MASK_CHUNKS = 40
        private const val MASK_HEX_LENGTH = 64
        private const val MATERIAL_TTL_MS = 6 * 60 * 60 * 1000L

        private val AA_CRYPTO_REGEX = Regex("""window\.__aaCrypto\s*=\s*(\{[^{}]*\})""")
        private val APP_ENTRY_REGEX = Regex("""import\("([^"]*/entry/app\.[^"]*\.js)"\)""")
        private val CHUNK_REF_REGEX = Regex("""\.\./chunks/([A-Za-z0-9_-]+\.js)""")
        private const val CRYPTO_CHUNK_MARKER = "aaReq"
        private val HEX64_REGEX = Regex("""(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])""")
    }
}
