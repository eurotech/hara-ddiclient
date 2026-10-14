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
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * A whole client against a fake DDI server that supports Range requests, to check that
 * interrupted downloads go on from where they stopped.
 */
class HaraClientDownloadResumeTest {

    private lateinit var server: MockWebServer
    private lateinit var artifactsDir: File
    private lateinit var client: HaraClient
    private lateinit var closedFeedback: CompletableFuture<String>
    private lateinit var installed: CompletableFuture<ByteArray>
    private val ranges: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    @BeforeMethod
    fun setUp() {
        closedFeedback = CompletableFuture()
        installed = CompletableFuture()
        ranges.clear()
        server = MockWebServer()
        artifactsDir = Files.createTempDirectory("hara-download-resume").toFile()
    }

    @AfterMethod
    fun tearDown() {
        client.stop()
        server.shutdown()
        artifactsDir.deleteRecursively()
    }

    @Test
    fun aDroppedConnectionIsResumedOnTheNextAttempt() {
        startClient(dropFirstDownload = true)

        assertSuccess()
        assertEquals(ranges.size, 2)
        assertEquals(ranges[0], null)
        val offset = ranges[1]!!.removePrefix("bytes=").removeSuffix("-").toLong()
        assertTrue(offset in 1 until CONTENT.size, "resumed from byte $offset")
    }

    @Test
    fun aPartialFileFromAnEarlierRunIsResumed() {
        tempFile().writeBytes(CONTENT.copyOf(1000))

        startClient()

        assertSuccess()
        assertEquals(ranges, listOf("bytes=1000-"))
    }

    @Test
    fun aCorruptedPartialFileIsDownloadedAgainFromTheStart() {
        tempFile().writeBytes(ByteArray(1000))

        startClient()

        assertSuccess()
        assertEquals(ranges, listOf("bytes=1000-", null))
    }

    @Test
    fun aCompletePartialFileIsOnlyChecked() {
        tempFile().writeBytes(CONTENT)

        startClient()

        assertSuccess()
        assertEquals(ranges, emptyList<String?>())
    }

    private fun tempFile() = File(artifactsDir, "$CONTENT_MD5.downloading")

    private fun assertSuccess() {
        val feedback = closedFeedback.get(20, TimeUnit.SECONDS)
        assertTrue(feedback.contains("\"finished\":\"success\""), feedback)
        assertEquals(installed.get(1, TimeUnit.SECONDS), CONTENT)
    }

    private fun startClient(dropFirstDownload: Boolean = false) {
        server.dispatcher = FakeDdiServer(dropFirstDownload)
        server.start()
        client = HaraClientDefaultImpl().apply {
            init(
                HaraClientData(TENANT, CONTROLLER_ID, server.url("/").toString(), gatewayToken = "token"),
                object : DirectoryForArtifactsProvider {
                    override fun directoryForArtifacts() = artifactsDir
                },
                object : ConfigDataProvider {},
                object : DeploymentPermitProvider {},
                emptyList(),
                listOf(object : Updater {
                    override fun apply(modules: Set<Updater.SwModuleWithPath>, messenger: Updater.Messenger) =
                        Updater.UpdateResult(true).also {
                            installed.complete(File(modules.single().artifacts.single().path).readBytes())
                        }
                }),
                object : DownloadBehavior {
                    override fun onAttempt(attempt: Int, artifactId: String, previousError: Throwable?) =
                        if (attempt <= 3) DownloadBehavior.Try.After(0) else DownloadBehavior.Try.Stop
                })
            startAsync()
        }
    }

    private inner class FakeDdiServer(private var dropNextDownload: Boolean) : Dispatcher() {

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!
            val base = "/$TENANT/controller/v1/$CONTROLLER_ID"
            return when {
                path == base -> json(if (closedFeedback.isDone) NOTHING_TO_DO else controllerBase())
                path.startsWith("$base/deploymentBase/42/feedback") -> {
                    val body = request.body.readUtf8()
                    if (body.contains("\"execution\":\"closed\"")) {
                        closedFeedback.complete(body)
                    }
                    MockResponse()
                }
                path.startsWith("$base/deploymentBase/42") -> json(deploymentBase())
                path == ARTIFACT -> artifact(request.getHeader("Range"))
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun artifact(range: String?): MockResponse {
            ranges += range
            val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val response = MockResponse().setBody(Buffer().write(CONTENT, start, CONTENT.size - start))
            if (range != null) {
                response.setResponseCode(206).setHeader("Content-Range", "bytes $start-${CONTENT.size - 1}/${CONTENT.size}")
            }
            if (dropNextDownload) {
                // Sends half the body, then closes the connection
                dropNextDownload = false
                response.socketPolicy = SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY
            }
            return response
        }

        private fun controllerBase() = """
            {"config":{"polling":{"sleep":"00:00:01"}},
             "_links":{"deploymentBase":{"href":"${server.url("/$TENANT/controller/v1/$CONTROLLER_ID/deploymentBase/42?c=-1")}"}}}
        """

        private fun deploymentBase() = """
            {"id":"42",
             "deployment":{"download":"forced","update":"forced","maintenanceWindow":"available",
               "chunks":[{"part":"os","version":"1.0","name":"linux",
                 "artifacts":[{"filename":"image.bin","hashes":{"sha1":"s1","md5":"$CONTENT_MD5"},"size":${CONTENT.size},
                   "_links":{"download-http":{"href":"${server.url(ARTIFACT)}"}}}]}]}}
        """

        private fun json(body: String) = MockResponse()
            .setHeader("Content-Type", "application/hal+json")
            .setBody(body)
    }

    companion object {
        private const val TENANT = "DEFAULT"
        private const val CONTROLLER_ID = "resume-test"
        private const val ARTIFACT = "/files/image.bin"
        private val CONTENT = Random(42).nextBytes(100_000)
        private val CONTENT_MD5 = MessageDigest.getInstance("MD5").digest(CONTENT).joinToString("") { "%02x".format(it) }
        private const val NOTHING_TO_DO = """{"config":{"polling":{"sleep":"00:00:01"}},"_links":{}}"""
    }
}
