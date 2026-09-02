// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javachronicle;

import io.cratis.chronicle.events.EventType;

/** Records a rename and the tenant-local title from which it was derived. */
@EventType
public record TaskRenamed(String previousTitle, String title) {}
