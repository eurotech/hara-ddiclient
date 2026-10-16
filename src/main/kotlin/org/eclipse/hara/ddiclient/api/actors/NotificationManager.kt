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

import org.eclipse.hara.ddiclient.api.MessageListener
import kotlinx.coroutines.ObsoleteCoroutinesApi

@OptIn(ObsoleteCoroutinesApi::class)
class NotificationManager
private constructor(scope: ActorScope) : AbstractActor(scope) {

    private val listeners = coroutineContext[HaraClientContext]!!.messageListeners

    private fun defaultReceive(): Receive = { msg ->

        when (msg) {

            is MessageListener.Message -> listeners.forEach { notify(it, msg) }

            else -> handleMsgDefault(msg)
        }
    }

    // A failing listener must not keep the message from the others, nor affect the client
    private fun notify(listener: MessageListener, msg: MessageListener.Message) {
        try {
            listener.onMessage(msg)
        } catch (t: Throwable) {
            if (t.isFatal()) throw t
            LOG.error("Message listener ${listener.javaClass.name} failed on $msg", t)
        }
    }

    init {
        become(defaultReceive())
    }

    companion object {
        fun of(scope: ActorScope) = NotificationManager(scope)
    }
}
