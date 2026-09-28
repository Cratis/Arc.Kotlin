// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javachronicle;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.artifacts.CommandKey;
import io.cratis.arc.authorization.AllowAnonymous;

/** Appends the fact to the stream named by the author id. */
@Command
@AllowAnonymous
public record RegisterAuthor(@CommandKey AuthorId id, String name) {
    public AuthorRegistered handle() { return new AuthorRegistered(name); }
}
