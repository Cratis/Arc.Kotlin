// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot;

/** Typed response returned after a task is created. */
public record TaskCreated(String id, String title) {
}
