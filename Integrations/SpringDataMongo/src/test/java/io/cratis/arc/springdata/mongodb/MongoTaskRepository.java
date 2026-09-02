// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface MongoTaskRepository extends MongoRepository<MongoTaskReadModel, String> {
}
