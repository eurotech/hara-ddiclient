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

import org.eclipse.hara.ddiclient.api.actors.ConnectionManager.Companion.feedbackOutcome
import org.testng.Assert.assertEquals
import org.testng.annotations.DataProvider
import org.testng.annotations.Test

class FeedbackOutcomeTest {

    @DataProvider
    fun codes(): Array<Array<Any>> = arrayOf(
        arrayOf(200, "DELIVERED"),
        arrayOf(204, "DELIVERED"),
        arrayOf(500, "RETRY"),
        arrayOf(503, "RETRY"),
        arrayOf(401, "RETRY"),
        arrayOf(403, "RETRY"),
        arrayOf(408, "RETRY"),
        arrayOf(409, "RETRY"),
        arrayOf(429, "RETRY"),
        arrayOf(400, "REFUSED"),
        arrayOf(404, "REFUSED"),
        arrayOf(410, "REFUSED"),
        arrayOf(302, "REFUSED"),
    )

    @Test(dataProvider = "codes")
    fun eachHttpCodeHasItsOutcome(code: Int, expected: String) {
        assertEquals(feedbackOutcome(code).name, expected)
    }
}
