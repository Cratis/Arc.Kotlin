// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot;

/** State and stable repository revision loaded during command preparation. */
public record TaskCompletionPreparation(TaskView task, long revision) {
}
