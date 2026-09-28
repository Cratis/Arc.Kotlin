// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.chronicle

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.chronicle.events.EventType

/** Appends the fact to the stream named by the author id. */
@Command
@AllowAnonymous
public data class RegisterAuthor(@CommandKey val id: AuthorId, val name: String) {
    public fun handle(): AuthorRegistered = AuthorRegistered(name)
}

@EventType
public data class AuthorRegistered(val name: String = "")
