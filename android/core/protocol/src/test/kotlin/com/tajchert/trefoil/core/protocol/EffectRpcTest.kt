package com.tajchert.trefoil.core.protocol
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class EffectRpcTest {
 @Test fun requestUsesEffectEnvelope() {
   val value = EffectRpc.request("7", "orchestration.subscribeShell", buildJsonObject {})
   assertEquals("Request", value.string("_tag")); assertEquals("7", value.string("id"))
   assertEquals(JsonArray(emptyList()), value["headers"]); assertFalse(value.containsKey("jsonrpc"))
 }
 @Test fun handlesBatchedChunkAndUnknownFields() {
   val frames = EffectRpc.decode("""[{"_tag":"Chunk","requestId":"7","values":[{"kind":"synchronized"}],"future":true},{"_tag":"Pong"}]""")
   assertEquals(2, frames.size); assertEquals("synchronized", frames[0].objects("values")[0].string("kind"))
 }
 @Test fun oversizedFrameNamesOnlyASingleResponse() {
   fun requestId(text: String) = try { EffectRpc.decode(text, maxChars = 40); null } catch (error: FrameTooLarge) { error.requestId ?: "none" }
   assertEquals("12", requestId("""{"_tag":"Chunk","requestId":"12","values":[{"kind":"snapshot","x":"${"a".repeat(64)}"}]}"""))
   assertEquals("9", requestId("""{"_tag":"Exit","requestId":"9","exit":{"_tag":"Success","value":"${"a".repeat(64)}"}}"""))
   // A batch, another order or another tag names no request.
   assertEquals("none", requestId("""[{"_tag":"Chunk","requestId":"12","values":[{"x":"${"a".repeat(64)}"}]}]"""))
   assertEquals("none", requestId("""{"requestId":"12","_tag":"Chunk","values":[{"x":"${"a".repeat(64)}"}]}"""))
   assertEquals("none", requestId("""{"_tag":"Defect","requestId":"12","defect":"${"a".repeat(64)}"}"""))
   assertNull(requestId("""{"_tag":"Pong"}"""))
 }
 @Test(expected = IllegalStateException::class) fun rejectsPrimitiveFrame() { EffectRpc.decode("1") }
}
