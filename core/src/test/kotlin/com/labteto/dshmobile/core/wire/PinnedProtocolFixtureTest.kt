package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.DshCore
import com.labteto.dshmobile.core.session.*
import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PinnedProtocolFixtureTest {
    @Test fun workspaceReconnectBaselineUsesNestedValue() {
        val frame = decodeFromJsonElement(WorkspaceFollowFrameSerializer, Json.parseToJsonElement("""
            {"type":"baseline","value":{"items":[],"archivedSessionIds":["archived"]}}
        """)) as WorkspaceFollowFrame.Baseline
        assertEquals(listOf("archived"), frame.archivedSessionIds)
    }
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/protocol/0d1f500.json")!!.readText()).jsonObject
    @Test fun pinnedV3HistoryPreservesJournalAndProjectsImageOffload() {
        assertEquals(DshCore.PROTOCOL_COMMIT, fixture.getValue("upstreamCommit").jsonPrimitive.content)
        val events = fixture.getValue("events").jsonArray.map { raw ->
            val e = raw.jsonObject
            SessionEventEnvelope(e.getValue("type").jsonPrimitive.content, e.getValue("seq").jsonPrimitive.long,
                e.getValue("time").jsonPrimitive.long, e.getValue("data"), surfaceIntent = e["surfaceOp"],
                sourceEventSeqs = (e["sourceEventSeqs"] as? JsonArray)?.map { it.jsonPrimitive.int },
                ignorable = e["ignorable"]?.jsonPrimitive?.booleanOrNull)
        }
        val snapshot = EventFold("s").fold(events)
        assertEquals(listOf(0L, 3L), snapshot.effectiveSurface.map { it.seq })
        val projectedImage = snapshot.effectiveSurface.first().data.jsonObject.getValue("content").jsonArray.first().jsonObject
        assertTrue(projectedImage.getValue("offloaded").jsonPrimitive.boolean)
        assertFalse(events.first().data.jsonObject.getValue("content").jsonArray.first().jsonObject.containsKey("offloaded"))
        assertTrue(snapshot.nodes.any { it is OtherNode && it.type == "future/notice" })
        assertEquals(events, snapshot.journal)
    }
    @Test fun optionalFlagsNestedFailuresAndRecoveryFramesDecode() {
        assertFalse(decodeFromJsonElement(AgentPresetListValue.serializer(), fixture.getValue("roster")).modeSelectionEnabled)
        val failure = decodeFromJsonElement(MessageFeedbackResult.serializer(MessageFeedbackItem.serializer()), fixture.getValue("feedbackConflict"))
        assertFalse(failure.ok)
        assertEquals("Retained note", failure.error?.current?.note)
        val snapshot = decodeFromJsonElement(TerminalFrame.serializer(), fixture.getValue("terminalSnapshot"))
        val output = decodeFromJsonElement(TerminalFrame.serializer(), fixture.getValue("terminalOutput"))
        assertEquals("attachment-2", snapshot.info?.controllerId)
        assertEquals(snapshot.sequence!! + 1, output.sequence)
        assertFalse(decodeFromJsonElement(WorkspaceFileText.serializer(), fixture.getValue("fileRead")).eof)
    }
}
