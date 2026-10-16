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

import org.eclipse.hara.ddi.api.model.DeploymentBaseResponse
import org.eclipse.hara.ddi.api.model.DeploymentBaseResponse.Deployment.ProvisioningType.attempt
import org.eclipse.hara.ddi.api.model.DeploymentBaseResponse.Deployment.ProvisioningType.forced
import org.eclipse.hara.ddi.api.model.DeploymentFeedbackRequest
import org.eclipse.hara.ddi.api.model.DeploymentFeedbackRequest.Status.Execution
import org.eclipse.hara.ddi.api.model.DeploymentFeedbackRequest.Status.Execution.closed
import org.eclipse.hara.ddi.api.model.DeploymentFeedbackRequest.Status.Execution.proceeding
import org.eclipse.hara.ddi.api.model.DeploymentFeedbackRequest.Status.Result.Finished
import org.eclipse.hara.ddi.api.model.DeploymentFeedbackRequest.Status.Result.Finished.none
import org.eclipse.hara.ddi.api.model.DeploymentFeedbackRequest.Status.Result.Finished.success
import org.eclipse.hara.ddi.api.model.DeploymentFeedbackRequest.Status.Result.Finished.failure
import org.eclipse.hara.ddi.api.model.DeploymentFeedbackRequest.Status.Result.Progress
import org.eclipse.hara.ddiclient.api.actors.ConnectionManager.Companion.Message.In.DeploymentFeedback
import org.eclipse.hara.ddiclient.api.actors.ConnectionManager.Companion.Message.Out.DeploymentInfo
import org.eclipse.hara.ddiclient.api.actors.DeploymentManager.Companion.Message.DownloadFailed
import org.eclipse.hara.ddiclient.api.actors.DeploymentManager.Companion.Message.DownloadFinished
import org.eclipse.hara.ddiclient.api.actors.DownloadManager.Companion.State.Download
import org.eclipse.hara.ddiclient.api.actors.DownloadManager.Companion.State.Download.State.Status
import org.eclipse.hara.ddiclient.api.actors.FileDownloader.Companion.FileToDownload
import org.eclipse.hara.ddiclient.api.MessageListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.eclipse.hara.ddi.api.model.CancelFeedbackRequest
import org.eclipse.hara.ddiclient.api.DeploymentPermitProvider
import org.eclipse.hara.ddiclient.api.ArtifactHash
import org.eclipse.hara.ddiclient.api.ArtifactHashException
import org.eclipse.hara.ddiclient.api.UpdaterException
import org.eclipse.hara.ddiclient.api.actors.FileDownloader.Companion.digest
import org.eclipse.hara.ddiclient.api.toUpdaterHashes

@OptIn(ObsoleteCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class DownloadManager
private constructor(scope: ActorScope) : AbstractActor(scope) {

    private val registry = coroutineContext[HaraClientContext]!!.registry
    private val pathResolver = coroutineContext[HaraClientContext]!!.pathResolver
    private val serverUrl = coroutineContext[HaraClientContext]!!.serverUrl
    private val notificationManager = coroutineContext[NMActor]!!.ref
    private val connectionManager = coroutineContext[CMActor]!!.ref
    private val softRequest: DeploymentPermitProvider = coroutineContext[HaraClientContext]!!.softDeploymentPermitProvider
    private val forceRequest: DeploymentPermitProvider = coroutineContext[HaraClientContext]!!.forceDeploymentPermitProvider
    private var waitingAuthJob: Job? = null

    @ExperimentalCoroutinesApi
    private fun beforeStartReceive(): Receive = { msg ->
        when (msg) {

            is DeploymentInfo -> failDownloadOnSelectionError(msg.info.id) {
                checkAndHandleFilesAlreadyDownloaded(msg) {

                    when {
                        msg.isDownloadForced -> {
                            forceDownloadTheArtifact(msg)
                        }

                        else -> {
                            attemptDownloadingTheArtifact(State(msg.info), msg)
                        }
                    }
                }
            }

            else -> handleMsgDefault(msg)
        }
    }


    private suspend fun forceDownloadTheArtifact(msg: DeploymentInfo) {
        val message = "Start downloading artifacts"
        LOG.info(message)
        forceRequest.onAuthorizationReceive {
            startDownloadProcedure(msg)
        }
    }

    private suspend fun attemptDownloadingTheArtifact(state: State, msg: DeploymentInfo) {
        val message = "Waiting authorization to download"
        LOG.info(message)
        feedback(msg.info.id, proceeding, Progress(0, 0), none, message)
        become(waitingDownloadAuthorization(state.copy(deplBaseResp = msg.info)))
        waitingAuthJob?.cancel()
        waitingAuthJob = launch {
            softRequest.onAuthorizationReceive(onWaitForAuthorization = {
                notificationManager.send(MessageListener.Message.State.WaitingDownloadAuthorization())
            }, onGrantAuthorization = {
                channel.send(Message.DownloadGranted)
            })
            waitingAuthJob = null
        }
    }

    private suspend fun checkAndHandleFilesAlreadyDownloaded(msg: DeploymentInfo,
                                                             block: suspend () -> Unit) {
        val alreadyDownloaded = mutableMapOf<ArtifactHash, Boolean>()
        val wd = pathResolver.updateDir(msg.info.id)
        val hashes = hashesOfFilesToBeDownloaded(msg.info)
        val dms = createDownloadsManagers(msg.info, hashes)

        msg.info.artifactsWithHashIn(hashes).forEach { (at, hash) ->
            val ftd = FileToDownload(at.filename, hash,
                DownloadLinks.urlsOf(at, serverUrl), wd, at.size)
            alreadyDownloaded[hash] = ftd.destination.exists() && (hash.value == ftd.destination.digest(hash.algorithm) ||
                    (ftd.destinationHashFile.exists() && hash.value == ftd.destinationHashFile.readText()))
        }

        if (alreadyDownloaded.isNotEmpty() && !alreadyDownloaded.values.contains(false)) {
            become(downloadingReceive(State(msg.info, dms)))
            alreadyDownloaded.forEach { (hash, _) ->
                channel.send(
                    FileDownloader.Companion.Message.AlreadyDownloaded(channel, hash))
            }
        } else {
            block()
        }
    }

    private suspend fun DeploymentPermitProvider.onAuthorizationReceive(
        onWaitForAuthorization: (suspend ()-> Unit)? = null,
        onGrantAuthorization: suspend ()-> Unit){
        //Since downloadAllowed function might be a long-running operation,
        // it is better to execute it first and then notify the user about the waiting process
        val downloadAllowed = downloadAllowed()
        onWaitForAuthorization?.invoke()
        if(downloadAllowed.await()){
            onGrantAuthorization.invoke()
        } else {
            LOG.info("Authorization denied for download files")
        }
    }

    private suspend fun startDownloadProcedure(msg: DeploymentInfo) {

        val hashes = hashesOfFilesToBeDownloaded(msg.info)
        clean(hashes)

        if (hashes.isNotEmpty()) {

            notificationManager.send(
                MessageListener.Message.State.Downloading(
                    msg.info.artifactsWithHashIn(hashes).map { (at, _) ->
                        MessageListener.Message.State.Downloading.Artifact(at.filename, at.size, at.hashes.toUpdaterHashes())
                    }.toList()
                )
            )

            val dms = createDownloadsManagers(msg.info, hashes)
            become(downloadingReceive(State(msg.info, dms)))
            feedback(msg.info.id, Execution.download, Progress(dms.size, 0), none,
                "Start downloading ${dms.size} files")
            dms.values.forEach {
                it.downloader.send(FileDownloader.Companion.Message.Start)
            }
        } else {
            onDownloadSucceeded(msg.info.id, Progress(0,0), success, "No file downloaded", null)
        }
    }

    private fun waitingDownloadAuthorization(state: State): Receive = { msg ->
        when (msg) {
            is DeploymentInfo -> {
                when {
                    msg.isDownloadSoft && !msg.forceAuthRequest -> {}

                    else -> {
                        become(beforeStartReceive())
                        channel.send(msg)
                    }
                }
            }

            is Message.DownloadGranted -> {
                val message = "Authorization granted for downloading files"
                LOG.info(message)
                feedback(state.deplBaseResp.id, proceeding, Progress(0, 0), none, message)
                failDownloadOnSelectionError(state.deplBaseResp.id) {
                    startDownloadProcedure(DeploymentInfo(state.deplBaseResp))
                }
            }

            is ConnectionManager.Companion.Message.Out.DeploymentCancelInfo -> {
                stopUpdateAndNotify(msg)
            }

            is ActionManager.Companion.Message.CancelForced -> {
                stopUpdate()
            }

            else -> handleMsgDefault(msg)
        }
    }

    private fun downloadingReceive(state: State): Receive = { msg ->
        when (msg) {

            is FileDownloader.Companion.Message.Success -> {
                processMessage(state, msg.hash, Status.SUCCESS, "Successfully downloaded file with ${msg.hash}")
            }

            is FileDownloader.Companion.Message.AlreadyDownloaded -> {
                processMessage(state, msg.hash, Status.SUCCESS, "${msg.hash} already downloaded")
            }

            is FileDownloader.Companion.Message.Info -> {
                feedback(state.deplBaseResp.id, Execution.download, state.getProgress(), none, msg.message)
                LOG.info(msg.toString())
            }

            is FileDownloader.Companion.Message.Error -> {
                processMessage(state, msg.hash,
                    Status.ERROR, "Failed to download file with ${msg.hash} due to ${msg.message}", msg.message)
            }

            else -> handleMsgDefault(msg)
        }
    }

    private suspend fun processMessage(state: State, hash: ArtifactHash, status: Status, message: String, errorMsg: List<String>? = null) {
        val download = state.downloads.getValue(hash)
        val newErrMessages = if (errorMsg == null) download.state.messages else download.state.messages + errorMsg
        val newDownload = download.copy(state = download.state.copy(status = status, messages = newErrMessages))
        val newState = state.copy(downloads = state.downloads + (hash to newDownload))
        val downloads = newState.downloads.values
        val progress = newState.getProgress()
        when {
            downloads.any { it.state.status == Status.RUNNING } -> {
                feedback(state.deplBaseResp.id, Execution.download, progress, none, message)
                become(downloadingReceive(newState))
            }
            downloads.any { it.state.status == Status.ERROR } -> {
                feedback(state.deplBaseResp.id, closed, progress, failure, message)
                notificationManager.send(
                    MessageListener.Message.Event.UpdateFinished(successApply = false,
                        details = listOf(message)))
                parent!!.send(DownloadFailed(listOf(message)))
            }
            else -> {
                feedback(state.deplBaseResp.id, Execution.download, progress, none, message)
                onDownloadSucceeded(state.deplBaseResp.id, progress, none, "Successfully downloaded all files", newState)
            }
        }
    }

    // An updater error, or an artifact without an accepted hash, while choosing the files to download
    // closes the action as a failed download
    private suspend fun failDownloadOnSelectionError(id: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (e !is UpdaterException && e !is ArtifactHashException) throw e
            val message = "Failed to select the files to download: ${e.message}"
            feedback(id, closed, Progress(0, 0), failure, message)
            notificationManager.send(
                MessageListener.Message.Event.UpdateFinished(successApply = false,
                    details = listOf(message)))
            parent!!.send(DownloadFailed(listOf(message)))
        }
    }

    private suspend fun onDownloadSucceeded(id: String, progress: Progress, finished: Finished, message: String, newState: State?){
        feedback(id, Execution.downloaded, progress, finished, message)
        newState?.downloads?.values?.forEach { it.downloader.close() }
        notificationManager.send(MessageListener.Message.Event.AllFilesDownloaded)
        parent!!.send(DownloadFinished)
        channel.close()
    }

    private fun State.getProgress():Progress {
        return Progress( downloads.values.size,  downloads.values.count { it.state.status == Status.SUCCESS })
    }

    private suspend fun feedback(id: String, execution: Execution, progress: Progress, finished: Finished, vararg messages: String) {
        val deplFdbkReq = DeploymentFeedbackRequest.newInstance(id, execution, progress, finished, *messages)
        connectionManager.send(DeploymentFeedback(deplFdbkReq))
    }

    private fun hashesOfFilesToBeDownloaded(dbr: DeploymentBaseResponse): Set<ArtifactHash> = when (dbr.deployment.download) {
        attempt, forced -> registry.allRequiredArtifactsFor(dbr.deployment.chunks).map { pathResolver.hashOf(it) }.toSet()
        else -> emptySet()
    }

    // Artifacts the updaters don't need may lack an accepted hash: they're skipped, not an error
    private fun DeploymentBaseResponse.artifactsWithHashIn(hashes: Set<ArtifactHash>) =
        deployment.chunks.flatMap { it.artifacts }.mapNotNull { at ->
            pathResolver.hashOrNull(at.hashes.toUpdaterHashes())?.takeIf { it in hashes }?.let { at to it }
        }

    private fun createDownloadsManagers(dbr: DeploymentBaseResponse, hashes: Set<ArtifactHash>): Map<ArtifactHash, Download> {
        val wd = pathResolver.updateDir(dbr.id)
        if (!wd.exists()) {
            wd.mkdirs()
        }
        return dbr.artifactsWithHashIn(hashes).map { (at, hash) ->
            val ftd = FileToDownload(at.filename, hash, DownloadLinks.urlsOf(at, serverUrl), wd, at.size)
            val dm = actorOf(childName(hash)) {
                FileDownloader.of(it, ftd, dbr.id)
            }
            Pair(hash, Download(dm))
        }.toMap()
    }

    private suspend fun stopUpdateAndNotify(msg: ConnectionManager.Companion.Message.Out.DeploymentCancelInfo) {
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
        parent!!.send(ActionManager.Companion.Message.UpdateStopped)
    }

    private val DeploymentInfo.isDownloadForced: Boolean
        get() = info.deployment.download == forced

    private val DeploymentInfo.isDownloadSoft: Boolean
        get() = info.deployment.download == attempt

    private fun childName(hash: ArtifactHash) = "fileDownloader_for_${hash.value}"

    private fun clean(hashesToKeep: Set<ArtifactHash>) = runBlocking {
        LOG.info("Removing artifacts not needed by the current update")
        withContext(Dispatchers.IO) {
            ArtifactsCleaner.clean(pathResolver.baseDirectory(), hashesToKeep.map { it.value }.toSet())
        }
    }

    init {
        become(beforeStartReceive())
    }

    companion object {
        fun of(scope: ActorScope) = DownloadManager(scope)

        data class State(
                val deplBaseResp: DeploymentBaseResponse,
                val downloads: Map<ArtifactHash, Download> = emptyMap()
        ) {
            data class Download(val downloader: ActorRef, val state: State = State()) {
                data class State(val status: Status = Status.RUNNING, val messages: List<String> = emptyList()) {
                    enum class Status { SUCCESS, ERROR, RUNNING }
                }
            }
        }

        sealed class Message {
            object DownloadGranted : Message()
        }
    }
}
