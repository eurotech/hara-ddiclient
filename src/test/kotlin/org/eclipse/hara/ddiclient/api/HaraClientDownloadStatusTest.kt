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
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A whole client against a fake DDI server, to check the execution status of the feedback
 * sent while the artifacts are downloaded.
 */
class HaraClientDownloadStatusTest {

    private lateinit var server: MockWebServer
    private lateinit var artifactsDir: File
    private lateinit var client: HaraClient
    private lateinit var closed: CompletableFuture<Unit>
    private lateinit var applied: CompletableFuture<Unit>
    private lateinit var downloaded: CompletableFuture<Unit>
    private val feedback: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    @BeforeMethod
    fun setUp() {
        closed = CompletableFuture()
        applied = CompletableFuture()
        downloaded = CompletableFuture()
        feedback.clear()
        server = MockWebServer()
        artifactsDir = Files.createTempDirectory("hara-download-status").toFile()
    }

    @AfterMethod
    fun tearDown() {
        client.stop()
        server.shutdown()
        artifactsDir.deleteRecursively()
    }

    @Test
    fun downloadingIsReportedAsDownloadThenDownloaded() {
        startClient()

        closed.get(20, TimeUnit.SECONDS)
        assertEquals(feedback.toList(), listOf(
            "download" to "Start downloading 1 files",
            "download" to "Successfully downloaded file with md5 $CONTENT_MD5",
            "downloaded" to "Successfully downloaded all files",
            "closed" to "Details:"
        ), feedback.toString())
    }

    @Test
    fun filesAlreadyDownloadedAreReportedAsDownloaded() {
        File(artifactsDir, CONTENT_MD5).writeText(CONTENT)

        startClient()

        closed.get(20, TimeUnit.SECONDS)
        assertEquals(feedback.take(2), listOf(
            "download" to "md5 $CONTENT_MD5 already downloaded",
            "downloaded" to "Successfully downloaded all files"
        ), feedback.toString())
    }

    @Test
    fun aDownloadOnlyActionEndsWhenTheFilesAreDownloaded() {
        val noUpdate = CompletableFuture<Unit>()

        startClient(update = "skip", listener = object : MessageListener {
            override fun onMessage(message: MessageListener.Message) {
                if (message == MessageListener.Message.Event.NoUpdate) noUpdate.complete(Unit)
            }
        })

        noUpdate.get(20, TimeUnit.SECONDS)
        downloaded.get(5, TimeUnit.SECONDS)
        assertEquals(feedback.toList(), listOf(
            "download" to "Start downloading 1 files",
            "download" to "Successfully downloaded file with md5 $CONTENT_MD5",
            "downloaded" to "Successfully downloaded all files"
        ), feedback.toString())
        assertFalse(applied.isDone)
    }

    private fun startClient(update: String = "forced", listener: MessageListener? = null) {
        server.dispatcher = FakeDdiServer(update)
        server.start()
        client = HaraClientDefaultImpl().apply {
            init(
                HaraClientData(TENANT, CONTROLLER_ID, server.url("/").toString(), gatewayToken = "token"),
                object : DirectoryForArtifactsProvider {
                    override fun directoryForArtifacts() = artifactsDir
                },
                object : ConfigDataProvider {},
                object : DeploymentPermitProvider {},
                listOfNotNull(listener),
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

    private inner class FakeDdiServer(private val update: String) : Dispatcher() {

        // Like hawkBit: a closed action, or a download-only one once downloaded, is no longer offered
        @Volatile
        private var actionOver = false

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!
            val base = "/$TENANT/controller/v1/$CONTROLLER_ID"
            return when {
                path == base -> json(if (actionOver) NOTHING_TO_DO else controllerBase())
                path.startsWith("$base/deploymentBase/42/feedback") -> {
                    val body = request.body.readUtf8()
                    val execution = EXECUTION.find(body)!!.groupValues[1]
                    feedback += execution to FIRST_DETAIL.find(body)!!.groupValues[1]
                    if (execution == "closed" || (execution == "downloaded" && update == "skip")) {
                        actionOver = true
                    }
                    if (execution == "downloaded") {
                        downloaded.complete(Unit)
                    }
                    if (execution == "closed") {
                        closed.complete(Unit)
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
             "deployment":{"download":"forced","update":"$update","maintenanceWindow":"available",
               "chunks":[{"part":"os","version":"1.0","name":"linux",
                 "artifacts":[{"filename":"image.bin","hashes":{"sha1":"s1","md5":"$CONTENT_MD5"},"size":5,
                   "_links":{"download-http":{"href":"${server.url(ARTIFACT)}"}}}]}]}}
        """

        private fun json(body: String) = MockResponse()
            .setHeader("Content-Type", "application/hal+json")
            .setBody(body)
    }

    companion object {
        private const val TENANT = "DEFAULT"
        private const val CONTROLLER_ID = "status-test"
        private const val ARTIFACT = "/files/image.bin"
        private const val CONTENT = "hello"
        private const val CONTENT_MD5 = "5d41402abc4b2a76b9719d911017c592"
        private val EXECUTION = Regex("\"execution\":\"(\\w+)\"")
        private val FIRST_DETAIL = Regex("\"details\":\\[\"([^\"]*)\"")
        private const val NOTHING_TO_DO = """{"config":{"polling":{"sleep":"00:00:01"}},"_links":{}}"""
    }
}
