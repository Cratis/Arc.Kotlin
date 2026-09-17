// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb;

import io.cratis.arc.naming.NamingPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Java-facing conformance checks for {@link NamingPolicy} and {@link DefaultNamingPolicy}.
 *
 * <p>Verifies that both types are usable from idiomatic Java: construction with and without
 * the boolean parameter, method calls with {@code Class} and {@code String} arguments, and
 * that {@link NamingPolicy} is reachable as an interface type from Java.
 */
final class DefaultNamingPolicyJavaConformanceTests {

    // ── Construction ─────────────────────────────────────────────────────────

    @Test
    void defaultConstructorProducesAPluralisingPolicy() {
        DefaultNamingPolicy policy = new DefaultNamingPolicy();
        assertNotNull(policy);
        // Regression-pin the plural; see DefaultNamingPolicyTests.kt for the verification note.
        assertEquals("Tasks", policy.getReadModelName(Task.class));
    }

    @Test
    void booleanConstructorWithFalseReturnsSimpleNameUnchanged() {
        DefaultNamingPolicy policy = new DefaultNamingPolicy(false);
        assertEquals("Task", policy.getReadModelName(Task.class));
    }

    @Test
    void booleanConstructorWithTruePluralises() {
        DefaultNamingPolicy policy = new DefaultNamingPolicy(true);
        assertEquals("Tasks", policy.getReadModelName(Task.class));
    }

    // ── NamingPolicy interface usage ─────────────────────────────────────────

    @Test
    void namingPolicyInterfaceIsAssignableFromDefaultNamingPolicy() {
        NamingPolicy policy = new DefaultNamingPolicy();
        assertNotNull(policy.getReadModelName(Task.class));
    }

    @Test
    void getPropertyNameReturnsTheNameUnchangedFromJava() {
        NamingPolicy policy = new DefaultNamingPolicy();
        assertEquals("firstName", policy.getPropertyName("firstName"));
        assertEquals("_id", policy.getPropertyName("_id"));
    }

    // ── Helper type whose simple name is the noun under test ─────────────────

    private static final class Task { }
}
