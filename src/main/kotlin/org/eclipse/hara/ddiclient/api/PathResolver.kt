/*
 * Copyright © 2017-2024  Kynetics, Inc.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.hara.ddiclient.api

import java.io.File

class PathResolver @JvmOverloads constructor(
    private val dfap: DirectoryForArtifactsProvider,
    private val artifactHashes: List<HashAlgorithm> = HaraClientData.DEFAULT_ARTIFACT_HASHES
) {

    companion object {
        const val ROOT = "artifacts"
    }

    fun fromArtifact(id: String): (artifact: Updater.SwModule.Artifact) -> String {
        return { artifact ->
            File(dfap.directoryForArtifacts(), hashOf(artifact.hashes).value).absolutePath
        }
    }

    fun baseDirectory(): File {
        return dfap.directoryForArtifacts()
    }

    fun updateDir(actionId: String): File {
        return baseDirectory()
    }

    internal fun hashOf(hashes: Updater.Hashes): ArtifactHash = ArtifactHash.of(hashes, artifactHashes)

    internal fun hashOrNull(hashes: Updater.Hashes): ArtifactHash? = ArtifactHash.ofOrNull(hashes, artifactHashes)
}
