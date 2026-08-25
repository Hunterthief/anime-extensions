package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.mkissa

import android.content.SharedPreferences
import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.delegate
import keiyoushi.utils.parallelCatchingMapNotNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toHex
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object MKissaCrypto {
    private const val TAG_LENGTH = 128
    private const val HASH_ALGO = "SHA-256"
    private const val HMAC_ALGO = "HmacSHA256"
    private const val KEY_TYPE = "AES"
    private const val CIPHER_ALGO = "AES/GCM/NoPadding"
    private const val LEGACY_SECRET = "Xot36i3lK3"
    private const val KEY_SIZE = 32
    const val SEED_COUNT = 4
    private const val SEED_SIZE = KEY_SIZE / SEED_COUNT
    private const val IV_SIZE = 12
    private const val HEADER_SIZE = 1 + IV_SIZE
    private const val WINDOW_MS = 5 * 60 * 1000L
    private const val EPOCH_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L
    private const val EPOCH_GRACE_MS = 24 * 60 * 60 * 1000L

    fun sha256Hex(value: String): String = MessageDigest.getInstance(HASH_ALGO)
        .digest(value.toByteArray(Charsets.UTF_8))
        .toHex()

    private data class MaskParams(val saltMul: Int, val saltAdd: Int, val fragMul: Int, val fragAdd: Int)

    private val MASK_PARAMS = listOf(
        MaskParams(250, 54, 16, 217),
        MaskParams(211, 222, 200, 176),
        MaskParams(17, 31, 41, 7),
    )

    fun maskCandidates(buildId: String, seeds: List<String>): List<ByteArray> {
        if (buildId.isEmpty() || seeds.size != SEED_COUNT) return emptyList()
        val candidates = mutableListOf<ByteArray>()
        for (params in MASK_PARAMS) {
            val stream = ByteArray(KEY_SIZE) { i ->
                (buildId[i % buildId.length].code xor ((i * params.saltMul + params.saltAdd) and 0xFF)).toByte()
            }

            val mask = ByteArray(KEY_SIZE)
            var ok = true
            seeds.forEachIndexed { index, seed ->
                val bytes = runCatching { Base64.decode(seed, Base64.DEFAULT) }.getOrNull()
                if (bytes == null || bytes.size < SEED_SIZE) {
                    ok = false
                    return@forEachIndexed
                }
                val base = index * SEED_SIZE
                for (offset in 0 until SEED_SIZE) {
                    mask[base + offset] = (
                        (bytes[offset].toInt() and 0xFF) xor
                            (stream[base + offset].toInt() and 0xFF) xor
                            ((index * params.fragMul + offset * params.fragAdd) and 0xFF)
                        ).toByte()
                }
            }
            if (ok && mask.any { it != 0.toByte() }) candidates.add(mask)
        }
        return candidates
    }

    fun deriveMask(buildId: String, seeds: List<String>): ByteArray? = maskCandidates(buildId, seeds).firstOrNull()

    fun deriveKey(mask: ByteArray, partB: ByteArray): SecretKeySpec {
        val keyBytes = ByteArray(KEY_SIZE) { i ->
            ((partB[i].toInt() and 0xFF) xor (mask[i % mask.size].toInt() and 0xFF)).toByte()
        }
        return SecretKeySpec(keyBytes, KEY_TYPE)
    }

    fun bootTokenNew(
        mask: ByteArray,
        buildId: String,
        epoch: Long,
        keyGroup: String,
        refererHost: String,
        lane: String,
    ): String {
        val inner = hmac(mask, "4X2PsZc2r:$buildId")
        val message = listOf(keyGroup, refererHost, lane, buildId, epoch.toString()).joinToString(".")
        return hmac(inner, message).toHex()
    }

    fun bootTokenPrevious(
        mask: ByteArray,
        buildId: String,
        epoch: Long,
        keyGroup: String,
        refererHost: String,
        lane: String,
    ): String {
        val inner = hmac(mask, "kNk1YgwkSI:$buildId")
        val message = listOf(epoch.toString(), keyGroup, refererHost, buildId, lane).joinToString(".")
        return hmac(inner, message).toHex()
    }

    fun bootTokenLegacy(
        mask: ByteArray,
        buildId: String,
        epoch: Long,
        keyGroup: String,
        refererHost: String,
        lane: String,
    ): String {
        val inner = hmac(mask, "aa-boot:$buildId")
        val message = buildString {
            append(buildId).append(':').append(keyGroup).append(':')
            append(refererHost).append(':').append(epoch)
            if (lane.isNotEmpty()) append(':').append(lane)
        }
        return hmac(inner, message).toHex()
    }

    fun bootTokenCandidates(
        mask: ByteArray,
        buildId: String,
        epoch: Long,
        keyGroup: String,
        refererHost: String,
        lane: String,
    ): List<String> = listOf(
        bootTokenNew(mask, buildId, epoch, keyGroup, refererHost, lane),
        bootTokenPrevious(mask, buildId, epoch, keyGroup, refererHost, lane),
        bootTokenLegacy(mask, buildId, epoch, keyGroup, refererHost, lane),
    )

    fun epochCandidates(now: Long = System.currentTimeMillis()): List<Long> {
        val current = now / EPOCH_WINDOW_MS
        val inGrace = now - current * EPOCH_WINDOW_MS < EPOCH_GRACE_MS && current > 0
        return if (inGrace) listOf(current - 1, current) else listOf(current)
    }

    fun skewedEpochCandidates(now: Long = System.currentTimeMillis()): List<Long> {
        val current = now / EPOCH_WINDOW_MS
        return listOf(current + 1, current - 1).filter { it > 0 } - epochCandidates(now).toSet()
    }

    fun buildAaReq(key: SecretKeySpec, epoch: Long, buildId: String, queryHash: String, lane: String): String {
        val ts = System.currentTimeMillis() / WINDOW_MS * WINDOW_MS
        val iv = MessageDigest.getInstance(HASH_ALGO)
            .digest("$epoch:$buildId:$queryHash:$ts:$lane".toByteArray(Charsets.UTF_8))
            .copyOfRange(0, IV_SIZE)
        val payload = MKissaAaReqPayload(v = 1, ts = ts, epoch = epoch, buildId = buildId, qh = queryHash, k = lane).toJsonString()
        val cipher = Cipher.getInstance(CIPHER_ALGO)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(HEADER_SIZE + ciphertext.size)
        blob[0] = 1
        System.arraycopy(iv, 0, blob, 1, IV_SIZE)
        System.arraycopy(ciphertext, 0, blob, HEADER_SIZE, ciphertext.size)
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    fun decrypt(base64Payload: String, materialKey: SecretKeySpec): String? {
        val blob = runCatching { Base64.decode(base64Payload, Base64.DEFAULT) }.getOrNull() ?: return null
        if (blob.size < HEADER_SIZE) return null
        val version = blob[0].toInt() and 0xFF
        val iv = blob.sliceArray(1 until HEADER_SIZE)
        val encryptedData = blob.sliceArray(HEADER_SIZE until blob.size)
        for (key in listOf(materialKey, legacyKey(version))) {
            runCatching {
                val cipher = Cipher.getInstance(CIPHER_ALGO)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
                String(cipher.doFinal(encryptedData), Charsets.UTF_8)
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun hmac(key: ByteArray, message: String): ByteArray = Mac.getInstance(HMAC_ALGO).run {
        init(SecretKeySpec(key, HMAC_ALGO))
        doFinal(message.toByteArray(Charsets.UTF_8))
    }

    private fun legacyKey(version: Int): SecretKeySpec {
        val bytes = MessageDigest.getInstance(HASH_ALGO)
            .digest("$LEGACY_SECRET:v$version".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(bytes, KEY_TYPE)
    }
}

object MKissaBundle {
    class BuildInfo(val buildId: String, val seeds: List<String>)

    fun parse(js: String): BuildInfo? {
        BUILD_ID_REGEX.find(js)?.groupValues?.get(1)?.let { legacyId ->
            extractSeeds(js)?.let { seeds -> return BuildInfo(legacyId, seeds) }
        }

        val (tables, bases, aliases) = decodersFrom(js)
        val buildId = extractBuildIdNew(js, tables, bases, aliases) ?: return null
        val seeds = extractSeedsWithTables(js, tables, bases, aliases) ?: return null
        return BuildInfo(buildId, seeds)
    }

    private fun extractBuildIdNew(
        js: String,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
    ): String? {
        val maskDefaultVar = Regex("""function\s+($IDENT)\s*\(\s*\w+\s*=\s*(\w+)\s*[,)]""").findAll(js)
            .mapNotNull { it.groupValues[2].takeIf(String::isNotEmpty) }
            .firstOrNull { varName ->
                Regex("""\b${Regex.escape(varName)}\s*=\s*$CALL_PATTERN""").containsMatchIn(js)
            }

        val candidates = mutableListOf<String>()

        if (maskDefaultVar != null) {
            val assignRegex = Regex("""\b${Regex.escape(maskDefaultVar)}\s*=\s*($CALL_PATTERN)""")
            assignRegex.findAll(js).forEach { m ->
                candidates.add(m.groupValues[1])
            }
        }

        val sfIndex = js.indexOf("sf=")
        if (sfIndex != -1) {
            val windowStart = (sfIndex - 2000).coerceAtLeast(0)
            val window = js.substring(windowStart, sfIndex)
            val assignRegex = Regex("""\b\w+\s*=\s*($CALL_PATTERN)\s*(?:,|;|\n)""")
            assignRegex.findAll(window).forEach { m ->
                val call = m.groupValues[1]
                if (!call.contains("+")) {
                    candidates.add(call)
                }
            }
        }

        if (candidates.isEmpty()) {
            val assignRegex = Regex("""\b\w+\s*=\s*($CALL_PATTERN)\b""")
            assignRegex.findAll(js).forEach { m ->
                val call = m.groupValues[1]
                if (!call.contains("+")) candidates.add(call)
            }
        }

        for (call in candidates) {
            val aliasName = CALL_REGEX.find(call)?.groupValues?.get(1) ?: continue
            val alias = aliases[aliasName] ?: continue
            val base = bases[alias.base] ?: continue
            val table = tables[base.table] ?: continue

            for (rotation in table.indices) {
                val decoded = resolve(call, rotation, tables, bases, aliases) ?: continue
                if (decoded.matches(BUILD_ID_DIGITS_REGEX)) {
                    val seedsOk = extractSeedsWithTables(js, tables, bases, aliases, forcedRotation = rotation) != null
                    if (seedsOk) return decoded
                }
            }
        }

        for (match in CALL_REGEX.findAll(js)) {
            val call = match.value
            if (call.contains("+")) continue
            val aliasName = match.groupValues[1]
            val alias = aliases[aliasName] ?: continue
            val base = bases[alias.base] ?: continue
            val table = tables[base.table] ?: continue
            for (rotation in table.indices) {
                val decoded = resolve(call, rotation, tables, bases, aliases) ?: continue
                if (decoded.matches(BUILD_ID_DIGITS_REGEX) && decoded.length in 2..8) {
                    val before = js.substring((match.range.first - 20).coerceAtLeast(0), match.range.first)
                    if (before.contains("sf=") || before.contains("kd=")) continue
                    if (extractSeedsWithTables(js, tables, bases, aliases, forcedRotation = rotation) == null) continue
                    return decoded
                }
            }
        }
        return null
    }

    private class Base(val table: String, val offset: Int)
    private class Alias(val base: String, val argIndex: Int, val delta: Int)

    private fun decodersFrom(js: String): Triple<Map<String, List<String>>, Map<String, Base>, Map<String, Alias>> {
        val tables = readTables(js)
        val bases = BASE_DECODER_REGEX.findAll(js).associate { m ->
            m.groupValues[1] to Base(m.groupValues[4], fold(m.groupValues[3]))
        }
        val aliases = buildMap {
            bases.keys.forEach { put(it, Alias(it, 0, 0)) }
            ALIAS_DECODER_REGEX.findAll(js).forEach { m ->
                val (name, firstParam, _, callee, arg, delta) = m.destructured
                if (callee !in bases) return@forEach
                put(name, Alias(callee, if (arg == firstParam) 0 else 1, if (delta.isEmpty()) 0 else fold(delta)))
            }
        }
        return Triple(tables, bases, aliases)
    }

    private fun extractSeeds(js: String): List<String>? {
        val (tables, bases, aliases) = decodersFrom(js)
        return extractSeedsWithTables(js, tables, bases, aliases)
    }

    private fun extractSeedsWithTables(
        js: String,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
        forcedRotation: Int? = null,
    ): List<String>? {
        for (match in SEED_ARRAY_REGEX.findAll(js)) {
            val calls = CALL_REGEX.findAll(match.groupValues[1]).map(MatchResult::value).toList()
            if (calls.size != MKissaCrypto.SEED_COUNT * 2) continue

            val table = CALL_REGEX.find(calls.first())
                ?.let { aliases[it.groupValues[1]] }
                ?.let { tables[bases[it.base]?.table] }
                ?: continue

            if (forcedRotation != null) {
                seedsAt(calls, forcedRotation, tables, bases, aliases)?.let { return it }
                continue
            }

            val matches = table.indices.mapNotNull { rotation ->
                seedsAt(calls, rotation, tables, bases, aliases)
            }
            matches.singleOrNull()?.let { return it }
        }
        return null
    }

    private fun seedsAt(
        calls: List<String>,
        rotation: Int,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
    ): List<String>? {
        val seeds = calls.chunked(2).mapNotNull { (first, second) ->
            val a = resolve(first, rotation, tables, bases, aliases) ?: return@mapNotNull null
            val b = resolve(second, rotation, tables, bases, aliases) ?: return@mapNotNull null
            (a + b).takeIf(SEED_REGEX::matches)
        }
        return seeds.takeIf { it.size == MKissaCrypto.SEED_COUNT }
    }

    private fun resolve(
        call: String,
        rotation: Int,
        tables: Map<String, List<String>>,
        bases: Map<String, Base>,
        aliases: Map<String, Alias>,
    ): String? {
        val match = CALL_REGEX.matchEntire(call) ?: return null
        val alias = aliases[match.groupValues[1]] ?: return null
        val base = bases[alias.base] ?: return null
        val table = tables[base.table]?.takeIf { it.isNotEmpty() } ?: return null

        val args = listOfNotNull(
            match.groupValues[2].toIntOrNull(),
            match.groupValues[3].toIntOrNull(),
        )
        val arg = args.getOrNull(alias.argIndex) ?: return null

        val index = arg + alias.delta - base.offset + rotation
        return table[((index % table.size) + table.size) % table.size]
    }

    private fun readTables(js: String): Map<String, List<String>> = buildMap {
        for (match in TABLE_HEAD_REGEX.findAll(js)) {
            readStringArray(js, match.range.last)?.let { put(match.groupValues[1], it) }
        }
    }

    private fun readStringArray(js: String, open: Int): List<String>? {
        val items = mutableListOf<String>()
        var i = open + 1
        while (i < js.length) {
            when (val c = js[i]) {
                ']' -> return items
                ',', ' ' -> i++
                '"', '\'' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < js.length && js[i] != c) {
                        if (js[i] == '\\') {
                            sb.append(js[i + 1])
                            i += 2
                        } else {
                            sb.append(js[i])
                            i++
                        }
                    }
                    if (i >= js.length) return null
                    i++
                    items.add(sb.toString())
                }
                else -> return null
            }
        }
        return null
    }

    private fun fold(expression: String): Int {
        var total = 0
        for (term in TERM_REGEX.findAll(expression.replace(" ", "")).map(MatchResult::value)) {
            var sign = 1
            var body = term
            while (body.startsWith('+') || body.startsWith('-')) {
                if (body.startsWith('-')) sign = -sign
                body = body.substring(1)
            }
            var value = parseFactor(sign, body.substringBefore('*')) ?: return 0
            val rest = body.substringAfter('*', "")
            if (rest.isNotEmpty()) {
                for (factor in rest.split('*')) value *= parseFactor(1, factor) ?: return 0
            }
            total += value
        }
        return total
    }

    private fun parseFactor(sign: Int, factor: String): Int? {
        var negative = sign < 0
        var digits = factor
        while (digits.startsWith('+') || digits.startsWith('-')) {
            if (digits.startsWith('-')) negative = !negative
            digits = digits.substring(1)
        }
        val magnitude = digits.toLongOrNull() ?: return null
        val signed = if (negative) -magnitude else magnitude
        return if (signed in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) signed.toInt() else null
    }

    private val BUILD_ID_REGEX = Regex("""!==\s*["']string["']\s*\?\s*["'](\d+)["']\s*:\s*["']["']""")
    private val BUILD_ID_DIGITS_REGEX = Regex("""\d{2,10}""")
    private val IDENT = """[${'$'}A-Za-z0-9_]+"""
    private val TABLE_HEAD_REGEX = Regex("""function ($IDENT)\(\)\s*\{\s*(?:const|let|var)\s+$IDENT\s*=\s*\[""")
    private val BASE_DECODER_REGEX = Regex("""function ($IDENT)\(($IDENT)(?:,$IDENT)*\)\{return \2=\2-\(?([-\d+*\s]+?)\)?,($IDENT)\(\)\[\2\]\}""")
    private val ALIAS_DECODER_REGEX = Regex("""function ($IDENT)\(($IDENT),($IDENT)\)\{return ($IDENT)\(($IDENT)((?:[-+][\d+*\s-]+)?)\)\}""")
    private val CALL_PATTERN = """($IDENT)\(\s*(-?\d+)\s*(?:,\s*(-?\d+)\s*)?\)"""
    private val CALL_REGEX = Regex(CALL_PATTERN)
    private val SEED_ARRAY_REGEX = Regex("""=\[((?:$CALL_PATTERN\+$CALL_PATTERN,){3}$CALL_PATTERN\+$CALL_PATTERN)]""")
    private val SEED_REGEX = Regex("""[A-Za-z0-9+/]{11}=""")
    private val TERM_REGEX = Regex("""[-+]*\d+(?:\*[-+]*\d+)*""")
}

class MKissaKeyManager(
    private val client: OkHttpClient,
    private val headers: Headers,
    preferences: SharedPreferences,
    private val siteUrl: String,
    private val apiUrl: String,
) {
    class Material(
        val key: SecretKeySpec,
        val epoch: Long,
        val buildId: String,
        val expiresAt: Long,
        val fetchedAt: Long,
    )

    @Volatile
    private var cachedMaterial: Material? = null
    private val materialMutex = Mutex()
    private var storedBuild by preferences.delegate(PREF_BUILD_KEY, "")

    suspend fun material(forceRefresh: Boolean = false): Material {
        val enteredAt = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedMaterial?.let { if (!it.isExpired()) return it }
        }

        return materialMutex.withLock {
            cachedMaterial?.let {
                if (it.fetchedAt > enteredAt || (!forceRefresh && !it.isExpired())) return@withLock it
            }

            val handshake = handshake() ?: throw Exception(MATERIAL_ERROR)

            val partB = runCatching { Base64.decode(handshake.bootstrap.partB, Base64.DEFAULT) }
                .getOrElse { throw Exception(MATERIAL_ERROR) }
            require(partB.size >= 32) { MATERIAL_ERROR }

            storedBuild = handshake.build.serialize()

            val now = System.currentTimeMillis()
            Material(
                key = MKissaCrypto.deriveKey(handshake.mask, partB),
                epoch = handshake.bootstrap.epoch,
                buildId = handshake.build.buildId,
                expiresAt = now + MATERIAL_TTL_MS,
                fetchedAt = now,
            ).also { cachedMaterial = it }
        }
    }

    fun aaReq(material: Material): String =
        MKissaCrypto.buildAaReq(material.key, material.epoch, material.buildId, STREAM_HASH, ANIME_LANE)

    fun decrypt(tobeparsed: String, material: Material): String? =
        MKissaCrypto.decrypt(tobeparsed, material.key)

    fun invalidate() {
        cachedMaterial = null
    }

    fun invalidateBuild() {
        storedBuild = ""
        cachedMaterial = null
    }

    fun isCryptoError(body: String): Boolean =
        runCatching { body.parseAs<AaApiError>().errors }.getOrNull()
            ?.any { it.extensions?.code?.startsWith("AA_CRYPTO") == true } == true

    fun apiErrorMessage(body: String): String? {
        if (isCryptoError(body)) return null
        val message = runCatching { body.parseAs<AaApiError>().errors }.getOrNull()
            ?.firstNotNullOfOrNull { it.message }
            ?: return null
        return if (message == CAPTCHA_ERROR) {
            "MKissa is rate limiting this network ($CAPTCHA_ERROR). Browsing still works; " +
                "streams should return on their own after a while."
        } else {
            "MKissa: $message"
        }
    }

    private class Handshake(
        val build: MKissaBundle.BuildInfo,
        val mask: ByteArray,
        val bootstrap: MKissaCryptoBootstrap,
    )

    private class BootstrapResult(
        val bootstrap: MKissaCryptoBootstrap?,
        val stale: Boolean,
        val mask: ByteArray? = null,
    )

    private suspend fun handshake(): Handshake? {
        val cached = cachedBuild()
        val cachedMasks = cached?.let { MKissaCrypto.maskCandidates(it.buildId, it.seeds) }

        if (cached != null && cachedMasks != null && cachedMasks.isNotEmpty()) {
            val first = bootstrap(cached.buildId, cachedMasks, MKissaCrypto.epochCandidates())
            first.bootstrap?.let { return Handshake(cached, first.mask!!, it) }
            if (!first.stale) return null

            val second = bootstrap(cached.buildId, cachedMasks, MKissaCrypto.skewedEpochCandidates())
            second.bootstrap?.let { return Handshake(cached, second.mask!!, it) }
        }

        val fresh = resolveBuild() ?: return null
        val freshMasks = MKissaCrypto.maskCandidates(fresh.buildId, fresh.seeds)
        if (freshMasks.isEmpty()) return null
        val freshResult = bootstrap(fresh.buildId, freshMasks, MKissaCrypto.epochCandidates())
        return freshResult.bootstrap?.let { Handshake(fresh, freshResult.mask!!, it) }
    }

    private suspend fun bootstrap(buildId: String, masks: List<ByteArray>, epochs: List<Long>): BootstrapResult {
        val host = siteUrl.toHttpUrl().host
        val url = "${apiUrl.trimEnd('/')}$BOOTSTRAP_PATH".toHttpUrl().newBuilder()
            .addQueryParameter("buildId", buildId)
            .addQueryParameter("k", ANIME_LANE)
            .build()

        var sawStale = false
        for (mask in masks) {
            for (epoch in epochs) {
                var epochStale = false
                for (bootToken in MKissaCrypto.bootTokenCandidates(mask, buildId, epoch, KEY_GROUP, host, ANIME_LANE)) {
                    val requestHeaders = headers.newBuilder()
                        .set("x-build-id", buildId)
                        .set("x-aa-boot", bootToken)
                        .set("Origin", siteUrl)
                        .set("Referer", "$siteUrl/")
                        .build()

                    val response = runCatching { client.newCall(GET(url, requestHeaders)).await() }.getOrNull()
                        ?: return BootstrapResult(null, stale = false)

                    if (!response.isSuccessful) {
                        response.close()
                        if (response.code in STALE_CODES) epochStale = true
                        continue
                    }

                    val bootstrap = runCatching { response.parseAs<MKissaCryptoBootstrap>() }.getOrNull()
                        ?: continue

                    if (bootstrap.k != null && bootstrap.k != ANIME_LANE) continue

                    return BootstrapResult(bootstrap, stale = false, mask = mask)
                }
                if (epochStale) sawStale = true
            }
        }
        return BootstrapResult(null, stale = sawStale)
    }

    private fun cachedBuild(): MKissaBundle.BuildInfo? {
        val buildId = storedBuild.substringBefore(FIELD_SEPARATOR, "").takeIf(String::isNotEmpty) ?: return null
        val seeds = storedBuild.substringAfter(FIELD_SEPARATOR, "").split(",").filter(String::isNotBlank)
        if (seeds.size != MKissaCrypto.SEED_COUNT) return null
        return MKissaBundle.BuildInfo(buildId, seeds)
    }

    private suspend fun resolveBuild(): MKissaBundle.BuildInfo? {
        val appUrl = entryUrlFromSite()?.toHttpUrl() ?: return null

        val appJs = runCatching {
            client.newCall(GET(appUrl, headers)).awaitSuccess().bodyString()
        }.getOrNull() ?: return null

        val chunkRefs = CHUNK_REF_REGEX.findAll(appJs)
            .map { it.groupValues[1] }
            .distinct()
            .sortedByDescending { it.contains("/chunks/") }
            .take(MAX_BUILD_CHUNKS)
            .toList()

        for (batch in chunkRefs.chunked(BUILD_CHUNK_BATCH)) {
            val found = batch.parallelCatchingMapNotNull { ref ->
                val chunkUrl = appUrl.resolve(ref) ?: return@parallelCatchingMapNotNull null
                val body = runCatching {
                    client.newCall(GET(chunkUrl, headers)).awaitSuccess().bodyString()
                }.getOrNull() ?: return@parallelCatchingMapNotNull null
                if (!body.contains(CRYPTO_CHUNK_MARKER)) return@parallelCatchingMapNotNull null
                MKissaBundle.parse(body)
            }
            found.firstOrNull()?.let { return it }
        }
        return null
    }

    private suspend fun entryUrlFromSite(): String? {
        val html = runCatching {
            client.newCall(GET("$siteUrl/", headers)).awaitSuccess().bodyString()
        }.getOrNull() ?: return null

        return APP_ENTRY_REGEX.find(html)?.groupValues?.get(1)
    }

    private fun Material.isExpired(): Boolean = System.currentTimeMillis() >= expiresAt

    private fun MKissaBundle.BuildInfo.serialize(): String = "$buildId$FIELD_SEPARATOR${seeds.joinToString(",")}"

    companion object {
        private const val MATERIAL_ERROR = "Unable to obtain MKissa crypto material"
        private const val CAPTCHA_ERROR = "NEED_CAPTCHA"
        private const val BOOTSTRAP_PATH = "/client-crypto/v1/bootstrap"
        private val STALE_CODES = setOf(403, 404)
        private const val KEY_GROUP = "mkissa"
        private const val PREF_BUILD_KEY = "client_build_cache"
        private const val FIELD_SEPARATOR = "|"
        private const val MAX_BUILD_CHUNKS = 40
        private const val BUILD_CHUNK_BATCH = 4
        private const val MATERIAL_TTL_MS = 6 * 60 * 60 * 1000L
        private val APP_ENTRY_REGEX = Regex("""import\("([^"]*/entry/app\.[^"]*\.js)"\)""")
        private val CHUNK_REF_REGEX = Regex("""["'](\.\.?/[\w./-]+\.js)["']""")
        private const val CRYPTO_CHUNK_MARKER = "aaReq"
    }
}
