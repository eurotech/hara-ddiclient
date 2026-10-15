/*
 * Copyright © 2017-2024  Kynetics, Inc.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.hara.ddiclient.api.actors

import org.eclipse.hara.ddi.api.model.CancelFeedbackRequest
import org.eclipse.hara.ddi.api.model.DeploymentBaseResponse
import org.eclipse.hara.ddiclient.api.actors.ActionManager.Companion.Message.CancelForced
import org.eclipse.hara.ddiclient.api.actors.ActionManager.Companion.Message.UpdateStopped
import org.eclipse.hara.ddiclient.api.actors.ConnectionManager.Companion.Message.Out.DeploymentCancelInfo
import org.eclipse.hara.ddiclient.api.actors.ConnectionManager.Companion.Message.Out.DeploymentInfo
import org.eclipse.hara.ddiclient.api.MessageListener
import kotlinx.coroutines.ObsoleteCoroutinesApi

@OptIn(ObsoleteCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeploymentManager
private constructor(scope: ActorScope) : AbstractActor(scope) {

    private val connectionManager = coroutineContext[CMActor]!!.ref
    private val notificationManager = coroutineContext[NMActor]!!.ref
    private fun beginningReceive(state: State): Receive = { msg ->
        when(msg) {
            is DeploymentInfo -> {
                become(downloadingReceive(state.copy(deplBaseResp = msg.info)))
                child("downloadManager")!!.send(msg)
            }

            is DeploymentCancelInfo -> {
                stopUpdateAndNotify(msg)
            }

            else -> handleMsgDefault(msg)
        }
    }

    private fun downloadingReceive(state: State): Receive = { msg ->
        when (msg) {
            is DeploymentInfo -> {
                //Send the new DeploymentInfo message to the download manager
                become(downloadingReceive(state.copy(deplBaseResp = msg.info)))
                child("downloadManager")!!.send(DeploymentInfo(msg.info))
            }
            is Message.DownloadFinished -> {
                afterDownload(state.deplBaseResp!!)
            }
            is Message.DownloadFailed -> {
                LOG.error("download failed")
                parent!!.send(msg)
            }
            is DeploymentCancelInfo -> {
                stopUpdateAndNotify(msg)
            }
            is CancelForced -> {
                stopUpdate()
            }
            else -> handleMsgDefault(msg)
        }
    }

    private suspend fun afterDownload(info: DeploymentBaseResponse) {
        when {
            // The server closed a download-only action when it got the "downloaded" feedback
            info.isDownloadOnly -> {
                LOG.info("Download-only action ${info.id}: the files are downloaded, nothing to install")
                notificationManager.send(MessageListener.Message.Event.NoUpdate)
                parent!!.send(Message.UpdateFinished)
            }

            info.isOutsideMaintenanceWindow -> {
                LOG.info("Action ${info.id}: the files are downloaded, waiting for the maintenance window")
                notificationManager.send(MessageListener.Message.State.WaitingMaintenanceWindow)
                become(waitingMaintenanceWindowReceive())
            }

            else -> {
                become(updatingReceive())
                child("updateManager")!!.send(DeploymentInfo(info))
            }
        }
    }

    // The server tells the window is open by sending the deployment again, with an update to do
    private fun waitingMaintenanceWindowReceive(): Receive = { msg ->
        when (msg) {
            is DeploymentInfo -> {
                if (!msg.info.isOutsideMaintenanceWindow) {
                    afterDownload(msg.info)
                }
            }
            is DeploymentCancelInfo -> {
                stopUpdateAndNotify(msg)
            }
            is CancelForced -> {
                stopUpdate()
            }
            else -> handleMsgDefault(msg)
        }
    }

    private fun updatingReceive(): Receive = { msg ->
        when (msg) {

            is DeploymentInfo -> {
                //Send the new DeploymentInfo message to the update manager
                child("updateManager")!!.send(DeploymentInfo(msg.info))
            }

            is Message.UpdateFailed -> {
                LOG.info("update failed")
                parent!!.send(msg)
            }

            is Message.UpdateFinished -> {
                LOG.info("update finished")
                parent!!.send(msg)
            }

            is DeploymentCancelInfo -> {
                LOG.info("can't stop update")
                connectionManager.send(ConnectionManager.Companion.Message.In.CancelFeedback(
                        CancelFeedbackRequest.newInstance(msg.info.cancelAction.stopId,
                                CancelFeedbackRequest.Status.Execution.rejected,
                                CancelFeedbackRequest.Status.Result.Finished.success,
                                "Update already started. Can't be stopped.")))
            }

            is CancelForced -> {
                LOG.info("Force cancel ignored")
            }
            else -> handleMsgDefault(msg)
        }
    }

    private suspend fun stopUpdateAndNotify(msg: DeploymentCancelInfo) {
        connectionManager.send(ConnectionManager.Companion.Message.In.CancelFeedback(
                CancelFeedbackRequest.newInstance(msg.info.cancelAction.stopId,
                        CancelFeedbackRequest.Status.Execution.closed,
                        CancelFeedbackRequest.Status.Result.Finished.success)))
        stopUpdate()
    }

    private suspend fun stopUpdate() {
        LOG.info("Stopping update")
        channel.cancel()
        notificationManager.send(MessageListener.Message.State.CancellingUpdate)
        parent!!.send(UpdateStopped)
    }

    init {
        actorOf("downloadManager") { DownloadManager.of(it) }
        actorOf("updateManager") { UpdateManager.of(it) }
        become(beginningReceive(State()))
    }

    companion object {
        fun of(scope: ActorScope) = DeploymentManager(scope)

        data class State(val deplBaseResp: DeploymentBaseResponse? = null)

        // hawkBit skips the update of a download-only action, and of any action outside its maintenance window
        private val DeploymentBaseResponse.isDownloadOnly: Boolean
            get() = deployment.update == DeploymentBaseResponse.Deployment.ProvisioningType.skip &&
                deployment.maintenanceWindow != DeploymentBaseResponse.Deployment.MaintenanceWindow.unavailable

        private val DeploymentBaseResponse.isOutsideMaintenanceWindow: Boolean
            get() = deployment.update == DeploymentBaseResponse.Deployment.ProvisioningType.skip &&
                deployment.maintenanceWindow == DeploymentBaseResponse.Deployment.MaintenanceWindow.unavailable

        sealed class Message {
            object DownloadFinished : Message()
            data class DownloadFailed(val details: List<String>) : Message()
            object UpdateFailed : Message()
            object UpdateFinished : Message()
        }
    }
}
