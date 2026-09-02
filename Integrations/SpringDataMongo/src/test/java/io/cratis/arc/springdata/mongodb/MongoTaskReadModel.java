// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb;

import io.cratis.arc.artifacts.ReadModel;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("tasks")
@ReadModel
public class MongoTaskReadModel {
    @Id
    private String id;
    private String title;

    public MongoTaskReadModel() {
    }

    public MongoTaskReadModel(String id, String title) {
        this.id = id;
        this.title = title;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }
}
