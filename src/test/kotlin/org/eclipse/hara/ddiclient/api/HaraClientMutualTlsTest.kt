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

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.DataProvider
import org.testng.annotations.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A whole client without tokens against a fake DDI server that requires a client certificate (mTLS):
 * the TLS settings of the caller's OkHttpClient.Builder authenticate the device on every request.
 */
class HaraClientMutualTlsTest {

    private val ca = HeldCertificate.Builder().certificateAuthority(0).build()
    private val serverCertificate = HeldCertificate.Builder()
        .addSubjectAlternativeName(HOST)
        .signedBy(ca)
        .build()
    private val clientCertificate = HeldCertificate.Builder()
        .commonName(CONTROLLER_ID)
        .signedBy(ca)
        .build()

    private lateinit var server: MockWebServer
    private lateinit var artifactsDir: File
    private lateinit var client: HaraClient
    private lateinit var closedFeedback: CompletableFuture<String>
    private lateinit var installed: CompletableFuture<String>
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @BeforeMethod
    fun setUp() {
        closedFeedback = CompletableFuture()
        installed = CompletableFuture()
        requests.clear()
        artifactsDir = Files.createTempDirectory("hara-mtls").toFile()
        server = MockWebServer().apply {
            dispatcher = FakeDdiServer()
            useHttps(HandshakeCertificates.Builder()
                .heldCertificate(serverCertificate)
                .addTrustedCertificate(ca.certificate)
                .build()
                .sslSocketFactory(), false)
            requireClientAuth()
            start(InetAddress.getByName(HOST), 0)
        }
    }

    @AfterMethod
    fun tearDown() {
        client.stop()
        server.shutdown()
        artifactsDir.deleteRecursively()
    }

    @DataProvider
    fun noTokens(): Array<Array<String?>> = arrayOf(
        arrayOf(null, null),
        arrayOf("", ""),
        arrayOf(" ", null),
    )

    @Test(dataProvider = "noTokens")
    fun aDeploymentWorksWithOnlyAClientCertificate(gatewayToken: String?, targetToken: String?) {
        startClient(gatewayToken, targetToken, clientCertificate)

        val feedback = closedFeedback.get(20, TimeUnit.SECONDS)
        assertTrue(feedback.contains("\"finished\":\"success\""), feedback)
        assertEquals(installed.get(1, TimeUnit.SECONDS), CONTENT)

        // Polls, deployment, download and feedback: all authenticated by the certificate, never by a header
        val paths = requests.map { it.path!! }
        assertTrue(paths.contains(BASE), paths.toString())
        assertTrue(paths.contains(ARTIFACT), paths.toString())
        requests.forEach {
            assertEquals(it.getHeader("Authorization"), null, it.path)
            assertEquals(it.handshake?.peerPrincipal?.name, "CN=$CONTROLLER_ID", it.path)
        }
    }

    @Test
    fun withoutAClientCertificateTheServerIsNeverReached() {
        startClient(null, null, certificate = null)

        // The first poll is sent at start; give it time to fail
        Thread.sleep(3000)
        assertEquals(server.requestCount, 0)
        assertFalse(installed.isDone)
    }

    private fun startClient(gatewayToken: String?, targetToken: String?, certificate: HeldCertificate?) {
        val tls = HandshakeCertificates.Builder()
            .addTrustedCertificate(ca.certificate)
            .apply { certificate?.let { heldCertificate(it) } }
            .build()
        val httpBuilder = OkHttpClient.Builder().sslSocketFactory(tls.sslSocketFactory(), tls.trustManager)
        client = HaraClientDefaultImpl().apply {
            init(
                HaraClientData(TENANT, CONTROLLER_ID, server.url("/").toString(), gatewayToken, targetToken),
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
                object : DownloadBehavior {
                    override fun onAttempt(attempt: Int, artifactId: String, previousError: Throwable?) =
                        if (attempt == 1) DownloadBehavior.Try.After(0) else DownloadBehavior.Try.Stop
                },
                httpBuilder = httpBuilder)
            startAsync()
        }
    }

    private inner class FakeDdiServer : Dispatcher() {

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.add(request)
            val path = request.path!!
            return when {
                path == BASE -> json(if (closedFeedback.isDone) NOTHING_TO_DO else controllerBase())
                path.startsWith("$BASE/deploymentBase/42/feedback") -> {
                    val body = request.body.readUtf8()
                    if (body.contains("\"execution\":\"closed\"")) {
                        closedFeedback.complete(body)
                    }
                    MockResponse()
                }
                path.startsWith("$BASE/deploymentBase/42") -> json(deploymentBase())
                path == ARTIFACT -> MockResponse().setBody(CONTENT)
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun controllerBase() = """
            {"config":{"polling":{"sleep":"00:00:01"}},
             "_links":{"deploymentBase":{"href":"${server.url("$BASE/deploymentBase/42?c=-1")}"}}}
        """

        private fun deploymentBase() = """
            {"id":"42",
             "deployment":{"download":"forced","update":"forced","maintenanceWindow":"available",
               "chunks":[{"part":"os","version":"1.0","name":"linux",
                 "artifacts":[{"filename":"image.bin","hashes":{"sha1":"$CONTENT_SHA1","md5":"$CONTENT_MD5","sha256":"$CONTENT_SHA256"},"size":5,
                   "_links":{"download":{"href":"${server.url(ARTIFACT)}"}}}]}]}}
        """

        private fun json(body: String) = MockResponse()
            .setHeader("Content-Type", "application/hal+json")
            .setBody(body)
    }

    companion object {
        private const val HOST = "localhost"
        private const val TENANT = "DEFAULT"
        private const val CONTROLLER_ID = "mtls-test"
        private const val BASE = "/$TENANT/controller/v1/$CONTROLLER_ID"
        private const val ARTIFACT = "/files/image.bin"
        private const val CONTENT = "hello"
        private const val CONTENT_SHA1 = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"
        private const val CONTENT_MD5 ="5d41402abc4b2a76b9719d911017c592"
        private const val CONTENT_SHA256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        private const val NOTHING_TO_DO = """{"config":{"polling":{"sleep":"00:00:01"}},"_links":{}}"""
    }
}
