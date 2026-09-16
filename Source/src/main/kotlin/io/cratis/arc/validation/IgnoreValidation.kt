// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

/**
 * Excludes one logical member edge from validation before reading it or traversing its contents.
 * Kotlin property, field and getter sites describe the same edge. Java fields, bean getters and
 * record components (through their field/accessor annotations) are supported.
 *
 * This does not exclude the owner, a separately validated child root, or a nonignored alias.
 * Owner-level imperative validators and Jakarta class constraints remain active and may read the
 * member themselves. Serialization, binding and authorization are not affected. Jakarta requires
 * an explicitly composed [IgnoreValidationTraversableResolver]; the annotation alone does not
 * configure a provider. Constructor parameters, setters, types and query parameters are not opt-outs.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class IgnoreValidation
