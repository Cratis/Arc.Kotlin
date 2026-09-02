// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa;

import io.cratis.arc.artifacts.ReadModel;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
@ReadModel
public class JpaTaskReadModel {
    @Id
    private String id;
    private String title;

    protected JpaTaskReadModel() {
    }

    public JpaTaskReadModel(String id, String title) {
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
