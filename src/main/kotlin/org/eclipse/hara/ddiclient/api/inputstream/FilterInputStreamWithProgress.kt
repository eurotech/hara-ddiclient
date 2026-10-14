/*
 * Copyright © 2017-2024  Kynetics, Inc.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.hara.ddiclient.api.inputstream

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

class FilterInputStreamWithProgress(
    inputStream: InputStream,
    private val totalSize: Long
) : FilterInputStream(inputStream) {

    // Long for files above 2 GiB; volatile because the progress is read from a timer thread
    @Volatile
    private var alreadyRead: Long = 0

    @Throws(IOException::class)
    override fun read(): Int {
        return `in`.read().also {
            if (it >= 0) alreadyRead++
        }
    }

    @Throws(IOException::class)
    override fun read(var1: ByteArray, var2: Int, var3: Int): Int {
        return `in`.read(var1, var2, var3).also {
            if (it > 0) alreadyRead += it
        }
    }

    fun getProgress(): Double {
        if (totalSize <= 0) return 1.0
        return min(alreadyRead.toDouble() / totalSize, 1.0)
    }
}
