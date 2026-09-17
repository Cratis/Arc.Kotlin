// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.livefeed;

import java.time.OffsetDateTime;

/**
 * A single message in a live feed, returned to the caller that posted it.
 *
 * @param author The author of the message.
 * @param text The content of the message.
 * @param postedAt The timestamp when the message was posted.
 */
public record LiveFeedMessage(String author, String text, OffsetDateTime postedAt) {
}
