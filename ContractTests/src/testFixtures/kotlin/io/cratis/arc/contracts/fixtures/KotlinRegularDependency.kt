// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

/** Dependency used by the regular Kotlin command fixture. */
public class KotlinRegularDependency {
    public var invocationCount: Int = 0
        private set

    public fun respond(message: String): String {
        invocationCount++
        return "regular:$message"
    }
}
