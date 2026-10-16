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
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A whole client against a fake DDI server, to check which hash identifies and checks
 * the downloaded artifact.
 */
class HaraClientArtifactHashTest {

    private lateinit var server: MockWebServer
    private lateinit var artifactsDir: File
    private lateinit var client: HaraClient
    private lateinit var closed: CompletableFuture<Unit>
    private lateinit var appliedFiles: CompletableFuture<List<String>>
    private val feedback: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var artifactRequested = false

    @BeforeMethod
    fun setUp() {
        closed = CompletableFuture()
        appliedFiles = CompletableFuture()
        feedback.clear()
        artifactRequested = false
        server = MockWebServer()
        artifactsDir = Files.createTempDirectory("hara-artifact-hash").toFile()
    }

    @AfterMethod
    fun tearDown() {
        client.stop()
        server.shutdown()
        artifactsDir.deleteRecursively()
    }

    @Test
    fun theFileIsCheckedAndNamedWithTheSha256() {
        startClient(hashes = """{"sha1":"$SHA1","md5":"$MD5","sha256":"$SHA256"}""")

        closed.get(20, TimeUnit.SECONDS)
        assertTrue(feedback.contains("download" to "Successfully downloaded file with sha256 $SHA256"), feedback.toString())
        assertEquals(appliedFiles.get(5, TimeUnit.SECONDS), listOf(File(artifactsDir, SHA256).absolutePath))
        assertEquals(File(artifactsDir, "$SHA256.sha256").readText(), SHA256)
    }

    @Test
    fun theMd5IsUsedWhenTheServerSendsNoSha256() {
        startClient(hashes = """{"sha1":"$SHA1","md5":"$MD5"}""")

        closed.get(20, TimeUnit.SECONDS)
        assertTrue(feedback.contains("download" to "Successfully downloaded file with md5 $MD5"), feedback.toString())
        assertEquals(appliedFiles.get(5, TimeUnit.SECONDS), listOf(File(artifactsDir, MD5).absolutePath))
    }

    @Test
    fun aWrongSha256FailsTheDownloadEvenIfTheMd5Matches() {
        startClient(hashes = """{"sha1":"$SHA1","md5":"$MD5","sha256":"$WRONG_SHA256"}""")

        closed.get(20, TimeUnit.SECONDS)
        val (execution, detail) = feedback.last()
        assertEquals(execution, "closed")
        assertTrue(detail.startsWith("Failed to download file with sha256 $WRONG_SHA256"), detail)
        assertFalse(appliedFiles.isDone)
        assertFalse(File(artifactsDir, MD5).exists())
    }

    @Test
    fun anArtifactWithoutAnAcceptedHashIsNotDownloaded() {
        startClient(hashes = """{"sha1":"$SHA1","md5":"$MD5"}""", artifactHashes = listOf(HashAlgorithm.SHA256))

        closed.get(20, TimeUnit.SECONDS)
        assertEquals(feedback.size, 1, feedback.toString())
        val (execution, detail) = feedback.single()
        assertEquals(execution, "closed")
        assertTrue(detail.startsWith(
            "Failed to select the files to download: The server sent none of the accepted hashes SHA256"), detail)
        assertFalse(artifactRequested)
        assertFalse(appliedFiles.isDone)
    }

    private fun startClient(hashes: String, artifactHashes: List<HashAlgorithm> = HaraClientData.DEFAULT_ARTIFACT_HASHES) {
        server.dispatcher = FakeDdiServer(hashes)
        server.start()
        client = HaraClientDefaultImpl().apply {
            init(
                HaraClientData(TENANT, CONTROLLER_ID, server.url("/").toString(), gatewayToken = "token",
                    artifactHashes = artifactHashes),
                object : DirectoryForArtifactsProvider {
                    override fun directoryForArtifacts() = artifactsDir
                },
                object : ConfigDataProvider {},
                object : DeploymentPermitProvider {},
                emptyList(),
                listOf(object : Updater {
                    override fun apply(modules: Set<Updater.SwModuleWithPath>, messenger: Updater.Messenger) =
                        Updater.UpdateResult(true).also {
                            appliedFiles.complete(modules.flatMap { m -> m.artifacts.map { it.path } })
                        }
                }),
                object : DownloadBehavior {
                    override fun onAttempt(attempt: Int, artifactId: String, previousError: Throwable?) =
                        if (attempt == 1) DownloadBehavior.Try.After(0) else DownloadBehavior.Try.Stop
                })
            startAsync()
        }
    }

    private inner class FakeDdiServer(private val hashes: String) : Dispatcher() {

        // Like hawkBit: a closed action is no longer offered
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
                    if (execution == "closed") {
                        actionOver = true
                        closed.complete(Unit)
                    }
                    MockResponse()
                }
                path.startsWith("$base/deploymentBase/42") -> json(deploymentBase())
                path == ARTIFACT -> MockResponse().setBody(CONTENT).also { artifactRequested = true }
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun controllerBase() = """
            {"config":{"polling":{"sleep":"00:00:01"}},
             "_links":{"deploymentBase":{"href":"${server.url("/$TENANT/controller/v1/$CONTROLLER_ID/deploymentBase/42?c=-1")}"}}}
        """

        private fun deploymentBase() = """
            {"id":"42",
             "deployment":{"download":"forced","update":"forced","maintenanceWindow":"available",
               "chunks":[{"part":"os","version":"1.0","name":"linux",
                 "artifacts":[{"filename":"image.bin","hashes":$hashes,"size":5,
                   "_links":{"download-http":{"href":"${server.url(ARTIFACT)}"}}}]}]}}
        """

        private fun json(body: String) = MockResponse()
            .setHeader("Content-Type", "application/hal+json")
            .setBody(body)
    }

    companion object {
        private const val TENANT = "DEFAULT"
        private const val CONTROLLER_ID = "hash-test"
        private const val ARTIFACT = "/files/image.bin"
        private const val CONTENT = "hello"
        private const val MD5 = "5d41402abc4b2a76b9719d911017c592"
        private const val SHA1 = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"
        private const val SHA256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        private const val WRONG_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"
        private val EXECUTION = Regex("\"execution\":\"(\\w+)\"")
        private val FIRST_DETAIL = Regex("\"details\":\\[\"([^\"]*)\"")
        private const val NOTHING_TO_DO = """{"config":{"polling":{"sleep":"00:00:01"}},"_links":{}}"""
    }
}
