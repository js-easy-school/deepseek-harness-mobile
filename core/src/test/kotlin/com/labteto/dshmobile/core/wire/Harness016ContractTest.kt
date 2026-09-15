package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream

/** Contract examples independently transcribed from upstream 0d1f500's Remote signatures. */
class Harness016ContractTest {
    private class Transport : RpcTransport {
        var path = ""
        var args: JsonObject = JsonObject(emptyMap())
        var value = "{}"
        override suspend fun post(path: String, body: String): RpcHttpResponse {
            this.path = path
            val request = Json.parseToJsonElement(body).jsonObject
            args = request.getValue("payload").jsonObject.getValue("args").jsonObject
            return RpcHttpResponse(200, """{"type":"server-response","rpcId":${request["rpcId"]},"result":{"ok":true,"value":$value}}""")
        }
        override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T = error("unused")
        override suspend fun upload(path: String, contentType: String, contentLength: Long, body: InputStream, onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unused")
    }
    @Test fun `permission catalog and V3 roster decode without legacy fields`() = runTest {
        val t = Transport(); val api = DshApiClient(t)
        t.value = """{"options":[{"value":"auto","name":"Auto review"}]}"""
        val result = api.permissionCatalog() as RpcResult.Ok
        assertEquals("auto", result.value.options.single().value)
        assertEquals("/api/permissionPresets/catalog", t.path)
        t.value = """{"presets":[],"authorable":true,"modeSelectionEnabled":false}"""
        assertFalse((api.agentPresetList() as RpcResult.Ok).value.modeSelectionEnabled)
    }
    @Test fun `file operations use header lookup identity and explicit ranges`() = runTest {
        val t = Transport(); val api = DshApiClient(t)
        t.value = """{"absolutePath":"/work/a.kt","version":"v1","offset":1,"text":"abc","lines":1,"eof":true}"""
        assertTrue(api.workspaceFileRead("cold-child", "a.kt") is RpcResult.Ok)
        assertEquals(setOf("workspaceFileScopeId", "path", "range"), t.args.keys)
        assertEquals("cold-child", t.args["workspaceFileScopeId"]?.jsonPrimitive?.content)
        assertEquals(1, t.args["range"]?.jsonObject?.get("offset")?.jsonPrimitive?.int)
    }
    @Test fun `feedback creation sends explicit null version and preserves nested conflict`() = runTest {
        val t = Transport(); val api = DshApiClient(t)
        t.value = """{"ok":false,"error":{"code":"version-conflict","current":null}}"""
        val result = api.messageFeedbackPut(MessageFeedbackPutRequest("s", "m", "positive", null)) as RpcResult.Ok
        assertFalse(result.value.ok)
        assertEquals("version-conflict", result.value.error?.code)
        assertTrue(t.args.getValue("request").jsonObject.containsKey("ifVersion"))
        assertEquals(JsonNull, t.args.getValue("request").jsonObject["ifVersion"])
    }
    @Test fun `terminal writes carry controller identity and list uses session identity`() = runTest {
        val t = Transport(); val api = DshApiClient(t)
        t.value = "null"
        api.terminalWrite("s", "terminal-1", "controller-1", "pwd\r")
        assertEquals(setOf("agentId", "id", "attachmentId", "data"), t.args.keys)
        assertEquals("pwd\r", t.args["data"]?.jsonPrimitive?.content)
        t.value = "[]"; assertTrue(api.terminalList("s") is RpcResult.Ok)
        assertEquals(setOf("sessionId"), t.args.keys)
    }
    @Test fun `subagent messages encode delivery and multiple photos stay in one prompt`() = runTest {
        val t = Transport(); val api = DshApiClient(t)
        t.value = """{"messageId":"m"}"""
        api.subagentPrompt(SubagentPromptRequest(requestId = "r", parentSessionId = "p", childSessionId = "c", delivery = "steer"))
        assertEquals("steer", t.args["request"]?.jsonObject?.get("delivery")?.jsonPrimitive?.content)
        t.value = """{"accepted":true}"""
        api.sessionPrompt(SessionPromptRequest(requestId = "r2", sessionId = "s", mode = "queue", content =
            listOf(PromptContentPart.Text("Site photos")) + List(3) { PromptContentPart.Image("image/png", "AA==") }))
        assertEquals("/api/session/prompt", t.path)
        assertEquals(4, t.args["request"]?.jsonObject?.get("content")?.jsonArray?.size)
    }
}
