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

import org.eclipse.hara.ddiclient.api.HashAlgorithm.MD5
import org.eclipse.hara.ddiclient.api.HashAlgorithm.SHA1
import org.eclipse.hara.ddiclient.api.HashAlgorithm.SHA256
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.Assert.expectThrows
import org.testng.annotations.Test

class ArtifactHashTest {

    @Test
    fun sha256IsPreferredByDefault() {
        assertEquals(ArtifactHash.of(ALL, HaraClientData.DEFAULT_ARTIFACT_HASHES), ArtifactHash(SHA256, SHA256_VALUE))
    }

    @Test
    fun md5IsUsedByDefaultWhenTheServerSendsNoSha256() {
        assertEquals(ArtifactHash.of(NO_SHA256, HaraClientData.DEFAULT_ARTIFACT_HASHES), ArtifactHash(MD5, MD5_VALUE))
    }

    @Test
    fun aBlankHashCountsAsMissing() {
        assertEquals(ArtifactHash.of(NO_SHA256.copy(sha256 = " "), HaraClientData.DEFAULT_ARTIFACT_HASHES),
            ArtifactHash(MD5, MD5_VALUE))
    }

    @Test
    fun theOrderOfTheAcceptedAlgorithmsWins() {
        assertEquals(ArtifactHash.of(ALL, listOf(MD5, SHA256)), ArtifactHash(MD5, MD5_VALUE))
        assertEquals(ArtifactHash.of(ALL, listOf(SHA1)), ArtifactHash(SHA1, SHA1_VALUE))
    }

    @Test
    fun theValueIsLowercase() {
        assertEquals(ArtifactHash.of(ALL.copy(sha256 = SHA256_VALUE.uppercase()), listOf(SHA256)).value, SHA256_VALUE)
    }

    @Test
    fun noAcceptedHashIsAnError() {
        val e = expectThrows(ArtifactHashException::class.java) { ArtifactHash.of(NO_SHA256, listOf(SHA256)) }

        assertTrue(e.message!!.startsWith("The server sent none of the accepted hashes SHA256"), e.message)
        assertNull(ArtifactHash.ofOrNull(NO_SHA256, listOf(SHA256)))
    }

    @Test
    fun toStringNamesTheAlgorithm() {
        assertEquals(ArtifactHash(SHA256, SHA256_VALUE).toString(), "sha256 $SHA256_VALUE")
    }

    companion object {
        private const val MD5_VALUE = "5d41402abc4b2a76b9719d911017c592"
        private const val SHA1_VALUE = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"
        private const val SHA256_VALUE = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        private val ALL = Updater.Hashes(SHA1_VALUE, MD5_VALUE, SHA256_VALUE)
        private val NO_SHA256 = Updater.Hashes(SHA1_VALUE, MD5_VALUE)
    }
}
