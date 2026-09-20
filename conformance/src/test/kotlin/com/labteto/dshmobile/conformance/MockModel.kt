package com.labteto.dshmobile.conformance

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The harness's own scriptable model server, run as a process.
 *
 * `packages/test-support/llm-mock-server` is an OpenAI-compatible SSE server whose every response
 * is chosen by a `--sequence` of named behaviours. Pointing a real harness at it is what makes a
 * real turn testable: the agent loop, the session log, the projections and the assistant stream are
 * all genuine, and only the tokens are scripted. Without it the only honest thing to say about live
 * streaming, tool approvals and feedback on a fresh reply is what `docs/VALIDATION-0.11.0.md` says
 * — unverified, because provisioning a paid key was out of scope.
 *
 * A behaviour is consumed per request, so the sequence is a script for the turn: `tool_call_success`
 * then `success` is one tool call followed by a closing message. `--seed` fixes anything random.
 */
class MockModel private constructor(
    /** Base URL to hand the harness, already carrying the `/v1` suffix the adapter expects. */
    val baseUrl: String,
    /** The bearer token the server will require, and the harness will send. */
    val apiKey: String,
    private val process: Process,
) : AutoCloseable {

    override fun close() {
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        private const val API_KEY = "conformance-mock-key"
        private const val READY_TIMEOUT_SECONDS = 60L
        private const val BIN = "packages/test-support/llm-mock-server/src/bin.ts"

        /**
         * Start the server with a scripted sequence.
         *
         * @param sequence behaviours in request order; see the package's README for the full set.
         *   `success` and `reasoning_success` produce text, `tool_call_success` produces a call,
         *   and the fault behaviours (`rate_limit`, `partial_disconnect`, …) are how retry and
         *   interruption paths get exercised.
         * @param successText the text a `success` behaviour streams, so a test can assert on
         *   exactly what the model said.
         * @param repeatLast keep answering with the final behaviour once the sequence runs out,
         *   which a multi-step turn needs.
         */
        fun start(
            sequence: List<String>,
            successText: String = "conformance reply",
            toolName: String? = null,
            toolArguments: String? = null,
            repeatLast: Boolean = true,
        ): MockModel {
            val root = requireNotNull(HarnessProcess.checkout()) { "no harness checkout" }
            val node = requireNotNull(HarnessProcess.node()) { "node is not on PATH" }
            val port = HarnessProcess.freePort()

            val args = buildList {
                add(node)
                add("--import")
                add(tsxLoaderHref(root, node))
                add(File(root, BIN).absolutePath)
                add("--host"); add("127.0.0.1")
                add("--port"); add(port.toString())
                add("--api-key"); add(API_KEY)
                add("--sequence"); add(sequence.joinToString(","))
                // `--seed` is only legal alongside a `random` entry, and the server refuses the
                // whole command line otherwise rather than ignoring it.
                if (sequence.contains("random")) { add("--seed"); add("1") }
                add("--success-text"); add(successText)
                if (repeatLast) add("--repeat-last")
                if (toolName != null) { add("--tool-name"); add(toolName) }
                if (toolArguments != null) { add("--tool-arguments"); add(toolArguments) }
            }

            val process = ProcessBuilder(args).directory(root).redirectErrorStream(true).start()
            val ready = AtomicReference<String>()
            val reader = Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    // The server speaks JSONL; its first `ready` record carries the base URL.
                    if (line.contains("\"type\":\"ready\"")) {
                        Regex(""""baseURL":"([^"]+)"""").find(line)
                            ?.let { ready.compareAndSet(null, it.groupValues[1]) }
                    }
                }
            }
            reader.isDaemon = true
            reader.start()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS)
            while (ready.get() == null && System.nanoTime() < deadline) {
                check(process.isAlive) { "mock model exited before announcing itself" }
                Thread.sleep(50)
            }
            val url = ready.get()
            if (url == null) {
                process.destroyForcibly()
                error("mock model did not announce itself within ${READY_TIMEOUT_SECONDS}s")
            }
            return MockModel(baseUrl = url, apiKey = API_KEY, process = process)
        }

        private fun tsxLoaderHref(root: File, node: String): String {
            val probe = ProcessBuilder(
                node, "-e",
                "console.log(require('url').pathToFileURL(require.resolve('tsx')).href)",
            ).directory(root).redirectErrorStream(true).start()
            val out = probe.inputStream.bufferedReader().readText().trim()
            check(probe.waitFor(30, TimeUnit.SECONDS) && probe.exitValue() == 0) {
                "could not resolve tsx in ${root.absolutePath}: $out"
            }
            return out.lines().last { it.startsWith("file:") }
        }
    }
}
