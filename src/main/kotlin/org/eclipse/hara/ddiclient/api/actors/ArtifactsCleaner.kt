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

import org.eclipse.hara.ddiclient.api.HashAlgorithm
import org.eclipse.hara.ddiclient.api.PathResolver
import org.eclipse.hara.ddiclient.api.actors.FileDownloader.Companion.DOWNLOADING_EXTENSION
import org.eclipse.hara.ddiclient.api.actors.FileDownloader.Companion.hashFileExtension
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Removes the artifacts that the current update doesn't need from the artifacts directory.
 *
 * Only the files the client creates are removed: `<hash>`, `<hash>.<algorithm>` (e.g. `<hash>.sha256`)
 * and `<hash>.downloading`, where `<hash>` is an MD5, SHA-1 or SHA-256 hash.
 * Other files and directories are left alone, since the directory may be shared with the
 * integrator (e.g. /data/ota_package on Android).
 */
internal object ArtifactsCleaner {

    private val LOG = LoggerFactory.getLogger(ArtifactsCleaner::class.java)

    private val CLIENT_FILE_NAME = Regex(
        "([0-9a-f]{32}|[0-9a-f]{40}|[0-9a-f]{64})" +
            (HashAlgorithm.values().map(::hashFileExtension) + DOWNLOADING_EXTENSION)
                .joinToString("|", "(\\.(?:", "))?"))

    // Release 2.0.0 stored the artifacts in <dir>/artifacts/<actionId>/
    private val LEGACY_ACTION_DIR_NAME = Regex("[0-9]+")

    fun clean(dir: File, hashesToKeep: Set<String>) {
        deleteClientFiles(dir, hashesToKeep)
        cleanLegacyLayout(File(dir, PathResolver.ROOT))
    }

    private fun deleteClientFiles(dir: File, hashesToKeep: Set<String>) {
        dir.listFiles()?.forEach { file ->
            val hash = CLIENT_FILE_NAME.matchEntire(file.name)?.groupValues?.get(1)
            if (hash != null && hash !in hashesToKeep && file.isFile) {
                LOG.info("Removing artifact file ${file.absolutePath}")
                if (!file.delete()) {
                    LOG.warn("Can't remove artifact file ${file.absolutePath}")
                }
            }
        }
    }

    private fun cleanLegacyLayout(legacyRoot: File) {
        legacyRoot.listFiles()
            ?.filter { it.isDirectory && LEGACY_ACTION_DIR_NAME.matches(it.name) }
            ?.forEach { actionDir ->
                deleteClientFiles(actionDir, emptySet())
                deleteIfEmpty(actionDir)
            }
        deleteIfEmpty(legacyRoot)
    }

    private fun deleteIfEmpty(dir: File) {
        if (dir.list()?.isEmpty() == true && !dir.delete()) {
            LOG.warn("Can't remove directory ${dir.absolutePath}")
        }
    }
}
