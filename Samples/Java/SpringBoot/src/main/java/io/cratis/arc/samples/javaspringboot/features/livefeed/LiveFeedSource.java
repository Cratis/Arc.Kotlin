// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.livefeed;

import io.cratis.arc.queries.ObservableState;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

/**
 * Holds every posted message and republishes the whole feed on each post.
 *
 * `byAuthor` keeps its own state per author rather than filtering on the way out. A publisher that
 * emits nothing until the next post has no current value, and Arc answers a snapshot `GET` on such
 * a source with `202 Not Ready` rather than blocking the request. Holding the filtered state means
 * the same query answers `GET` and a live subscription alike.
 */
@Component
public final class LiveFeedSource {
    private final Object monitor = new Object();
    private final ObservableState<List<LiveFeed>> messages =
        new ObservableState<>(List.of(new LiveFeed("system", "Feed started", OffsetDateTime.now())));
    private final Map<String, ObservableState<List<LiveFeed>>> byAuthor = new ConcurrentHashMap<>();

    /**
     * Observes every message in the feed.
     *
     * @return A publisher of the whole feed.
     */
    public Flow.Publisher<List<LiveFeed>> observe() {
        return messages;
    }

    /**
     * Observes only the messages written by one author.
     *
     * @param author The author to filter by.
     * @return A publisher of that author's messages.
     */
    public Flow.Publisher<List<LiveFeed>> observeByAuthor(String author) {
        synchronized (monitor) {
            return byAuthor.computeIfAbsent(author, key -> new ObservableState<>(filter(messages.get(), key)));
        }
    }

    /**
     * Appends a message and pushes the new feed to every subscriber.
     *
     * @param author The author of the message.
     * @param text The message text.
     * @return The posted message.
     */
    public LiveFeed post(String author, String text) {
        var message = new LiveFeed(author, text, OffsetDateTime.now());
        synchronized (monitor) {
            var posted = new ArrayList<>(messages.get());
            posted.add(message);
            var published = List.copyOf(posted);
            messages.set(published);
            byAuthor.forEach((key, state) -> state.set(filter(published, key)));
        }
        return message;
    }

    private static List<LiveFeed> filter(List<LiveFeed> messages, String author) {
        return messages.stream().filter(message -> message.author().equals(author)).toList();
    }
}
