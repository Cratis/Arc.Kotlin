// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.modelbound;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.authorization.AllowAnonymous;

/**
 * The smallest command Arc can host.
 *
 * One annotation, one {@code handle}. No controller, no route attribute, no registration — the KSP
 * processor sees the annotation and generates the endpoint and the TypeScript client from it.
 *
 * @param stuffToDo The work the caller is asking for.
 */
@Command
@AllowAnonymous
public record ModelBoundCommand(String stuffToDo) {
    /**
     * Handles the command and echoes the request back.
     *
     * @return The echoed request.
     */
    public String handle() {
        return "Doing : " + stuffToDo + "!";
    }
}
