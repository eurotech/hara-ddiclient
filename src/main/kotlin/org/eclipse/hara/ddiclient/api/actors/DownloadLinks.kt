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

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.eclipse.hara.ddi.api.model.DeploymentBaseResponse.Deployment.Chunks.Artifact

/**
 * Chooses the URLs an artifact is downloaded from, in the order they are tried.
 *
 * The server may send a `download` link (meant for HTTPS) and a `download-http` link (meant for
 * HTTP); which ones are sent and what they point at depends on the server configuration.
 * HTTPS URLs come first. Plain HTTP URLs are used only when the client itself talks to the server
 * over HTTP: a client configured for HTTPS never downgrades a download to HTTP.
 */
internal object DownloadLinks {

    fun urlsOf(artifact: Artifact, serverUrl: String): List<String> {
        val allowPlainHttp = serverUrl.toHttpUrlOrNull()?.isHttps == false
        return listOfNotNull(artifact._links.download?.href, artifact._links.download_http?.href)
            .distinct()
            .mapNotNull { href -> href.toHttpUrlOrNull()?.let { href to it.isHttps } }
            .filter { (_, isHttps) -> isHttps || allowPlainHttp }
            .sortedByDescending { (_, isHttps) -> isHttps }
            .map { (href, _) -> href }
    }
}
