// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for the task board. */
public interface TaskEntities extends JpaRepository<TaskEntity, String> {
}
