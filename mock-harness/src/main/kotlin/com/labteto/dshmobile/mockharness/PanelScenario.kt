package com.labteto.dshmobile.mockharness

import kotlinx.serialization.json.*
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Stateful, credential-free fixtures for the 0d1f500 contracts. Never runs a shell. */
class PanelScenario(private val harness: MockHarness) {
    val archived = mutableSetOf("archived-demo")
    val feedback = ConcurrentHashMap<String, JsonObject>()
    val files = ConcurrentHashMap<String, ByteArray>().apply {
        put("README.md", "# Preview fixture\n\nTwo photos belong in one prompt.\n".toByteArray())
        put("index.html", "<h1>Isolated preview</h1><script>document.body.innerHTML='UNSAFE'</script>".toByteArray())
    }
    val terminals = ConcurrentHashMap<String, JsonObject>()
    val terminalInputs = mutableListOf<String>()
    private var revision = 0
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun ok(value: JsonElement) = buildJsonObject { put("ok", true); put("value", value) }
    private fun conflict(current: JsonObject?) = buildJsonObject {
        put("ok", false); putJsonObject("error") { put("code", "version-conflict"); put("current", current ?: JsonNull) }
    }
    init {
        harness.remote("permissionPresets", "catalog", emptySet()) {
            Json.parseToJsonElement("""{"options":[{"value":"default","name":"Default"},{"value":"auto","name":"Auto review"}]}""")
        }
        harness.requestRemote("workspace", "unarchiveSession", setOf("sessionId")) {
            archived.remove(it.string("sessionId"))
            buildJsonObject { put("archivedSessionIds", JsonArray(archived.map(::JsonPrimitive))) }
        }
        harness.requestRemote("messageFeedback", "list", setOf("sessionId")) { request ->
            ok(buildJsonObject { put("items", JsonArray(feedback.filterKeys { it.startsWith(request.string("sessionId") + ":") }.values.toList())) })
        }
        harness.requestRemote("messageFeedback", "put", setOf("sessionId", "messageId", "rating", "ifVersion")) { request ->
            synchronized(feedback) {
                val key = request.string("sessionId") + ":" + request.string("messageId")
                val old = feedback[key]
                if ((old?.get("version") ?: JsonNull) != request["ifVersion"]) conflict(old)
                else {
                    val item = buildJsonObject {
                        put("messageId", request.getValue("messageId")); put("rating", request.getValue("rating"))
                        request["note"]?.let { put("note", it) }; request["category"]?.let { put("category", it) }
                        put("version", "v${++revision}"); put("createdAt", old?.get("createdAt") ?: JsonPrimitive(1000)); put("updatedAt", 1000 + revision)
                    }
                    feedback[key] = item; ok(item)
                }
            }
        }
        harness.requestRemote("messageFeedback", "delete", setOf("sessionId", "messageId", "ifVersion")) { request ->
            synchronized(feedback) {
                val key = request.string("sessionId") + ":" + request.string("messageId")
                val old = feedback[key]
                if (old != null && old["version"] != request["ifVersion"]) conflict(old)
                else { feedback.remove(key); ok(buildJsonObject { put("absent", true) }) }
            }
        }
        harness.remote("workspaceFiles", "list", setOf("workspaceFileScopeId", "path")) {
            buildJsonObject {
                put("path", ""); put("truncated", false)
                put("entries", JsonArray(files.entries.sortedBy { it.key }.map { (name, bytes) ->
                    buildJsonObject { put("name", name); put("type", "file"); put("size", bytes.size) }
                }))
            }
        }
        for (method in listOf("stat", "read", "readBytes", "readAll", "readRelated")) {
            val args = setOf("workspaceFileScopeId", "path") + when (method) {
                "read", "readBytes" -> setOf("range")
                "readRelated" -> setOf("relativePath")
                else -> emptySet()
            }
            harness.remote("workspaceFiles", method, args) { request ->
                val path = if (method == "readRelated") request.string("relativePath") else request.string("path").removePrefix("./")
                val bytes = files[path] ?: throw IllegalStateException("workspace-file/not-found")
                buildJsonObject {
                    put("absolutePath", "/workspace/$path"); put("bytes", bytes.size); put("version", bytes.contentHashCode().toString())
                    if (method == "read") {
                        val range = request.getValue("range").jsonObject
                        val offset = range["offset"]?.jsonPrimitive?.int ?: 1
                        val limit = range["limit"]?.jsonPrimitive?.int ?: 500
                        val lines = bytes.toString(Charsets.UTF_8).lines()
                        val page = lines.drop(offset - 1).take(limit)
                        put("offset", offset); put("text", page.joinToString("\n")); put("lines", page.size); put("eof", offset - 1 + page.size >= lines.size)
                    } else if (method != "stat") {
                        val range = request["range"] as? JsonObject
                        val offset = range?.get("offset")?.jsonPrimitive?.int ?: 0
                        val length = range?.get("length")?.jsonPrimitive?.int ?: bytes.size
                        val end = (offset + length).coerceAtMost(bytes.size)
                        put("offset", offset); put("data", Base64.getEncoder().encodeToString(bytes.copyOfRange(offset.coerceAtMost(end), end))); put("eof", end == bytes.size)
                    }
                }
            }
        }
        val shell = Json.parseToJsonElement("""{"path":"/mock/sh","args":[],"name":"Fixture shell"}""")
        harness.remote("terminal", "environment", setOf("agentId")) {
            Json.parseToJsonElement("""{"cwd":"/workspace","maxInputBytes":4096,"maxCols":240,"maxRows":100,"scrollback":1000}""")
        }
        harness.remote("terminal", "shells", setOf("agentId")) { JsonArray(listOf(shell)) }
        harness.remote("terminal", "list", setOf("sessionId")) { request ->
            JsonArray(terminals.filterKeys { it.startsWith(request.string("sessionId") + ":") }.values.toList())
        }
        harness.remote("terminal", "create", setOf("agentId", "request")) { args ->
            val request = args.getValue("request").jsonObject
            terminals.getOrPut(args.string("agentId") + ":" + request.string("id")) {
                buildJsonObject {
                    put("id", request.getValue("id")); put("title", "Fixture shell"); put("shell", shell)
                    put("cwd", "/workspace"); put("cols", request.getValue("cols")); put("rows", request.getValue("rows"))
                    put("state", "running"); put("exitCode", JsonNull)
                }
            }
        }
        for (method in listOf("write", "resize", "rename", "close")) {
            val expected = setOf("agentId", "id") + when (method) {
                "write" -> setOf("attachmentId", "data")
                "resize" -> setOf("attachmentId", "cols", "rows")
                "rename" -> setOf("title")
                else -> emptySet()
            }
            harness.remote("terminal", method, expected) { args ->
                val key = args.string("agentId") + ":" + args.string("id")
                val terminal = terminals[key] ?: error("terminal/not-found")
                if (method in setOf("write", "resize") && args["attachmentId"] != terminal["controllerId"]) error("terminal/control-unavailable")
                when (method) {
                    "write" -> terminalInputs.add(args.string("data"))
                    "resize" -> terminals[key] = JsonObject(terminal + args.filterKeys { it in setOf("cols", "rows") })
                    "rename" -> terminals[key] = JsonObject(terminal + ("title" to args.getValue("title")))
                    "close" -> terminals.remove(key)
                }
                JsonNull
            }
        }
        harness.onStream("terminal/follow") { args ->
            val key = args.string("agentId") + ":" + args.string("id")
            val terminal = terminals[key] ?: error("terminal/not-found")
            val info = JsonObject(terminal + ("controllerId" to args.getValue("attachmentId")))
            terminals[key] = info
            listOf(buildJsonObject { put("type", "snapshot"); put("sequence", 0); put("screen", "Fixture terminal\r\n$ "); put("info", info) })
        }
        harness.onStream("workspaceFiles/changes") { listOf(buildJsonObject { put("kind", "ready") }) }
    }
}
