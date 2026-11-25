package com.tajchert.trefoil.core.protocol

import kotlinx.serialization.json.*

/** Effect 4.1.2 RpcMessage / RpcSerialization.layerJson; deliberately not JSON-RPC. */
object EffectRpc {
    val json = Json { ignoreUnknownKeys = true }
    fun request(id: String, tag: String, payload: JsonObject) = buildJsonObject {
        put("_tag", "Request"); put("id", id); put("tag", tag)
        put("payload", payload); put("headers", JsonArray(emptyList()))
    }
    fun signal(tag: String, id: String? = null) = buildJsonObject {
        put("_tag", tag); if (id != null) put("requestId", id)
    }
    /** Larger frames are never parsed (a phone would run out of memory); see [FrameTooLarge]. */
    const val MAX_FRAME_CHARS = 16 * 1024 * 1024
    // Effect encodes a response as `{"_tag":…,"requestId":…,…}`; only a single message is attributed, never a batch.
    private val singleResponse = Regex("""^\{"_tag":"(?:Chunk|Exit)","requestId":"([^"\\]{1,64})"""")
    fun decode(text: String, maxChars: Int = MAX_FRAME_CHARS): List<JsonObject> {
        if (text.length > maxChars) throw FrameTooLarge(singleResponse.find(text.take(128))?.groupValues?.get(1))
        return when (val value = json.parseToJsonElement(text)) {
            is JsonObject -> listOf(value)
            is JsonArray -> value.map { it.jsonObject }
            else -> error("Invalid RPC frame")
        }
    }
}
/** A frame over [EffectRpc.MAX_FRAME_CHARS], left unparsed; [requestId] names its request when it is one `Chunk` or `Exit`. */
class FrameTooLarge(val requestId: String?) : IllegalArgumentException("RPC frame too large")
fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())
fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
