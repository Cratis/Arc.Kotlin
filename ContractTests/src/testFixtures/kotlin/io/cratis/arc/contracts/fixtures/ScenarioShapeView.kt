// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel

/** Command input containing generated interface and abstract-base registrations. */
@Command
public data class ScenarioShapeCommand(
    public val shape: FixtureShape,
    public val base: FixtureShapeBase,
    public val javaContract: JavaFixtureContract
) {
    public fun handle(): ScenarioShapeView = ScenarioShapeView(shape, base, javaContract)
}

/** Nested polymorphic query data in single and list results. */
@ReadModel
public data class ScenarioShapeView(
    public val shape: FixtureShape,
    public val base: FixtureShapeBase,
    public val javaContract: JavaFixtureContract
) {
    public companion object {
        public fun nested(@FromServices source: ScenarioShapeSource): ScenarioShapeView = source.view
        public fun list(@FromServices source: ScenarioShapeSource): List<ScenarioShapeView> = listOf(source.view)
    }
}

/** Supplies identifiable original instances so scenarios must prove a real JSON copy. */
public class ScenarioShapeSource(public val view: ScenarioShapeView)
