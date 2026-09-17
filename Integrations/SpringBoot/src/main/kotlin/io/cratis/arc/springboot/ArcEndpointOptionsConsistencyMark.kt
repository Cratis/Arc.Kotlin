// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

/**
 * Sentinel type returned by the endpoint-options consistency check bean.
 *
 * The bean is internal and this type is not part of the public API. Its sole purpose is to give
 * the Spring bean factory a non-void return type so the factory method can throw on mismatch
 * and Spring reports it as a startup failure. Override the `arcEndpointOptionsConsistency` bean
 * by name only when a downstream test framework requires it.
 */
internal class ArcEndpointOptionsConsistencyMark
