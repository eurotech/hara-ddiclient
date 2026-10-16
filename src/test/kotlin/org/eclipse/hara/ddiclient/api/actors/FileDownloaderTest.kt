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

import org.eclipse.hara.ddiclient.api.ArtifactHash
import org.eclipse.hara.ddiclient.api.HashAlgorithm
import org.eclipse.hara.ddiclient.api.actors.FileDownloader.Companion.FileToDownload
import org.eclipse.hara.ddiclient.api.actors.FileDownloader.Companion.digest
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class FileDownloaderTest {

    private lateinit var folder: File

    @BeforeMethod
    fun createFolder() {
        folder = Files.createTempDirectory("hara-file-downloader").toFile()
    }

    @AfterMethod
    fun deleteFolder() {
        folder.deleteRecursively()
    }

    @Test
    fun md5IsLowercaseHex() {
        val file = File(folder, "hello").apply { writeText("hello") }

        assertEquals(file.digest(HashAlgorithm.MD5), "5d41402abc4b2a76b9719d911017c592")
    }

    @Test
    fun sha1IsLowercaseHex() {
        val file = File(folder, "hello").apply { writeText("hello") }

        assertEquals(file.digest(HashAlgorithm.SHA1), "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d")
    }

    @Test
    fun sha256IsLowercaseHex() {
        val file = File(folder, "hello").apply { writeText("hello") }

        assertEquals(file.digest(HashAlgorithm.SHA256), SHA256)
    }

    @Test
    fun md5OfAnEmptyFile() {
        val file = File(folder, "empty").apply { writeBytes(ByteArray(0)) }

        assertEquals(file.digest(HashAlgorithm.MD5), "d41d8cd98f00b204e9800998ecf8427e")
    }

    @Test
    fun sha256OfAFileLargerThanTheReadBuffer() {
        val content = ByteArray(10_000) { (it % 256).toByte() }
        val file = File(folder, "large").apply { writeBytes(content) }
        val expected = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

        assertEquals(file.digest(HashAlgorithm.SHA256), expected)
    }

    @Test
    fun filesAreNamedAfterTheHash() {
        val toDownload = FileToDownload("image.bin", ArtifactHash(HashAlgorithm.SHA256, SHA256),
            listOf("http://server/image.bin"), folder, 5)

        assertEquals(toDownload.tempFile, File(folder, "$SHA256.downloading"))
        assertEquals(toDownload.destination, File(folder, SHA256))
        assertEquals(toDownload.destinationHashFile, File(folder, "$SHA256.sha256"))
    }

    @Test
    fun theHashFileIsNamedAfterTheAlgorithm() {
        val toDownload = FileToDownload("image.bin", ArtifactHash(HashAlgorithm.MD5, MD5),
            listOf("http://server/image.bin"), folder, 5)

        assertEquals(toDownload.destination, File(folder, MD5))
        assertEquals(toDownload.destinationHashFile, File(folder, "$MD5.md5"))
    }

    @Test
    fun onFileSavedMovesTheTempFileToTheDestination() {
        val toDownload = FileToDownload("image.bin", ArtifactHash(HashAlgorithm.SHA256, SHA256),
            listOf("http://server/image.bin"), folder, 5)
        toDownload.tempFile.writeText("hello")

        assertTrue(toDownload.onFileSaved())
        assertFalse(toDownload.tempFile.exists())
        assertEquals(toDownload.destination.readText(), "hello")
    }

    companion object {
        private const val MD5 = "5d41402abc4b2a76b9719d911017c592"
        private const val SHA256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    }
}
