// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot.features.livefeed

import io.cratis.arc.artifacts.Command
import io.cratis.arc.authorization.AllowAnonymous
import java.time.OffsetDateTime

/**
 * A single message in a live feed, returned to the caller that posted it.
 *
 * @property author The author of the message.
 * @property text The content of the message.
 * @property postedAt The timestamp when the message was posted.
 */
public data class LiveFeedMessage(
    public val author: String,
    public val text: String,
    public val postedAt: OffsetDateTime
)

/**
 * Posts a message to the live feed.
 *
 * The command returns the posted message to its own caller and, as a side effect, pushes the new
 * feed to every other subscriber of [LiveFeed.all]. That split is worth noticing: the command
 * result is a request/response value, while the feed update travels the observable transport.
 *
 * @property author The author of the message.
 * @property text The message text.
 */
@Command
@AllowAnonymous
public data class PostToFeed(public val author: String, public val text: String) {
    /** Handles the command by appending the message to the shared live feed. */
    public fun handle(source: LiveFeedSource): LiveFeedMessage {
        val posted = source.post(author, text)
        return LiveFeedMessage(posted.author, posted.text, posted.postedAt)
    }
}
