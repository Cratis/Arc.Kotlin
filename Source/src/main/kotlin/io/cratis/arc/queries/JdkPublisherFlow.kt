// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import java.util.concurrent.Flow as JdkFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Adapts a JDK [JdkFlow.Publisher] to a cold Kotlin [Flow] without blocking a thread. */
public fun <T : Any> JdkFlow.Publisher<T>.asKotlinFlow(): Flow<T> = callbackFlow {
    var subscription: JdkFlow.Subscription? = null
    this@asKotlinFlow.subscribe(object : JdkFlow.Subscriber<T> {
        override fun onSubscribe(value: JdkFlow.Subscription) {
            if (subscription != null) {
                value.cancel()
                return
            }
            subscription = value
            value.request(1)
        }

        override fun onNext(item: T) {
            val result = trySend(item)
            if (result.isSuccess) {
                subscription?.request(1)
            } else {
                subscription?.cancel()
            }
        }

        override fun onError(throwable: Throwable) {
            close(throwable)
        }

        override fun onComplete() {
            close()
        }
    })
    awaitClose { subscription?.cancel() }
}
