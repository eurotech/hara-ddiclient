/*
 * Copyright © 2026  Eurotech S.p.A.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.hara.ddiclient.api.inputstream

import org.testng.Assert.assertEquals
import org.testng.annotations.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class FilterInputStreamWithProgressTest {

    @Test
    fun singleByteReadsCountOneByteEach() {
        // 0xFF bytes: the byte value must not be what's counted
        val stream = FilterInputStreamWithProgress(ByteArrayInputStream(ByteArray(4) { -1 }), 8)

        repeat(4) { stream.read() }

        assertEquals(stream.getProgress(), 0.5)
    }

    @Test
    fun theEndOfTheStreamIsNotCounted() {
        val stream = FilterInputStreamWithProgress(ByteArrayInputStream(ByteArray(4)), 4)

        stream.read(ByteArray(4), 0, 4)
        repeat(3) { stream.read(ByteArray(4), 0, 4) }
        repeat(3) { stream.read() }

        assertEquals(stream.getProgress(), 1.0)
    }

    @Test
    fun progressIsRightAboveTwoGiB() {
        val total = 3L * GiB
        val stream = FilterInputStreamWithProgress(Zeros(total), total)
        val buffer = ByteArray(1 shl 20)

        var read = 0L
        while (read < 5L * GiB / 2) {
            read += stream.read(buffer, 0, buffer.size)
        }

        assertEquals(stream.getProgress(), 2.5 / 3, 1e-9)
    }

    @Test
    fun anEmptyFileIsComplete() {
        val stream = FilterInputStreamWithProgress(ByteArrayInputStream(ByteArray(0)), 0)

        stream.read()

        assertEquals(stream.getProgress(), 1.0)
    }

    // Reports bytes as read without filling the buffer, so gigabytes cost nothing
    private class Zeros(private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining-- > 0) 0 else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = minOf(len.toLong(), remaining).toInt()
            remaining -= n
            return n
        }
    }

    companion object {
        private const val GiB = 1L shl 30
    }
}
