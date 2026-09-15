package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.*
import com.labteto.dshmobile.mockharness.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.Assert.*

class PanelContractsEndToEndTest {
    @Test fun panelsUseRealHttpAndPreserveBusinessFailures() = runBlocking {
        val harness = MockHarness()
        val scenario = PanelScenario(harness)
        val port = harness.start()
        try {
            val api = DshApiClient(OkHttpRpcTransport("http://127.0.0.1:$port", OkHttpClient(), 5000, 5000))
            assertEquals("auto", (api.permissionCatalog() as RpcResult.Ok).value.options.last().value)
            assertTrue((api.workspaceUnarchiveSession("archived-demo") as RpcResult.Ok).value.archivedSessionIds.isEmpty())
            assertTrue(scenario.archived.isEmpty())
            val listing = (api.workspaceFileList("cold-child", ".") as RpcResult.Ok).value
            assertEquals(2, listing.entries.size)
            val page = (api.workspaceFileRead("cold-child", "README.md", WorkspaceFileRange(1, 1)) as RpcResult.Ok).value
            assertEquals("# Preview fixture", page.text)
            assertFalse(page.eof)
            val rest = (api.workspaceFileRead("cold-child", "README.md", WorkspaceFileRange(2, 500)) as RpcResult.Ok).value
            assertTrue(rest.eof)
            assertEquals(page.version, rest.version)
            assertTrue(api.workspaceFileReadAll("cold-child", "README.md") is RpcResult.Ok)
            assertTrue(api.workspaceFileReadRelated("cold-child", "index.html", "README.md") is RpcResult.Ok)
            val first = (api.messageFeedbackPut(MessageFeedbackPutRequest("s", "m", "positive", null, "Keep this note")) as RpcResult.Ok).value
            assertTrue(first.ok)
            val conflict = (api.messageFeedbackPut(MessageFeedbackPutRequest("s", "m", "negative", null, "Do not discard")) as RpcResult.Ok).value
            assertFalse(conflict.ok)
            assertEquals(first.value, conflict.error?.current)
            assertEquals("Keep this note", (api.messageFeedbackList("s") as RpcResult.Ok).value.value?.items?.single()?.note)
            val deleted = (api.messageFeedbackDelete(MessageFeedbackDeleteRequest("s", "m", first.value!!.version)) as RpcResult.Ok).value
            assertTrue(deleted.ok)
            assertTrue((api.messageFeedbackList("s") as RpcResult.Ok).value.value!!.items.isEmpty())
            val terminal = (api.terminalCreate("s", TerminalCreateRequest("t", 80, 24)) as RpcResult.Ok).value
            assertEquals("t", terminal.id)
            assertTrue(api.terminalWrite("s", "t", "wrong-controller", "whoami\r") is RpcResult.Err)
            assertTrue(scenario.terminalInputs.isEmpty())
            assertTrue(api.terminalRename("s", "t", "Build") is RpcResult.Ok)
            assertEquals("Build", (api.terminalList("s") as RpcResult.Ok).value.single().title)
            assertTrue(api.terminalClose("s", "t") is RpcResult.Ok)
            assertTrue((api.terminalList("s") as RpcResult.Ok).value.isEmpty())
        } finally { harness.stop() }
    }
}
