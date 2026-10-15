/*
 * Copyright © 2026  Eurotech S.p.A.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.hara.ddiclient.api

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A whole client against a fake DDI server whose action is outside its maintenance window:
 * the client downloads the files, then installs them only when the window opens.
 */
class HaraClientMaintenanceWindowTest {

    private lateinit var server: MockWebServer
    private lateinit var artifactsDir: File
    private lateinit var client: HaraClient
    private lateinit var waiting: CompletableFuture<Unit>
    private lateinit var applied: CompletableFuture<Unit>
    private lateinit var closedFeedback: CompletableFuture<String>
    private lateinit var cancelFeedback: CompletableFuture<String>

    @Volatile
    private var windowOpen = false

    @Volatile
    private var cancelRequested = false

    @BeforeMethod
    fun setUp() {
        waiting = CompletableFuture()
        applied = CompletableFuture()
        closedFeedback = CompletableFuture()
        cancelFeedback = CompletableFuture()
        windowOpen = false
        cancelRequested = false
        server = MockWebServer()
        artifactsDir = Files.createTempDirectory("hara-maintenance-window").toFile()
    }

    @AfterMethod
    fun tearDown() {
        client.stop()
        server.shutdown()
        artifactsDir.deleteRecursively()
    }

    @Test
    fun theUpdateWaitsForTheMaintenanceWindow() {
        startClient()

        waiting.get(20, TimeUnit.SECONDS)
        // A few polls with the window still closed
        Thread.sleep(3_000)
        assertFalse(applied.isDone)
        assertTrue(File(artifactsDir, CONTENT_MD5).exists())

        windowOpen = true

        applied.get(20, TimeUnit.SECONDS)
        val feedback = closedFeedback.get(20, TimeUnit.SECONDS)
        assertTrue(feedback.contains("\"finished\":\"success\""), feedback)
    }

    @Test
    fun anActionWaitingForTheMaintenanceWindowCanBeCancelled() {
        startClient()

        waiting.get(20, TimeUnit.SECONDS)
        cancelRequested = true

        val feedback = cancelFeedback.get(20, TimeUnit.SECONDS)
        assertTrue(feedback.contains("\"execution\":\"closed\""), feedback)
        Thread.sleep(2_000)
        assertFalse(applied.isDone)
    }

    private fun startClient() {
        server.dispatcher = FakeDdiServer()
        server.start()
        client = HaraClientDefaultImpl().apply {
            init(
                HaraClientData(TENANT, CONTROLLER_ID, server.url("/").toString(), gatewayToken = "token"),
                object : DirectoryForArtifactsProvider {
                    override fun directoryForArtifacts() = artifactsDir
                },
                object : ConfigDataProvider {},
                object : DeploymentPermitProvider {},
                listOf(object : MessageListener {
                    override fun onMessage(message: MessageListener.Message) {
                        if (message == MessageListener.Message.State.WaitingMaintenanceWindow) waiting.complete(Unit)
                    }
                }),
                listOf(object : Updater {
                    override fun apply(modules: Set<Updater.SwModuleWithPath>, messenger: Updater.Messenger) =
                        Updater.UpdateResult(true).also { applied.complete(Unit) }
                }),
                object : DownloadBehavior {
                    override fun onAttempt(attempt: Int, artifactId: String, previousError: Throwable?) =
                        if (attempt == 1) DownloadBehavior.Try.After(0) else DownloadBehavior.Try.Stop
                })
            startAsync()
        }
    }

    private inner class FakeDdiServer : Dispatcher() {

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!
            val base = "/$TENANT/controller/v1/$CONTROLLER_ID"
            return when {
                path == base -> json(controllerBase())
                path.startsWith("$base/deploymentBase/42/feedback") -> {
                    val body = request.body.readUtf8()
                    if (body.contains("\"execution\":\"closed\"")) {
                        closedFeedback.complete(body)
                    }
                    MockResponse()
                }
                path.startsWith("$base/deploymentBase/42") -> json(deploymentBase())
                path.startsWith("$base/cancelAction/42/feedback") -> {
                    cancelFeedback.complete(request.body.readUtf8())
                    MockResponse()
                }
                path.startsWith("$base/cancelAction/42") -> json("""{"id":"42","cancelAction":{"stopId":"42"}}""")
                path == ARTIFACT -> MockResponse().setBody(CONTENT)
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun controllerBase() = when {
            closedFeedback.isDone || cancelFeedback.isDone -> NOTHING_TO_DO
            cancelRequested -> """
                {"config":{"polling":{"sleep":"00:00:01"}},
                 "_links":{"cancelAction":{"href":"${server.url("/$TENANT/controller/v1/$CONTROLLER_ID/cancelAction/42")}"}}}
            """
            else -> """
                {"config":{"polling":{"sleep":"00:00:01"}},
                 "_links":{"deploymentBase":{"href":"${server.url("/$TENANT/controller/v1/$CONTROLLER_ID/deploymentBase/42?c=-1")}"}}}
            """
        }

        // Like hawkBit: outside the window the update is skipped
        private fun deploymentBase(): String {
            val update = if (windowOpen) "forced" else "skip"
            val window = if (windowOpen) "available" else "unavailable"
            return """
                {"id":"42",
                 "deployment":{"download":"forced","update":"$update","maintenanceWindow":"$window",
                   "chunks":[{"part":"os","version":"1.0","name":"linux",
                     "artifacts":[{"filename":"image.bin","hashes":{"sha1":"s1","md5":"$CONTENT_MD5"},"size":5,
                       "_links":{"download-http":{"href":"${server.url(ARTIFACT)}"}}}]}]}}
            """
        }

        private fun json(body: String) = MockResponse()
            .setHeader("Content-Type", "application/hal+json")
            .setBody(body)
    }

    companion object {
        private const val TENANT = "DEFAULT"
        private const val CONTROLLER_ID = "window-test"
        private const val ARTIFACT = "/files/image.bin"
        private const val CONTENT = "hello"
        private const val CONTENT_MD5 = "5d41402abc4b2a76b9719d911017c592"
        private const val NOTHING_TO_DO = """{"config":{"polling":{"sleep":"00:00:01"}},"_links":{}}"""
    }
}
