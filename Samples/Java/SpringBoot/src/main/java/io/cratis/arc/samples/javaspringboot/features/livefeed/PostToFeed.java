// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.livefeed;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.authorization.AllowAnonymous;

/**
 * Posts a message to the live feed.
 *
 * The command returns the posted message to its own caller and, as a side effect, pushes the new
 * feed to every other subscriber of {@code LiveFeed.all}. That split is worth noticing: the command
 * result is a request/response value, while the feed update travels the observable transport.
 *
 * @param author The author of the message.
 * @param text The message text.
 */
@Command
@AllowAnonymous
public record PostToFeed(String author, String text) {
    /**
     * Handles the command by appending the message to the shared live feed.
     *
     * @param source The feed state.
     * @return The posted message.
     */
    public LiveFeedMessage handle(LiveFeedSource source) {
        var posted = source.post(author, text);
        return new LiveFeedMessage(posted.author(), posted.text(), posted.postedAt());
    }
}
