// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.artifacts

/** Command-instance provider for a dynamic event subject. */
public fun interface CommandEventSubjectProvider {
    /** Returns the event subject, or `null` to leave the event store fallback unchanged. */
    public fun eventSubject(): String?
}
