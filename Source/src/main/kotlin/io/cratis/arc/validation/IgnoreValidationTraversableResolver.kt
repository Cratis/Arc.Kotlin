// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import jakarta.validation.ElementKind
import jakarta.validation.Path
import jakarta.validation.TraversableResolver
import java.lang.annotation.ElementType

/**
 * Explicitly composes pre-access member exclusion with an application's existing Jakarta resolver.
 * Pass the resolver actually configured on the factory, not an assumed provider default. This
 * adapter owns neither a factory nor a validator and does not install itself into a host.
 * Class constraints and executable parameter/return-value constraints are not member edges.
 * Calls without an owner object are delegated unchanged; this is not a capability check for an
 * opaque Validator or for provider APIs which bypass traversal.
 */
public class IgnoreValidationTraversableResolver(private val delegate: TraversableResolver) : TraversableResolver {
    override fun isReachable(
        traversableObject: Any?, traversableProperty: Path.Node, rootBeanType: Class<*>,
        pathToTraversableObject: Path, elementType: ElementType
    ): Boolean = !ignored(traversableObject, traversableProperty, elementType) &&
        delegate.isReachable(traversableObject, traversableProperty, rootBeanType, pathToTraversableObject, elementType)

    override fun isCascadable(
        traversableObject: Any?, traversableProperty: Path.Node, rootBeanType: Class<*>,
        pathToTraversableObject: Path, elementType: ElementType
    ): Boolean = !ignored(traversableObject, traversableProperty, elementType) &&
        delegate.isCascadable(traversableObject, traversableProperty, rootBeanType, pathToTraversableObject, elementType)

    private fun ignored(owner: Any?, property: Path.Node, elementType: ElementType): Boolean =
        owner != null && (elementType == ElementType.FIELD || elementType == ElementType.METHOD ||
            elementType == ElementType.TYPE_USE && property.kind == ElementKind.PROPERTY) &&
            property.name?.let { ValidationMemberPolicy.isIgnored(owner.javaClass, it) } == true
}
