// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot.features.livefeed

import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.springframework.stereotype.Component

/**
 * Holds every posted message and republishes the whole feed on each post.
 *
 * `byAuthor` keeps its own `MutableStateFlow` per author rather than mapping over [messages].
 * A mapped flow has no current value, and Arc answers a snapshot `GET` on a source without one
 * with `202 Not Ready` — correct, because it will not block a request waiting for an emission.
 * Holding the filtered state means the same query answers `GET` and a live subscription alike.
 */
@Component
public class LiveFeedSource {
    private val messages: MutableStateFlow<List<LiveFeed>> =
        MutableStateFlow(listOf(LiveFeed("system", "Feed started", OffsetDateTime.now())))
    private val byAuthor: ConcurrentHashMap<String, MutableStateFlow<List<LiveFeed>>> = ConcurrentHashMap()

    /** Observes every message in the feed. */
    public fun observe(): Flow<List<LiveFeed>> = messages

    /** Observes only the messages written by [author]. */
    public fun observeByAuthor(author: String): Flow<List<LiveFeed>> = synchronized(this) {
        byAuthor.computeIfAbsent(author) { MutableStateFlow(messages.value.filter { it.author == author }) }
    }

    /** Appends a message and pushes the new feed to every subscriber. */
    public fun post(author: String, text: String): LiveFeed {
        val message = LiveFeed(author, text, OffsetDateTime.now())
        synchronized(this) {
            val posted = messages.value + message
            messages.value = posted
            byAuthor.forEach { (key, flow) -> flow.value = posted.filter { it.author == key } }
        }
        return message
    }
}

/**
 * A live message feed.
 *
 * `all` and `byAuthor` are the same data behind two subscriptions, and `byAuthor` takes a client
 * argument — which is the point: an observable query is still a query, so it binds parameters the
 * same way a one-shot query does and re-evaluates them on every emission.
 *
 * @property author The author of the message.
 * @property text The message text.
 * @property postedAt The timestamp when the message was posted.
 */
@ReadModel
@AllowAnonymous
public data class LiveFeed(
    public val author: String,
    public val text: String,
    public val postedAt: OffsetDateTime
) {
    public companion object {
        /** Observes all messages in the live feed. */
        @JvmStatic
        public fun all(@FromServices source: LiveFeedSource): Flow<List<LiveFeed>> = source.observe()

        /** Observes messages posted by a specific author. */
        @JvmStatic
        public fun byAuthor(author: String, @FromServices source: LiveFeedSource): Flow<List<LiveFeed>> =
            source.observeByAuthor(author)
    }
}
