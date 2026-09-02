// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

/** Controls the Secure attribute on Arc's client-readable identity cache cookie. */
public enum IdentityCookieSecurePolicy {
    /** Secure on HTTPS and in every profile except dev, development, and local. */
    AUTO,
    /** Always emit the Secure attribute. */
    ALWAYS,
    /** Never emit the Secure attribute. Intended only for explicitly accepted local development risk. */
    NEVER
}
