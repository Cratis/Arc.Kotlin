// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javachronicle;

import io.cratis.chronicle.events.EventType;

@EventType
public record AuthorRegistered(String name) { }
