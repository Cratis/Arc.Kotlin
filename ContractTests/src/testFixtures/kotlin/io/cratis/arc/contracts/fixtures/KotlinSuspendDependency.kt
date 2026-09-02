// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import kotlinx.coroutines.yield

/** Dependency used by the suspending Kotlin command fixture. */
public class KotlinSuspendDependency {
    public var invocationCount: Int = 0
        private set

    public suspend fun respond(commandId: String): String {
        yield()
        invocationCount++
        return "suspend:$commandId"
    }
}
