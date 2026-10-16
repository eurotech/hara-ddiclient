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

import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test
import java.io.File
import java.nio.file.Files

class ArtifactsCleanerTest {

    private lateinit var dir: File

    @BeforeMethod
    fun createDir() {
        dir = Files.createTempDirectory("hara-artifacts-cleaner").toFile()
    }

    @AfterMethod
    fun deleteDir() {
        dir.deleteRecursively()
    }

    @Test
    fun keepsTheFilesOfTheCurrentUpdate() {
        files(CURRENT, "$CURRENT.md5", OTHER_CURRENT, "$OTHER_CURRENT.downloading")

        ArtifactsCleaner.clean(dir, setOf(CURRENT, OTHER_CURRENT))

        assertEquals(names(dir), setOf(CURRENT, "$CURRENT.md5", OTHER_CURRENT, "$OTHER_CURRENT.downloading"))
    }

    @Test
    fun removesTheFilesOfOldUpdates() {
        files(CURRENT, OLD, "$OLD.md5", "$OLD.downloading")

        ArtifactsCleaner.clean(dir, setOf(CURRENT))

        assertEquals(names(dir), setOf(CURRENT))
    }

    @Test
    fun removesEveryClientFileWhenNothingIsNeeded() {
        files(OLD, "$OLD.md5")

        ArtifactsCleaner.clean(dir, emptySet())

        assertEquals(names(dir), emptySet<String>())
    }

    @Test
    fun removesTheFilesOfEveryHashAlgorithm() {
        files(SHA1, "$SHA1.sha1", SHA256, "$SHA256.sha256", "$SHA256.downloading", OLD, "$OLD.md5")

        ArtifactsCleaner.clean(dir, emptySet())

        assertEquals(names(dir), emptySet<String>())
    }

    @Test
    fun keepsTheFilesOfTheCurrentUpdateNamedAfterTheirSha256() {
        files(SHA256, "$SHA256.sha256", OLD, "$OLD.md5")

        ArtifactsCleaner.clean(dir, setOf(SHA256))

        assertEquals(names(dir), setOf(SHA256, "$SHA256.sha256"))
    }

    @Test
    fun leavesFilesNotCreatedByTheClientAlone() {
        val foreign = setOf("notes.txt", OLD.dropLast(1), "$OLD.bak", "update.zip", SHA256 + "0", "$SHA256.md4")
        foreign.forEach { File(dir, it).writeText("foreign") }
        File(dir, "$OTHER_CURRENT/inner").apply { parentFile.mkdirs() }.writeText("foreign")
        File(dir, "data/inner").apply { parentFile.mkdirs() }.writeText("foreign")

        ArtifactsCleaner.clean(dir, emptySet())

        assertEquals(names(dir), foreign + OTHER_CURRENT + "data")
        assertTrue(File(dir, "$OTHER_CURRENT/inner").exists())
        assertTrue(File(dir, "data/inner").exists())
    }

    @Test
    fun removesTheLayoutOfRelease200() {
        files("artifacts/12/$OLD", "artifacts/12/$OLD.md5", "artifacts/13/$CURRENT.downloading")

        ArtifactsCleaner.clean(dir, setOf(CURRENT))

        assertFalse(File(dir, "artifacts").exists())
    }

    @Test
    fun keepsFilesNotCreatedByTheClientInTheLayoutOfRelease200() {
        files("artifacts/12/$OLD", "artifacts/12/notes.txt", "artifacts/13/$OLD", "artifacts/backup/$OLD")

        ArtifactsCleaner.clean(dir, emptySet())

        assertEquals(names(File(dir, "artifacts")), setOf("12", "backup"))
        assertEquals(names(File(dir, "artifacts/12")), setOf("notes.txt"))
        assertTrue(File(dir, "artifacts/backup/$OLD").exists())
    }

    @Test
    fun aMissingDirectoryIsNotAnError() {
        ArtifactsCleaner.clean(File(dir, "missing"), emptySet())
    }

    private fun files(vararg paths: String) = paths.forEach {
        File(dir, it).apply { parentFile.mkdirs() }.writeText("content")
    }

    private fun names(dir: File) = dir.list()!!.toSet()

    companion object {
        private const val CURRENT = "5d41402abc4b2a76b9719d911017c592"
        private const val OTHER_CURRENT = "d41d8cd98f00b204e9800998ecf8427e"
        private const val OLD = "7d793037a0760186574b0282f2f435e7"
        private const val SHA1 = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"
        private const val SHA256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    }
}
