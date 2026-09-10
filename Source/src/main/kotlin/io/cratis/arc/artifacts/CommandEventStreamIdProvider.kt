// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.artifacts

/** Command-instance provider for a dynamic event-stream identifier. */
public fun interface CommandEventStreamIdProvider {
    /** Returns the event-stream identifier, or `null` to leave the event store fallback unchanged. */
    public fun eventStreamId(): String?
}
