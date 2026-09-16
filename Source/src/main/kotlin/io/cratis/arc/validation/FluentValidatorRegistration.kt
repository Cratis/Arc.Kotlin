// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

/**
 * Generated module contribution binding a runtime declaration to compiler-extracted rules.
 * Runtime construction is allowed; tooling must produce [expectedRules] without executing application code.
 * Missing, extra, mistyped or changed rules fail registration rather than diverging from the client.
 */
public class FluentValidatorRegistration(
    public val validator: FluentModelValidator<*>,
    public val modelType: Class<*>,
    expectedRules: List<FluentValidationMember>
) {
    public val expectedRules: List<FluentValidationMember> = java.util.List.copyOf(expectedRules)

    init {
        require(modelType == validator.modelType && this.expectedRules == validator.rules) {
            "Fluent validator '${validator.javaClass.name}' does not match its compiler metadata; regenerate the artifact module."
        }
    }
}
