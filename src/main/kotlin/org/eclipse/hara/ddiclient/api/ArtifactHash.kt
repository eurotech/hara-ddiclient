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
 * The artifact file is named after [value], so it must be lower-case hex of the length
 * of [algorithm]: anything else (a path, for example) is rejected.
 */
data class ArtifactHash(val algorithm: HashAlgorithm, val value: String) {

    init {
        require(isValid(algorithm, value)) { "Not a lower-case hex ${algorithm.name} hash: $value" }
    }

    override fun toString(): String = "${algorithm.name.lowercase()} $value"

    internal companion object {

        /**
         * The hash of the first algorithm in [accepted] that [hashes] has a value for, or null
         * if there's none or that value is malformed.
         */
        fun ofOrNull(hashes: Updater.Hashes, accepted: List<HashAlgorithm>): ArtifactHash? =
            select(hashes, accepted)?.takeIf { (algorithm, value) -> isValid(algorithm, value) }
                ?.let { (algorithm, value) -> ArtifactHash(algorithm, value) }

        fun of(hashes: Updater.Hashes, accepted: List<HashAlgorithm>): ArtifactHash {
            val (algorithm, value) = select(hashes, accepted) ?: throw ArtifactHashException(
                "The server sent none of the accepted hashes ${accepted.joinToString()} for the artifact with $hashes")
            // A malformed hash fails the artifact: falling back to the next algorithm would weaken the check
            if (!isValid(algorithm, value)) {
                throw ArtifactHashException("The server sent a malformed ${algorithm.name} hash for the artifact with $hashes")
            }
            return ArtifactHash(algorithm, value)
        }

        private fun select(hashes: Updater.Hashes, accepted: List<HashAlgorithm>): Pair<HashAlgorithm, String>? =
            accepted.asSequence()
                .map { algorithm -> algorithm to hashes.valueOf(algorithm) }
                .firstOrNull { (_, value) -> !value.isNullOrBlank() }
                ?.let { (algorithm, value) -> algorithm to value!!.lowercase() }

        private fun isValid(algorithm: HashAlgorithm, value: String): Boolean =
            value.length == algorithm.hexLength && value.all { it in '0'..'9' || it in 'a'..'f' }

        private val HashAlgorithm.hexLength: Int
            get() = when (this) {
                HashAlgorithm.MD5 -> 32
                HashAlgorithm.SHA1 -> 40
                HashAlgorithm.SHA256 -> 64
            }

        private fun Updater.Hashes.valueOf(algorithm: HashAlgorithm): String? = when (algorithm) {
            HashAlgorithm.MD5 -> md5
            HashAlgorithm.SHA1 -> sha1
            HashAlgorithm.SHA256 -> sha256
        }
    }
}

/**
 * Thrown when an artifact to download has no hash of an accepted algorithm, or a malformed one.
 */
internal class ArtifactHashException(message: String) : Exception(message)

internal fun DeploymentBaseResponse.Deployment.Chunks.Artifact.Hashes.toUpdaterHashes(): Updater.Hashes =
    Updater.Hashes(sha1, md5, sha256)
