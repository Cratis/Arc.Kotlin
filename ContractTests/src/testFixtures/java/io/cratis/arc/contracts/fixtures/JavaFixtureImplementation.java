// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.polymorphism.DerivedType;

/** Java record derivative preserved without converting it to a Kotlin model. */
@DerivedType(id = "java-contract")
public record JavaFixtureImplementation(String label) implements JavaFixtureContract {
}
