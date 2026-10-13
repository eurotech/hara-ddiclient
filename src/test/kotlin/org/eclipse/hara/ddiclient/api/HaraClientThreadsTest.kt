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

import org.testng.Assert.assertEquals
import org.testng.annotations.Test
import java.io.File
import java.net.ServerSocket

class HaraClientThreadsTest {

    // Pools that are shared, or whose idle threads exit on their own
    private val pooledThreadPrefixes = listOf(
        "OkHttp ",
        "DefaultDispatcher-worker-",
        "kotlinx.coroutines.DefaultExecutor"
    )

    @Test
    fun startingAndStoppingClientsLeavesNoThreadsBehind() {
        // Nothing listens on this port: every poll fails, so each client also restarts its
        // polling timer once for the backoff
        val closedPort = ServerSocket(0).use { it.localPort }
        val before = liveThreads()

        repeat(10) {
            val client = client("http://localhost:$closedPort")
            client.startAsync()
            Thread.sleep(300)
            client.stop()
        }
        Thread.sleep(1_000)

        val leaked = (liveThreads() - before)
            .map { it.name }
            .filterNot { name -> pooledThreadPrefixes.any { name.startsWith(it) } }
        assertEquals(leaked, emptyList<String>())
    }

    private fun liveThreads(): Set<Thread> = Thread.getAllStackTraces().keys.filter { it.isAlive }.toSet()

    private fun client(serverUrl: String) = HaraClientDefaultImpl().apply {
        init(
            HaraClientData("DEFAULT", "threads-test", serverUrl, gatewayToken = "token"),
            object : DirectoryForArtifactsProvider {
                override fun directoryForArtifacts() = File("build/test/threads")
            },
            object : ConfigDataProvider {},
            object : DeploymentPermitProvider {},
            emptyList(),
            emptyList(),
            object : DownloadBehavior {
                override fun onAttempt(attempt: Int, artifactId: String, previousError: Throwable?) =
                    DownloadBehavior.Try.Stop
            })
    }
}
