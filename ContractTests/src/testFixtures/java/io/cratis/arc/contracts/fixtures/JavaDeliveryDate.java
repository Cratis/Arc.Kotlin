// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.concepts.ConceptAs;
import java.time.LocalDate;

/** Java record date concept. */
public record JavaDeliveryDate(LocalDate value) implements ConceptAs<LocalDate> {
}
