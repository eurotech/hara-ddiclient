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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A whole client against a fake DDI server, with an integrator callback that throws: the client
 * must close the action (or carry on without the callback) and keep polling.
 */
class HaraClientActorErrorTest {

    private lateinit var server: MockWebServer
    private lateinit var baseDir: File
    private lateinit var client: HaraClient
    private lateinit var closed: CompletableFuture<Pair<String, String>>
    private lateinit var polledAfterClose: CompletableFuture<Unit>
    private val polls = AtomicInteger()
    private val feedback: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val events: MutableList<MessageListener.Message> = Collections.synchronizedList(mutableListOf())

    @BeforeMethod
    fun setUp() {
        closed = CompletableFuture()
        polledAfterClose = CompletableFuture()
        polls.set(0)
        feedback.clear()
        events.clear()
        server = MockWebServer()
        baseDir = Files.createTempDirectory("hara-actor-error").toFile()
    }

    @AfterMethod
    fun tearDown() {
        client.stop()
        server.shutdown()
        baseDir.deleteRecursively()
    }

    @Test
    fun aThrowingDirectoryProviderFailsTheAction() {
        startClient(directory = { throw IllegalStateException("no storage") })

        assertActionFailedAndClientAlive("Internal error in downloadManager: java.lang.IllegalStateException: no storage")
    }

    @Test
    fun aThrowingDownloadBehaviorFailsTheAction() {
        startClient(downloadBehavior = object : DownloadBehavior {
            override fun onAttempt(attempt: Int, artifactId: String, previousError: Throwable?): DownloadBehavior.Try =
                throw IllegalStateException("no policy")
        })

        assertActionFailedAndClientAlive(
            "Internal error in fileDownloader_for_$SHA256: java.lang.IllegalStateException: no policy")
    }

    @Test
    fun aThrowingSoftDownloadPermitFailsTheAction() {
        startClient(download = "attempt", softPermit = object : DeploymentPermitProvider {
            override fun downloadAllowed(): Deferred<Boolean> = throw IllegalStateException("no permit")
        })

        assertActionFailedAndClientAlive("Internal error in downloadManager: java.lang.IllegalStateException: no permit")
    }

    @Test
    fun aThrowingForcedUpdatePermitFailsTheAction() {
        startClient(forcedPermit = object : DeploymentPermitProvider {
            override fun updateAllowed(): Deferred<Boolean> = throw IllegalStateException("no permit")
        })

        assertActionFailedAndClientAlive("Internal error in updateManager: java.lang.IllegalStateException: no permit")
    }

    @Test
    fun aFailedSoftUpdatePermitFailsTheAction() {
        startClient(update = "attempt", softPermit = object : DeploymentPermitProvider {
            override fun updateAllowed(): Deferred<Boolean> =
                CompletableDeferred<Boolean>().apply { completeExceptionally(IllegalStateException("no permit")) }
        })

        assertActionFailedAndClientAlive("Internal error in updateManager: java.lang.IllegalStateException: no permit")
    }

    @Test
    fun aThrowingConfigDataProviderDoesNotStopDeployments() {
        startClient(configData = object : ConfigDataProvider {
            override fun configData(): Map<String, String> = throw IllegalStateException("no config")
        })

        assertActionSucceededAndClientAlive()
        assertTrue(events.contains(MessageListener.Message.Event.Error(
            listOf("Internal error in actionManager: java.lang.IllegalStateException: no config"))), events.toString())
    }

    @Test
    fun aThrowingConfigDataCheckDoesNotStopPolling() {
        startClient(configData = object : ConfigDataProvider {
            override fun isUpdated(): Boolean = throw IllegalStateException("no config")
        })

        assertActionSucceededAndClientAlive()
    }

    @Test
    fun aThrowingMessageListenerDoesNotStopDeployments() {
        startClient(listener = object : MessageListener {
            override fun onMessage(message: MessageListener.Message) = throw IllegalStateException("listener bug")
        })

        assertActionSucceededAndClientAlive()
        // The other listeners still get every message
        assertTrue(events.contains(MessageListener.Message.Event.UpdateFinished(true, listOf("Details:"))), events.toString())
    }

    private fun assertActionFailedAndClientAlive(detail: String) {
        assertEquals(awaitClosed(), "failure" to detail, feedback.toString())
        assertTrue(events.contains(MessageListener.Message.Event.UpdateFinished(false, listOf(detail))), events.toString())
        awaitPollAfterClose()
    }

    private fun assertActionSucceededAndClientAlive() {
        assertEquals(awaitClosed().first, "success", feedback.toString())
        awaitPollAfterClose()
    }

    private fun awaitClosed(): Pair<String, String> = try {
        closed.get(15, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
        throw AssertionError("The action was never closed. Polls: ${polls.get()}, feedback: $feedback", e)
    }

    private fun awaitPollAfterClose() = try {
        polledAfterClose.get(15, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
        throw AssertionError("The client stopped polling. Polls: ${polls.get()}, feedback: $feedback", e)
    }

    private fun startClient(
        download: String = "forced",
        update: String = "forced",
        directory: () -> File = { baseDir },
        configData: ConfigDataProvider = object : ConfigDataProvider {},
        softPermit: DeploymentPermitProvider = object : DeploymentPermitProvider {},
        forcedPermit: DeploymentPermitProvider = object : DeploymentPermitProvider {},
        listener: MessageListener? = null,
        downloadBehavior: DownloadBehavior = object : DownloadBehavior {
            override fun onAttempt(attempt: Int, artifactId: String, previousError: Throwable?) =
                if (attempt == 1) DownloadBehavior.Try.After(0) else DownloadBehavior.Try.Stop
        }
    ) {
        server.dispatcher = FakeDdiServer(download, update)
        server.start()
        client = HaraClientDefaultImpl().apply {
            init(
                HaraClientData(TENANT, CONTROLLER_ID, server.url("/").toString(), gatewayToken = "token"),
                object : DirectoryForArtifactsProvider {
                    override fun directoryForArtifacts() = directory()
                },
                configData,
                softPermit,
                listOfNotNull(listener, object : MessageListener {
                    override fun onMessage(message: MessageListener.Message) {
                        events += message
                    }
                }),
                listOf(object : Updater {
                    override fun apply(modules: Set<Updater.SwModuleWithPath>, messenger: Updater.Messenger) =
                        Updater.UpdateResult(true)
                }),
                downloadBehavior,
                forcedPermit)
            startAsync()
        }
    }

    private inner class FakeDdiServer(private val download: String, private val update: String) : Dispatcher() {

        // Like hawkBit: a closed action is no longer offered
        @Volatile
        private var actionOver = false

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!
            val base = "/$TENANT/controller/v1/$CONTROLLER_ID"
            return when {
                path == base -> {
                    polls.incrementAndGet()
                    if (actionOver) polledAfterClose.complete(Unit)
                    json(if (actionOver) NOTHING_TO_DO else controllerBase())
                }
                path.startsWith("$base/deploymentBase/42/feedback") -> {
                    val body = request.body.readUtf8()
                    feedback += body
                    if (EXECUTION.find(body)!!.groupValues[1] == "closed") {
                        actionOver = true
                        closed.complete(FINISHED.find(body)!!.groupValues[1] to FIRST_DETAIL.find(body)!!.groupValues[1])
                    }
                    MockResponse()
                }
                path.startsWith("$base/deploymentBase/42") -> json(deploymentBase())
                path == ARTIFACT -> MockResponse().setBody(CONTENT)
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun controllerBase() = """
            {"config":{"polling":{"sleep":"00:00:01"}},
             "_links":{"deploymentBase":{"href":"${server.url("/$TENANT/controller/v1/$CONTROLLER_ID/deploymentBase/42?c=-1")}"}}}
        """

        private fun deploymentBase() = """
            {"id":"42",
             "deployment":{"download":"$download","update":"$update","maintenanceWindow":"available",
               "chunks":[{"part":"os","version":"1.0","name":"linux",
                 "artifacts":[{"filename":"image.bin","hashes":{"sha256":"$SHA256"},"size":5,
                   "_links":{"download-http":{"href":"${server.url(ARTIFACT)}"}}}]}]}}
        """

        private fun json(body: String) = MockResponse()
            .setHeader("Content-Type", "application/hal+json")
            .setBody(body)
    }

    companion object {
        private const val TENANT = "DEFAULT"
        private const val CONTROLLER_ID = "actor-error-test"
        private const val ARTIFACT = "/files/image.bin"
        private const val CONTENT = "hello"
        private const val SHA256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        private val EXECUTION = Regex("\"execution\":\"(\\w+)\"")
        private val FINISHED = Regex("\"finished\":\"(\\w+)\"")
        private val FIRST_DETAIL = Regex("\"details\":\\[\"([^\"]*)\"")
        private const val NOTHING_TO_DO = """{"config":{"polling":{"sleep":"00:00:01"}},"_links":{}}"""
    }
}
