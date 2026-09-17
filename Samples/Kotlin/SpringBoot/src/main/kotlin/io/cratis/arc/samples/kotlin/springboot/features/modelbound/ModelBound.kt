// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot.features.modelbound

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous

/**
 * The smallest command Arc can host.
 *
 * One annotation, one `handle`. No controller, no route attribute, no registration — the KSP
 * processor sees the annotation and generates the endpoint and the TypeScript client from it.
 *
 * @property stuffToDo The work the caller is asking for.
 */
@Command
@AllowAnonymous
public data class ModelBoundCommand(public val stuffToDo: String) {
    /** Handles the command and echoes the request back. */
    public fun handle(): String = "Doing : $stuffToDo!"
}

/**
 * The smallest read model Arc can host.
 *
 * @property data The numeric payload.
 */
@ReadModel
@AllowAnonymous
public data class ModelBoundReadModel(public val data: Int) {
    public companion object {
        /** Gets every item. */
        @JvmStatic
        public fun getAll(): List<ModelBoundReadModel> = (1..20).map(::ModelBoundReadModel)

        /** Gets one item by identifier. */
        @JvmStatic
        public fun getById(id: String): ModelBoundReadModel = ModelBoundReadModel(id.toIntOrNull() ?: 0)
    }
}
