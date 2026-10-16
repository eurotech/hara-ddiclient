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

/**
 * Hash algorithms the client can use to identify and check a downloaded artifact.
 *
 * @property digestName name of the algorithm for [java.security.MessageDigest]
 */
enum class HashAlgorithm(val digestName: String) {
    MD5("MD5"),
    SHA1("SHA-1"),
    SHA256("SHA-256")
}
