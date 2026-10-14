/*
 * Copyright © 2026  Eurotech S.p.A.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.hara.ddiclient.api.actors

import org.eclipse.hara.ddi.api.model.DeploymentBaseResponse.Deployment.Chunks.Artifact
import org.eclipse.hara.ddi.api.model.DeploymentBaseResponse.Deployment.Chunks.Artifact.Links
import org.eclipse.hara.ddi.api.model.DeploymentBaseResponse.Deployment.Chunks.Artifact.Links.Link
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

class DownloadLinksTest {

    @Test
    fun anHttpsClientUsesOnlyHttpsLinks() {
        val artifact = artifact(download = HTTPS_URL, downloadHttp = HTTP_URL)

        assertEquals(DownloadLinks.urlsOf(artifact, HTTPS_SERVER), listOf(HTTPS_URL))
    }

    @Test
    fun anHttpClientTriesHttpsFirstThenHttp() {
        val artifact = artifact(download = HTTPS_URL, downloadHttp = HTTP_URL)

        assertEquals(DownloadLinks.urlsOf(artifact, HTTP_SERVER), listOf(HTTPS_URL, HTTP_URL))
    }

    @Test
    fun theOrderFollowsTheSchemeNotTheLinkName() {
        // hawkBit 1.x fills download-http with the request's scheme, so behind a TLS proxy it's HTTPS
        val artifact = artifact(download = HTTP_URL, downloadHttp = HTTPS_URL)

        assertEquals(DownloadLinks.urlsOf(artifact, HTTP_SERVER), listOf(HTTPS_URL, HTTP_URL))
    }

    @Test
    fun aSingleDownloadHttpLinkOverHttpsIsUsed() {
        val artifact = artifact(downloadHttp = HTTPS_URL)

        assertEquals(DownloadLinks.urlsOf(artifact, HTTPS_SERVER), listOf(HTTPS_URL))
    }

    @Test
    fun aSingleDownloadLinkIsUsed() {
        val artifact = artifact(download = HTTPS_URL)

        assertEquals(DownloadLinks.urlsOf(artifact, HTTPS_SERVER), listOf(HTTPS_URL))
    }

    @Test
    fun anHttpsClientGetsNoUrlFromHttpLinksOnly() {
        val artifact = artifact(downloadHttp = HTTP_URL)

        assertEquals(DownloadLinks.urlsOf(artifact, HTTPS_SERVER), emptyList<String>())
    }

    @Test
    fun noLinksGiveNoUrl() {
        assertEquals(DownloadLinks.urlsOf(artifact(), HTTP_SERVER), emptyList<String>())
    }

    @Test
    fun theSameUrlIsTriedOnce() {
        val artifact = artifact(download = HTTPS_URL, downloadHttp = HTTPS_URL)

        assertEquals(DownloadLinks.urlsOf(artifact, HTTPS_SERVER), listOf(HTTPS_URL))
    }

    @Test
    fun urlsThatAreNotHttpAreSkipped() {
        val artifact = artifact(download = "ftp://server/image.bin", downloadHttp = "not a url")

        assertEquals(DownloadLinks.urlsOf(artifact, HTTP_SERVER), emptyList<String>())
    }

    private fun artifact(download: String? = null, downloadHttp: String? = null) = Artifact(
        "image.bin",
        Artifact.Hashes("sha1", "md5"),
        5,
        Links(download?.let(::Link), null, downloadHttp?.let(::Link), null)
    )

    companion object {
        private const val HTTP_SERVER = "http://hawkbit:8080"
        private const val HTTPS_SERVER = "https://hawkbit"
        private const val HTTPS_URL = "https://hawkbit/DEFAULT/controller/v1/dev/softwaremodules/1/artifacts/image.bin"
        private const val HTTP_URL = "http://hawkbit:8080/DEFAULT/controller/v1/dev/softwaremodules/1/artifacts/image.bin"
    }
}
