// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

/** Creates immutable command-provided values in declaration order. */
@JvmSynthetic
public fun commandProvidedValuesOf(vararg values: Any): CommandProvidedValues = CommandProvidedValues(values.asList())

/** Creates immutable command-response values in handler-declared order. */
@JvmSynthetic
public fun commandResponseValuesOf(vararg values: Any): CommandResponseValues = CommandResponseValues(values.asList())
