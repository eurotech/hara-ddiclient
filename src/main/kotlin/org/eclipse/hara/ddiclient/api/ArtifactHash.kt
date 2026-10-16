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

import org.eclipse.hara.ddi.api.model.DeploymentBaseResponse

/**
 * The hash that identifies a downloaded artifact and checks its content.
 * The artifact file is named after [value].
 */
data class ArtifactHash(val algorithm: HashAlgorithm, val value: String) {

    override fun toString(): String = "${algorithm.name.lowercase()} $value"

    internal companion object {

        /**
         * The hash of the first algorithm in [accepted] that [hashes] has a value for, or null.
         */
        fun ofOrNull(hashes: Updater.Hashes, accepted: List<HashAlgorithm>): ArtifactHash? =
            accepted.asSequence()
                .map { algorithm -> algorithm to hashes.valueOf(algorithm) }
                .firstOrNull { (_, value) -> !value.isNullOrBlank() }
                ?.let { (algorithm, value) -> ArtifactHash(algorithm, value!!.lowercase()) }

        fun of(hashes: Updater.Hashes, accepted: List<HashAlgorithm>): ArtifactHash =
            ofOrNull(hashes, accepted) ?: throw ArtifactHashException(
                "The server sent none of the accepted hashes ${accepted.joinToString()} for the artifact with $hashes")

        // Values typed as non-null can still be null when the server omits them (Gson)
        private fun Updater.Hashes.valueOf(algorithm: HashAlgorithm): String? = when (algorithm) {
            HashAlgorithm.MD5 -> md5
            HashAlgorithm.SHA1 -> sha1
            HashAlgorithm.SHA256 -> sha256
        }
    }
}

/**
 * Thrown when an artifact to download has no hash of an accepted algorithm.
 */
internal class ArtifactHashException(message: String) : Exception(message)

internal fun DeploymentBaseResponse.Deployment.Chunks.Artifact.Hashes.toUpdaterHashes(): Updater.Hashes =
    Updater.Hashes(sha1, md5, sha256)
