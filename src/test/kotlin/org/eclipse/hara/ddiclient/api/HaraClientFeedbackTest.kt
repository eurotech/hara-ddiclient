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
import org.eclipse.hara.ddiclient.api.MessageListener.Message.Event.DeployFeedbackRequestResult
import org.testng.Assert.assertFalse
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A whole client against a fake DDI server that refuses every feedback.
 */
class HaraClientFeedbackTest {

    private lateinit var server: MockWebServer
    private lateinit var artifactsDir: File
    private lateinit var client: HaraClient
    private lateinit var firstResult: CompletableFuture<DeployFeedbackRequestResult>

    @BeforeMethod
    fun setUp() {
        firstResult = CompletableFuture()
        server = MockWebServer()
        artifactsDir = Files.createTempDirectory("hara-feedback").toFile()
    }

    @AfterMethod
    fun tearDown() {
        client.stop()
        server.shutdown()
        artifactsDir.deleteRecursively()
    }

    @Test
    fun feedbackRefusedWithGoneIsNotReportedAsDelivered() {
        server.dispatcher = FakeDdiServer(feedbackCode = 410)
        server.start()
        startClient()

        assertFalse(firstResult.get(20, TimeUnit.SECONDS).success)
    }

    private fun startClient() {
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
                        if (message is DeployFeedbackRequestResult) firstResult.complete(message)
                    }
                }),
                listOf(object : Updater {
                    override fun apply(modules: Set<Updater.SwModuleWithPath>, messenger: Updater.Messenger) =
                        Updater.UpdateResult(true)
                }),
                object : DownloadBehavior {
                    override fun onAttempt(attempt: Int, artifactId: String, previousError: Throwable?) =
                        DownloadBehavior.Try.Stop
                })
            startAsync()
        }
    }

    private inner class FakeDdiServer(private val feedbackCode: Int) : Dispatcher() {

        // Like hawkBit after a 410: the action is closed and no longer offered
        @Volatile
        private var actionClosed = false

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!
            val base = "/$TENANT/controller/v1/$CONTROLLER_ID"
            return when {
                path == base && actionClosed -> json(NOTHING_TO_DO)
                path == base -> json("""
                    {"config":{"polling":{"sleep":"00:00:01"}},
                     "_links":{"deploymentBase":{"href":"${server.url("$base/deploymentBase/42?c=-1")}"}}}
                """)
                path.startsWith("$base/deploymentBase/42/feedback") -> {
                    actionClosed = true
                    MockResponse().setResponseCode(feedbackCode)
                }
                path.startsWith("$base/deploymentBase/42") -> json("""
                    {"id":"42",
                     "deployment":{"download":"forced","update":"forced","maintenanceWindow":"available",
                       "chunks":[{"part":"os","version":"1.0","name":"linux","artifacts":[]}]}}
                """)
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun json(body: String) = MockResponse()
            .setHeader("Content-Type", "application/hal+json")
            .setBody(body)
    }

    companion object {
        private const val TENANT = "DEFAULT"
        private const val CONTROLLER_ID = "feedback-test"
        private const val NOTHING_TO_DO = """{"config":{"polling":{"sleep":"00:00:01"}},"_links":{}}"""
    }
}
