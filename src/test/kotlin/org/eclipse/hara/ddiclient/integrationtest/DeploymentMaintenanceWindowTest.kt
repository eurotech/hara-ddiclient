/*
 * Copyright © 2026  Eurotech S.p.A.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.hara.ddiclient.integrationtest

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.eclipse.hara.ddiclient.integrationtest.abstractions.AbstractDeploymentTest
import org.eclipse.hara.ddiclient.integrationtest.api.management.ActionStatus
import org.eclipse.hara.ddiclient.integrationtest.api.management.AssignDistributionType
import org.eclipse.hara.ddiclient.integrationtest.api.management.HawkbitAssignDistributionBody
import org.eclipse.hara.ddiclient.integrationtest.api.management.MaintenanceWindow
import org.eclipse.hara.ddiclient.integrationtest.utils.TestUtils
import org.eclipse.hara.ddiclient.integrationtest.utils.TestUtils.endMessagesOnSuccessUpdate
import org.eclipse.hara.ddiclient.integrationtest.utils.TestUtils.messagesOnSuccessfullyDownloadDistribution
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * A forced update whose maintenance window opens a little after the assignment:
 * the client downloads the files at once and installs them only in the window.
 */
class DeploymentMaintenanceWindowTest : AbstractDeploymentTest() {

    private var actionId: Int = 0

    companion object {
        const val TARGET_ID: String = "MaintenanceWindowTest"
        const val DISTRIBUTION_ID = 3
        private val WINDOW_DELAY = Duration.ofSeconds(45)
    }

    @BeforeClass
    override fun beforeTest() {
        super.beforeTest()
        setPollingTime("00:00:05")
    }

    @Test(enabled = true, timeOut = 150_000, priority = 15)
    fun testForcedUpdateWaitsForTheMaintenanceWindow() = runBlocking {

        reCreateTestTargetOnServer(TARGET_ID)

        val windowStart = Instant.now().plus(WINDOW_DELAY)
        assignForcedDistributionWithWindowAt(windowStart)

        val client = createHaraClientWithAuthorizationPermissions(TARGET_ID)

        // Shortly before the window: downloaded, not installed
        val beforeTheWindow = async {
            delay(Duration.between(Instant.now(), windowStart).minusSeconds(8).toMillis())
            managementApi.getTargetActionStatusAsync(TestUtils.basic, TARGET_ID, actionId).content
        }

        startTheTestAndWaitForResult(client,
            createTargetTestDeployment(expectedActionsAfterTheWindow))

        val statuses = beforeTheWindow.await()
        assertTrue(statuses.contains(ActionStatus.ContentEntry(
            ActionStatus.ContentEntry.Type.downloaded, listOf("Successfully downloaded all files"))), statuses.toString())
        assertFalse(statuses.any { it.messages.contains("Applying the update...") }, statuses.toString())
    }

    // hawkBit takes a Quartz cron expression: the window opens once a day at that time (UTC)
    private suspend fun assignForcedDistributionWithWindowAt(start: Instant) {
        val time = start.atOffset(ZoneOffset.UTC)
        val distribution = HawkbitAssignDistributionBody(DISTRIBUTION_ID,
            AssignDistributionType.FORCED, 0,
            MaintenanceWindow("${time.second} ${time.minute} ${time.hour} * * ?", "00:05:00", "+00:00"))
        actionId = assignDistributionToTheTarget(TARGET_ID, distribution)
    }

    private fun createTargetTestDeployment(
        actionsOnFinish: ActionStatus): TestUtils.TargetDeployments {
        val filesDownloadedPairedToServerFile = setOf(
            TestUtils.pathResolver.fromArtifact(actionId.toString()).invoke(
                TestUtils.test1Artifact) to TestUtils.locationOfFileNamed("test1"))

        return TestUtils.TargetDeployments(
            targetId = TARGET_ID,
            targetToken = "",
            deploymentInfo = listOf(
                TestUtils.TargetDeployments.DeploymentInfo(
                    actionId = actionId,
                    actionStatusOnFinish = actionsOnFinish,
                    filesDownloadedPairedWithServerFile = filesDownloadedPairedToServerFile
                )
            )
        )
    }

    private val expectedActionsAfterTheWindow: ActionStatus =
        ActionStatus(setOf(
            *endMessagesOnSuccessUpdate,
            *messagesOnSuccessfullyDownloadDistribution(
                TestUtils.md5OfFileNamed("test1"), TARGET_ID,
                "1", "test_1"),
            *TestUtils.firstActionsOnTargetDeployment
        ))
}
