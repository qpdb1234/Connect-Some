/*
 * Copyright (c) 2024  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.util

import com.limelight.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

/**
 * Extension of [LiveEvent] to facilitate awaiting some response from observers.
 *
 * This simplifies the cases where a background thread needs some value from the user (i.e. UI thread),
 * and we want the background thread to block until that value is available.
 */
class LiveRequest<RequestType, ResponseType>() {

    private companion object {
        private const val TAG = "LiveRequest"
    }

    private val liveEvent = LiveEvent<RequestType>()
    private val responses = Channel<ResponseType>(Channel.UNLIMITED)

    val value get() = liveEvent.value

    /**
     * Fires this request with given [value] and returns the response.
     * Will block until any response is available, or [scope] is cancelled.
     * Cannot be called from main thread.
     */
    fun getResponseFor(value: RequestType, scope: CoroutineScope): ResponseType {
        checkNotMainThread()

        // Replies are not tagged with the request they belong to, so an answer that arrives
        // after its own prompt was cancelled (the scope died, the user typed anyway) would
        // still sit in [responses] and be handed out as the answer to whatever is asked
        // next - a password field returning a username prompt's value. Requests are
        // serialized by the single receiver thread, so dropping what is left here is safe.
        while (responses.tryReceive().isSuccess) {
            Log.w(TAG, "Dropped an unanswered response before firing a new request")
        }

        liveEvent.fireAsync(value)

        return runBlocking(scope.coroutineContext) {
            responses.receive()
        }
    }

    /**
     * Sets response for current request.
     */
    fun offerResponse(response: ResponseType) {
        runBlocking { responses.send(response) }
    }

    /**
     * Adds observer to be notified when [getResponseFor] is executed.
     */
    fun observe(owner: LifecycleOwner, observer: Observer<RequestType>) = liveEvent.observe(owner, observer)
}