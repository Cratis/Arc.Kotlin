// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins what Evo Inflector 1.3 actually produces for the nouns most likely to appear as read-model
 * class names.  A library upgrade that silently changes a plural would move a MongoDB collection
 * name and cause queries to return empty results without a compile-time error; these tests fail
 * instead.
 *
 * Every expectation here was measured by running both libraries over the same word list, not
 * inferred from their rule documentation. Evo Inflector 1.3 and the kernel's Humanizer agreed on
 * Child, Status, Series, Category, Entry, Task, Mouse and Datum, and disagreed on two: Evo produced
 * `Persons` and `Indexes` where Humanizer produced `People` and `Indices`. The policy corrects those
 * two toward the kernel, because the kernel writes the projection and therefore owns the collection
 * name a query has to read.
 */
class DefaultNamingPolicyTests {

    // ── Test type stubs whose simple names are the nouns under test ──────────
    private class Person
    private class Child
    private class Index
    private class Status
    private class Series
    private class Category
    private class Entry
    private class Task

    // ── pluralizeReadModelNames = true (default) ─────────────────────────────

    @Test
    fun `getReadModelName pluralises Person to People`() {
        // Measured: Evo Inflector 1.3 yields "Persons"; the kernel's Humanizer yields "People".
        // The policy corrects toward the kernel, which owns the collection the projection writes.
        assertEquals("People", DefaultNamingPolicy().getReadModelName(Person::class.java))
    }

    @Test
    fun `getReadModelName pluralises Child to Children`() {
        // Measured: Evo Inflector and Humanizer agree here, so no correction is needed.
        assertEquals("Children", DefaultNamingPolicy().getReadModelName(Child::class.java))
    }

    @Test
    fun `getReadModelName pluralises Index to Indices to match the kernel`() {
        // Measured: Evo Inflector 1.3 alone yields "Indexes" while the kernel's Humanizer yields
        // "Indices". The policy corrects toward the kernel, which owns the collection name.
        assertEquals("Indices", DefaultNamingPolicy().getReadModelName(Index::class.java))
    }

    @Test
    fun `getReadModelName pluralises Status to Statuses`() {
        // Measured: Evo Inflector and Humanizer agree here, so no correction is needed.
        assertEquals("Statuses", DefaultNamingPolicy().getReadModelName(Status::class.java))
    }

    @Test
    fun `getReadModelName leaves Series unchanged as it is an invariant word`() {
        // Confirmed: 'series' is listed explicitly in Evo Inflector 1.3's uncountable-word set.
        assertEquals("Series", DefaultNamingPolicy().getReadModelName(Series::class.java))
    }

    @Test
    fun `getReadModelName pluralises Category to Categories`() {
        // Confirmed: standard -y -> -ies rule; 'category' is not in the irregular or uncountable list.
        assertEquals("Categories", DefaultNamingPolicy().getReadModelName(Category::class.java))
    }

    @Test
    fun `getReadModelName pluralises Entry to Entries`() {
        // Confirmed: standard -ry -> -ries rule; 'entry' is not in the irregular or uncountable list.
        assertEquals("Entries", DefaultNamingPolicy().getReadModelName(Entry::class.java))
    }

    @Test
    fun `getReadModelName pluralises a regular noun by appending s`() {
        // Confirmed: 'task' follows the default append-s rule.
        assertEquals("Tasks", DefaultNamingPolicy().getReadModelName(Task::class.java))
    }

    // ── pluralizeReadModelNames = false ──────────────────────────────────────

    @Test
    fun `getReadModelName returns the simple class name unchanged when pluralizeReadModelNames is false`() {
        val policy = DefaultNamingPolicy(pluralizeReadModelNames = false)
        assertEquals("Person", policy.getReadModelName(Person::class.java))
        assertEquals("Category", policy.getReadModelName(Category::class.java))
        assertEquals("Task", policy.getReadModelName(Task::class.java))
    }

    // ── getPropertyName ──────────────────────────────────────────────────────

    @Test
    fun `getPropertyName returns the declared member name unchanged`() {
        val policy = DefaultNamingPolicy()
        assertEquals("firstName", policy.getPropertyName("firstName"))
        assertEquals("_id", policy.getPropertyName("_id"))
        assertEquals("some_field", policy.getPropertyName("some_field"))
    }

    @Test
    fun `getPropertyName is the same with pluralizeReadModelNames false`() {
        val policy = DefaultNamingPolicy(pluralizeReadModelNames = false)
        assertEquals("title", policy.getPropertyName("title"))
    }
}
