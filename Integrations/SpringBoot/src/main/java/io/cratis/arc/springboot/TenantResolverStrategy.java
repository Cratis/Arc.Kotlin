// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

/** Standard tenant resolver strategies available through Spring Boot configuration. */
public enum TenantResolverStrategy {
    /** Resolves one configured tenant. */
    FIXED,
    /** Resolves a case-insensitive request header. */
    HEADER,
    /** Resolves an exact query-string parameter. */
    QUERY,
    /** Resolves a principal claim. */
    CLAIM,
    /** Resolves the label before a configured base domain. */
    SUBDOMAIN,
    /** Resolves one configured development tenant. */
    DEVELOPMENT
}
