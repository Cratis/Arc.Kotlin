// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.livefeed;

import io.cratis.arc.artifacts.FromServices;
import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.authorization.AllowAnonymous;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * A live message feed.
 *
 * `all` and `byAuthor` are the same data behind two subscriptions, and `byAuthor` takes a client
 * argument — which is the point: an observable query is still a query, so it binds parameters the
 * same way a one-shot query does and re-evaluates them on every emission.
 *
 * @param author The author of the message.
 * @param text The message text.
 * @param postedAt The timestamp when the message was posted.
 */
@ReadModel
@AllowAnonymous
public record LiveFeed(String author, String text, OffsetDateTime postedAt) {
    /**
     * Observes all messages in the live feed.
     *
     * @param source The feed state.
     * @return A publisher of the whole feed.
     */
    public static Flow.Publisher<List<LiveFeed>> all(@FromServices LiveFeedSource source) {
        return source.observe();
    }

    /**
     * Observes messages posted by a specific author.
     *
     * @param author The author to filter by.
     * @param source The feed state.
     * @return A publisher of that author's messages.
     */
    public static Flow.Publisher<List<LiveFeed>> byAuthor(String author, @FromServices LiveFeedSource source) {
        return source.observeByAuthor(author);
    }
}
