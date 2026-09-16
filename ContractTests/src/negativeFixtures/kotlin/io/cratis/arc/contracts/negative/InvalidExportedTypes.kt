// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative

import io.cratis.arc.artifacts.ExportedType

/** An abstract class has no single concrete shape to generate; the concrete descendants are exported instead. */
@ExportedType
public abstract class AbstractExportedType {
    public abstract val name: String
}

/** A generic definition has no single shape to generate. */
@ExportedType
public class GenericExportedType<T : Any>(public val value: T)

/** A non-public type cannot be used by a generated client. */
@ExportedType
internal class InternalExportedType(val value: String)

/** Only top-level types are supported, matching every other Arc artifact root. */
public class ExportedTypeOwner {
    @ExportedType
    public class NestedExportedType(public val value: String)
}
