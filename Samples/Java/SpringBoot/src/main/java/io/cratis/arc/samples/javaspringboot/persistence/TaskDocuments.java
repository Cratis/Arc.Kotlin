// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data MongoDB repository for the task board. */
public interface TaskDocuments extends MongoRepository<TaskDocument, String> {
}
