// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/** Nullable type-use annotation for negative Java map fixtures. */
@Target({ElementType.TYPE_USE, ElementType.RECORD_COMPONENT})
@interface Nullable {
}
