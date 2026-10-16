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

import kotlinx.coroutines.*
import org.eclipse.hara.ddi.api.DdiClient
import org.eclipse.hara.ddi.api.model.DeploymentFeedbackRequest
import org.eclipse.hara.ddiclient.api.MessageListener
import org.eclipse.hara.ddiclient.api.inputstream.FilterInputStreamWithProgress
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.NumberFormat
import java.util.Timer
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.fixedRateTimer
import org.eclipse.hara.ddiclient.api.DownloadBehavior
import org.eclipse.hara.ddiclient.api.ArtifactHash
import org.eclipse.hara.ddiclient.api.HashAlgorithm
import java.lang.StringBuilder
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ObsoleteCoroutinesApi::class)
class FileDownloader
private constructor(
        scope: ActorScope,
        private val fileToDownload: FileToDownload,
        actionId: String
) : AbstractActor(scope) {

    private val client: DdiClient = coroutineContext[HaraClientContext]!!.ddiClient
    private val downloadBehavior: DownloadBehavior = coroutineContext[HaraClientContext]!!.downloadBehavior
    private val notificationManager = coroutineContext[NMActor]!!.ref
    private val connectionManager = coroutineContext[CMActor]!!.ref
    private var downloadJob: Job? = null
    private var downloadScope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    private fun beforeStart(state: State): Receive = { msg ->
        when (msg) {

            is Message.Start -> {
                become(downloading(state))
                if (fileToDownload.destination.exists()) {
                    channel.send(Message.FileDownloaded)
                } else if (fileToDownload.urls.isEmpty()) {
                    // Retrying can't help: the links come from the deployment
                    val errors = listOf("No usable download link for ${fileToDownload.fileName}")
                    parent!!.send(Message.Error(channel, fileToDownload.hash, errors))
                    notificationManager.send(MessageListener.Message.Event.Error(errors))
                } else {
                    notificationManager.send(MessageListener.Message.Event.StartDownloadFile(fileToDownload.fileName))
                    tryDownload(state)
                }
            }

            else -> handleMsgDefault(msg)
        }
    }

    private fun downloading(state: State): Receive = { msg ->

        when (msg) {

            is Message.FileDownloaded -> checkHashOfDownloadedFile()

            is Message.FileChecked -> {
                parent!!.send(Message.Success(channel, fileToDownload.hash))
                notificationManager.send(MessageListener.Message.Event.FileDownloaded(fileToDownload.fileName))
            }

            is Message.TrialExhausted -> {
                val errors = mutableListOf<String>()
                errors.add(0, "Exhausted trials of downloading (${fileToDownload.fileName}) after ${state.currentAttempt} attempts!")
                parent!!.send(Message.Error(channel, fileToDownload.hash, errors))
                notificationManager.send(MessageListener.Message.Event.Error(errors))
            }

            is Message.RetryDownload -> {
                val newState = state.copy(currentAttempt = state.nextAttempt(), errors = state.errors + msg.message)
                become(downloading(newState))
                tryDownload(newState, msg.cause)
            }

            else -> handleMsgDefault(msg)
        }
    }

    private suspend fun tryDownload(state: State, error:Throwable? = null) {

        when(val tryDownload = downloadBehavior.onAttempt(state.currentAttempt, "${state.actionId}-${fileToDownload.hash.value}", error)){

            is DownloadBehavior.Try.Stop ->  channel.send(Message.TrialExhausted)

            is DownloadBehavior.Try.After -> {
                downloadJob = downloadScope.launch {
                    if(error != null){
                        val errorMessage = "Retry download of ${fileToDownload.fileName} due to: $error. The download will start in ${tryDownload.seconds.toDuration(DurationUnit.SECONDS)}."
                        parent!!.send(Message.Info(channel, fileToDownload.hash, errorMessage))
                        notificationManager.send(MessageListener.Message.Event.Error(listOf(errorMessage)))
                    }
                    delay(tryDownload.seconds * 1000)
                    runCatching {
                        download(state.actionId)
                        channel.send(Message.FileDownloaded)
                    }.onFailure {t ->
                        channel.send(Message.RetryDownload("exception: ${t.javaClass.simpleName}. message: ${t.message}", t))
                        LOG.warn("Failed to download file ${fileToDownload.fileName}", t)
                    }
                }
            }
        }
    }

    private fun Double.toPercentage(minFractionDigits: Int = 0): String {
        val format = NumberFormat.getPercentInstance()
        format.minimumFractionDigits = minFractionDigits
        return format.format(this)
    }

    // Tries the links in order; the attempt fails only if every link fails
    private suspend fun download(actionId: String) {
        val failures = mutableListOf<Throwable>()
        for (url in fileToDownload.urls) {
            try {
                download(url, actionId)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Failed to download file ${fileToDownload.fileName} from $url: $e")
                failures += e
            }
        }
        throw when (failures.size) {
            1 -> failures.single()
            else -> IOException(
                failures.zip(fileToDownload.urls).joinToString("; ", "All download links failed: ") { (e, url) ->
                    "$url: ${e.javaClass.simpleName}: ${e.message}"
                },
                failures.last()
            ).apply { failures.dropLast(1).forEach(::addSuppressed) }
        }
    }

    private suspend fun download(url: String, actionId: String) {
        val file = fileToDownload.tempFile
        // A partial file left by an earlier attempt or run is resumed; one longer than the artifact can't be
        val offset = if (file.length() <= fileToDownload.size) file.length() else 0L
        if (offset > 0 && offset == fileToDownload.size) {
            LOG.info("${fileToDownload.fileName} already fully downloaded, checking it")
            return
        }
        if (offset == 0L && file.exists()) {
            file.delete()
        }

        val artifact = client.downloadArtifact(url, offset)
        require(artifact.offset == 0L || artifact.offset == offset) {
            "The download of ${fileToDownload.fileName} starts at byte ${artifact.offset}, expected $offset or 0"
        }
        if (offset > 0) {
            LOG.info(if (artifact.offset > 0) "Resuming the download of ${fileToDownload.fileName} from byte $offset"
                else "The server sent ${fileToDownload.fileName} from the start: downloading it again")
        }

        val inputStream = FilterInputStreamWithProgress(artifact.stream, fileToDownload.size, artifact.offset)

        val queue = ArrayBlockingQueue(10, true, (1..9).map { it.toDouble() / 10 })

        val timer = checkDownloadProgress(inputStream, queue, actionId)

        runCatching {
            inputStream.use { inputStream ->
                FileOutputStream(file, artifact.offset > 0).use { outputStream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var progressBytes = 0L
                    var bytes = inputStream.read(buffer)
                    while (bytes >= 0) {
                        if (!downloadScope.isActive) {
                            LOG.info("Download of ${fileToDownload.fileName} was cancelled")
                            return
                        }
                        outputStream.write(buffer, 0, bytes)
                        progressBytes += bytes
                        bytes = inputStream.read(buffer)
                    }
                }
                file.setReadable(true, false)
            }
        }.also {
            timer.purge()
            timer.cancel()
        }.onFailure {
            throw it
        }

    }

    private fun checkDownloadProgress(
            inputStream: FilterInputStreamWithProgress,
            queue: ArrayBlockingQueue<Double>,
            actionId: String
    ): Timer {
        return fixedRateTimer("Download Checker ${fileToDownload.fileName}", true, 60_000, 60_000) {
            launch {
                val progress = inputStream.getProgress()
                val limit = queue.peek() ?: 1.0
                if (progress > limit) {
                    feedback(actionId,
                            DeploymentFeedbackRequest.Status.Execution.download,
                            DeploymentFeedbackRequest.Status.Result.Progress(0, 0),
                            DeploymentFeedbackRequest.Status.Result.Finished.none,
                            "Downloading file named ${fileToDownload.fileName} " +
                                    "- ${progress.toPercentage(2)}")
                    while (progress > queue.peek() ?: 1.0 && queue.isNotEmpty()) {
                        queue.poll()
                    }
                }
                notificationManager.send(MessageListener.Message.Event.DownloadProgress(fileToDownload.fileName, progress))
            }
        }
    }

    private suspend fun feedback(id: String, execution: DeploymentFeedbackRequest.Status.Execution, progress: DeploymentFeedbackRequest.Status.Result.Progress, finished: DeploymentFeedbackRequest.Status.Result.Finished, vararg messages: String) {
        val deplFdbkReq = DeploymentFeedbackRequest.newInstance(id, execution, progress, finished, *messages)
        connectionManager.send(ConnectionManager.Companion.Message.In.DeploymentFeedback(deplFdbkReq))
    }

    private suspend fun checkHashOfDownloadedFile() {
        launch {
            var fileAlreadyDownloaded = false
            val file = if(fileToDownload.destination.exists()) {
                LOG.info("${fileToDownload.fileName} already downloaded. Checking ${fileToDownload.hash.algorithm}...")
                fileAlreadyDownloaded = true
                fileToDownload.destination
            } else {
                fileToDownload.tempFile
            }

            val hash = file.digest(fileToDownload.hash.algorithm)
            val hashCheckResult = hash == fileToDownload.hash.value
            when {

                fileAlreadyDownloaded && hashCheckResult -> {
                    parent!!.send(Message.AlreadyDownloaded(channel, fileToDownload.hash))
                }

                !fileAlreadyDownloaded && hashCheckResult -> {
                    fileToDownload.onFileSaved()
                    fileToDownload.destinationHashFile.writeText(hash)
                    channel.send(Message.FileChecked)
                }

                file.delete() -> channel.send(Message.RetryDownload("Downloaded file (${fileToDownload.fileName}) has wrong ${fileToDownload.hash.algorithm} hash ($hash)"))

                else -> channel.send(Message.RetryDownload("Can't remove file named ${file.name}"))
            }
        }
    }

    override suspend fun stopActor() {
        runCatching {
            downloadJob?.let {
                LOG.debug("Cancelling download job $it")
                if (it.isActive) {
                    it.cancel()
                    downloadScope.cancel()
                }
            }
        }
        super.stopActor()
    }

    private fun State.nextAttempt():Int = if (currentAttempt == Int.MAX_VALUE) currentAttempt else currentAttempt + 1

    init {
        become(beforeStart(State(1, actionId)))
    }

    companion object {
        const val DOWNLOADING_EXTENSION = "downloading"
        fun of(
                scope: ActorScope,
                fileToDownload: FileToDownload,
                actionId: String
        ) = FileDownloader(scope, fileToDownload, actionId)

        data class FileToDownload(
                val fileName: String,
                val hash: ArtifactHash,
                val urls: List<String>,
                val folder: File,
                val size: Long
        ) {
            val tempFile = File(folder, "${hash.value}.$DOWNLOADING_EXTENSION")
            val destination = File(folder, hash.value)
            // Marks the file as checked; named after the algorithm, so it's never read with another one
            val destinationHashFile = File(folder, "${hash.value}.${hashFileExtension(hash.algorithm)}")
            fun onFileSaved() = tempFile.renameTo(destination)
        }

        private data class State(
            val currentAttempt: Int,
            val actionId: String,
            val errors: List<String> = emptyList()
        )

        sealed class Message {

            object Start : Message()

            object FileDownloaded : Message()
            object FileChecked : Message()
            data class RetryDownload(val message: String, val cause: Throwable? = null) : Message()
            object TrialExhausted : Message()

            data class Success(val sender: ActorRef, val hash: ArtifactHash) : Message()
            data class AlreadyDownloaded(val sender: ActorRef, val hash: ArtifactHash) : Message()
            data class Info(val sender: ActorRef, val hash: ArtifactHash, val message: String) : Message()
            data class Error(val sender: ActorRef, val hash: ArtifactHash, val message: List<String> = emptyList()) : Message()
        }

        fun hashFileExtension(algorithm: HashAlgorithm): String = algorithm.name.lowercase()

        fun File.digest(algorithm: HashAlgorithm): String {
            val md = MessageDigest.getInstance(algorithm.digestName)
            val sb = StringBuilder()

            inputStream().use { fis ->
                val digestInputStream = DigestInputStream(fis, md)
                val buffer = ByteArray(4096)
                while (digestInputStream.read(buffer) > -1) {}
                digestInputStream.close()
                digestInputStream.messageDigest
                    .digest()
                    .forEach {
                        sb.append(String.format("%02X", it))
                    }
            }
            return sb.toString().lowercase()
        }
    }
}
