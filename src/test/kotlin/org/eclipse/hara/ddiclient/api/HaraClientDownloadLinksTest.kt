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
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A whole client against a fake DDI server, to check which artifact links the downloads use.
 */
class HaraClientDownloadLinksTest {

    private lateinit var server: MockWebServer
    private lateinit var artifactsDir: File
    private lateinit var client: HaraClient
    private lateinit var closedFeedback: CompletableFuture<String>
    private lateinit var installed: CompletableFuture<String>

    @BeforeMethod
    fun setUp() {
        closedFeedback = CompletableFuture()
        installed = CompletableFuture()
        server = MockWebServer()
        artifactsDir = Files.createTempDirectory("hara-download-links").toFile()
    }

    @AfterMethod
    fun tearDown() {
        client.stop()
        server.shutdown()
        artifactsDir.deleteRecursively()
    }

    @Test
    fun anArtifactWithOnlyTheDownloadLinkIsDownloaded() {
        startClient(download = WORKING, downloadHttp = null)

        assertSuccess()
    }

    @Test
    fun theNextLinkIsTriedWhenTheFirstOneFails() {
        startClient(download = BROKEN, downloadHttp = WORKING)

        assertSuccess()
    }

    @Test
    fun anArtifactWithoutUsableLinksFailsTheAction() {
        startClient(download = null, downloadHttp = null)

        val feedback = closedFeedback.get(20, TimeUnit.SECONDS)
        assertTrue(feedback.contains("\"finished\":\"failure\""), feedback)
        assertTrue(feedback.contains("No usable download link for image.bin"), feedback)
    }

    private fun assertSuccess() {
        val feedback = closedFeedback.get(20, TimeUnit.SECONDS)
        assertTrue(feedback.contains("\"finished\":\"success\""), feedback)
        assertEquals(installed.get(1, TimeUnit.SECONDS), CONTENT)
    }

    private fun startClient(download: String?, downloadHttp: String?) {
        server.dispatcher = FakeDdiServer(download, downloadHttp)
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
                            installed.complete(File(modules.single().artifacts.single().path).readText())
                        }
                }),
                // One attempt only: a link that isn't used shows up as a failure
                object : DownloadBehavior {
                    override fun onAttempt(attempt: Int, artifactId: String, previousError: Throwable?) =
                        if (attempt == 1) DownloadBehavior.Try.After(0) else DownloadBehavior.Try.Stop
                })
            startAsync()
        }
    }

    private inner class FakeDdiServer(private val download: String?, private val downloadHttp: String?) : Dispatcher() {

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
                path == WORKING -> MockResponse().setBody(CONTENT)
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun controllerBase() = """
            {"config":{"polling":{"sleep":"00:00:01"}},
             "_links":{"deploymentBase":{"href":"${server.url("/$TENANT/controller/v1/$CONTROLLER_ID/deploymentBase/42?c=-1")}"}}}
        """

        private fun deploymentBase(): String {
            val links = listOfNotNull(
                download?.let { "\"download\":{\"href\":\"${server.url(it)}\"}" },
                downloadHttp?.let { "\"download-http\":{\"href\":\"${server.url(it)}\"}" }
            ).joinToString(",")
            return """
                {"id":"42",
                 "deployment":{"download":"forced","update":"forced","maintenanceWindow":"available",
                   "chunks":[{"part":"os","version":"1.0","name":"linux",
                     "artifacts":[{"filename":"image.bin","hashes":{"sha1":"s1","md5":"$CONTENT_MD5"},"size":5,
                       "_links":{$links}}]}]}}
            """
        }

        private fun json(body: String) = MockResponse()
            .setHeader("Content-Type", "application/hal+json")
            .setBody(body)
    }

    companion object {
        private const val TENANT = "DEFAULT"
        private const val CONTROLLER_ID = "links-test"
        private const val WORKING = "/files/image.bin"
        private const val BROKEN = "/missing/image.bin"
        private const val CONTENT = "hello"
        private const val CONTENT_MD5 = "5d41402abc4b2a76b9719d911017c592"
        private const val NOTHING_TO_DO = """{"config":{"polling":{"sleep":"00:00:01"}},"_links":{}}"""
    }
}
