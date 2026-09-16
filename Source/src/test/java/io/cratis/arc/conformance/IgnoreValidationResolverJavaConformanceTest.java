// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.validation.IgnoreValidation;
import io.cratis.arc.validation.IgnoreValidationTraversableResolver;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.validation.TraversableResolver;
import java.lang.annotation.ElementType;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Resolver composition tests only; actual Jakarta provider execution belongs to the host tests. */
final class IgnoreValidationResolverJavaConformanceTest {
    @Test
    void ignoredFieldAndGetterEdgesDoNotReachTheDelegateOrReadTheOwner() {
        var owner = new Owner();
        var path = new MemberPath(List.of());
        var ignored = new MemberNode("ignored");
        var delegate = new RecordingResolver(owner, ignored, path, true);
        var resolver = new IgnoreValidationTraversableResolver(delegate);
        for (var site : List.of(ElementType.FIELD, ElementType.METHOD)) {
            assertFalse(resolver.isReachable(owner, ignored, Owner.class, path, site));
            assertFalse(resolver.isCascadable(owner, ignored, Owner.class, path, site));
        }
        assertEquals(0, delegate.reachableCalls);
        assertEquals(0, delegate.cascadableCalls);
        assertEquals(0, owner.reads);
    }

    @Test
    void activeEdgesPreserveBothDelegateDecisionsAndOriginalArguments() {
        var owner = new Owner();
        var path = new MemberPath(List.of(new MemberNode("parent")));
        var sibling = new MemberNode("sibling");
        for (boolean answer : List.of(true, false)) {
            var delegate = new RecordingResolver(owner, sibling, path, answer);
            var resolver = new IgnoreValidationTraversableResolver(delegate);
            assertEquals(answer, resolver.isReachable(owner, sibling, Owner.class, path, ElementType.FIELD));
            assertEquals(answer, resolver.isCascadable(owner, sibling, Owner.class, path, ElementType.FIELD));
            assertEquals(1, delegate.reachableCalls);
            assertEquals(1, delegate.cascadableCalls);
            assertEquals(ElementType.FIELD, delegate.site);
        }
    }

    @Test
    void nonMemberRequestsAndUnknownOwnersAreDelegatedRatherThanInferredAsIgnored() {
        var owner = new Owner();
        var path = new MemberPath(List.of());
        var ignored = new MemberNode("ignored");
        var delegate = new RecordingResolver(owner, ignored, path, true);
        var resolver = new IgnoreValidationTraversableResolver(delegate);
        assertTrue(resolver.isReachable(owner, ignored, Owner.class, path, ElementType.PARAMETER));
        assertTrue(resolver.isCascadable(owner, ignored, Owner.class, path, ElementType.TYPE));
        assertEquals(1, delegate.reachableCalls);
        assertEquals(1, delegate.cascadableCalls);
        var unknownDelegate = new RecordingResolver(null, ignored, path, true);
        var unknown = new IgnoreValidationTraversableResolver(unknownDelegate);
        assertTrue(unknown.isReachable(null, ignored, Owner.class, path, ElementType.FIELD));
        assertTrue(unknown.isCascadable(null, ignored, Owner.class, path, ElementType.FIELD));
        assertEquals(1, unknownDelegate.reachableCalls);
        assertEquals(1, unknownDelegate.cascadableCalls);
    }

    public static final class Owner {
        private int reads;
        @IgnoreValidation public String getIgnored() { reads++; throw new AssertionError("resolver read ignored getter"); }
        public String getSibling() { throw new AssertionError("metadata resolver must not read any getter"); }
    }

    private static final class RecordingResolver implements TraversableResolver {
        private final Object owner;
        private final Path.Node node;
        private final Path path;
        private final boolean answer;
        private int reachableCalls;
        private int cascadableCalls;
        private ElementType site;

        private RecordingResolver(Object owner, Path.Node node, Path path, boolean answer) {
            this.owner = owner;
            this.node = node;
            this.path = path;
            this.answer = answer;
        }
        @Override public boolean isReachable(Object value, Path.Node property, Class<?> root, Path parent, ElementType element) {
            reachableCalls++;
            check(value, property, root, parent, element);
            return answer;
        }
        @Override public boolean isCascadable(Object value, Path.Node property, Class<?> root, Path parent, ElementType element) {
            cascadableCalls++;
            check(value, property, root, parent, element);
            return answer;
        }
        private void check(Object value, Path.Node property, Class<?> root, Path parent, ElementType element) {
            assertSame(owner, value);
            assertSame(node, property);
            assertSame(Owner.class, root);
            assertSame(path, parent);
            site = element;
        }
    }

    private record MemberPath(List<Path.Node> nodes) implements Path {
        @Override public Iterator<Path.Node> iterator() { return nodes.iterator(); }
    }
    private record MemberNode(String name) implements Path.PropertyNode {
        @Override public String getName() { return name; }
        @Override public boolean isInIterable() { return false; }
        @Override public Integer getIndex() { return null; }
        @Override public Object getKey() { return null; }
        @Override public ElementKind getKind() { return ElementKind.PROPERTY; }
        @Override public Class<?> getContainerClass() { return null; }
        @Override public Integer getTypeArgumentIndex() { return null; }
        @Override public <T extends Path.Node> T as(Class<T> type) { return type.cast(this); }
    }
}
