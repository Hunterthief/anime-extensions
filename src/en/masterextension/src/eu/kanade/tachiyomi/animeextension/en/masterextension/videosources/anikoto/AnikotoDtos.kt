package eu.kanade.tachiyomi.animeextension.en.masterextension.videosources.anikoto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Serializable
class AnikotoResultResponse(
    private val result: String,
) {
    fun toDocument(): Document = Jsoup.parseBodyFragment(result)
}

@Serializable
class AnikotoServerResponseDto(
    val result: AnikotoServerResultDto,
)

@Serializable
class AnikotoServerResultDto(
    val url: String,
)

@Serializable
class AnikotoSourceResponseDto(
    @Serializable(with = AnikotoSourcesSerializer::class) val sources: String,
    val tracks: List<AnikotoTrackDto>? = null,
)

@Serializable
class AnikotoTrackDto(
    val file: String,
    val kind: String,
    val label: String = "",
)

@Serializable
class AnikotoMapperServerDto(
    val sub: AnikotoMapperLinkDto? = null,
    val dub: AnikotoMapperLinkDto? = null,
)

@Serializable
class AnikotoMapperLinkDto(
    val url: String,
)

object AnikotoSourcesSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): String =
        when (val element = (decoder as JsonDecoder).decodeJsonElement()) {
            is JsonObject -> element["file"]?.jsonPrimitive?.content
            is JsonArray -> element.firstOrNull()?.let {
                when (it) {
                    is JsonObject -> it["file"]?.jsonPrimitive?.content
                    is JsonPrimitive -> it.content
                    else -> null
                }
            }
            is JsonPrimitive -> element.content
        } ?: throw IllegalStateException("No valid m3u8 found in sources")

    override fun serialize(encoder: Encoder, value: String): Unit =
        throw UnsupportedOperationException("Serialization not supported")
}
