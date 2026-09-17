// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The stored shape of a task in MongoDB.
 *
 * {@code @Version} is doing real work here: it is what makes {@code CompleteTask}'s optimistic check
 * a database guarantee rather than a hope. Two callers completing the same task race, and exactly
 * one of them finds the version it prepared still current.
 *
 * @param id The task identifier.
 * @param title The task title.
 * @param completed Whether the task has been completed.
 * @param version The optimistic-concurrency version Spring Data maintains.
 */
@Document("tasks")
public record TaskDocument(@Id String id, String title, boolean completed, @Version Long version) {
}
