// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The stored shape of a task in a relational database.
 *
 * {@code @Version} is doing real work here: it is what makes {@code CompleteTask}'s optimistic check
 * a database guarantee rather than a hope.
 */
@Entity
@Table(name = "tasks")
public class TaskEntity {
    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id = "";

    @Column(name = "title", nullable = false, length = 512)
    private String title = "";

    @Column(name = "completed", nullable = false)
    private boolean completed;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Required by JPA. */
    protected TaskEntity() {
    }

    /**
     * Initializes a new instance of the {@link TaskEntity} class.
     *
     * @param id The task identifier.
     * @param title The task title.
     * @param completed Whether the task has been completed.
     */
    public TaskEntity(String id, String title, boolean completed) {
        this.id = id;
        this.title = title;
        this.completed = completed;
    }

    /**
     * Gets the task identifier.
     *
     * @return The identifier.
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the task title.
     *
     * @return The title.
     */
    public String getTitle() {
        return title;
    }

    /**
     * Gets whether the task has been completed.
     *
     * @return True when completed.
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * Marks the task completed.
     *
     * @param value The completion state.
     */
    public void setCompleted(boolean value) {
        completed = value;
    }

    /**
     * Gets the optimistic-concurrency version.
     *
     * @return The version.
     */
    public long getVersion() {
        return version;
    }
}
