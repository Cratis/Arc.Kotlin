// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.concepts.ConceptAs;
import java.time.LocalTime;

/** Java record time concept. */
public record JavaDeliveryTime(LocalTime value) implements ConceptAs<LocalTime> {
}
