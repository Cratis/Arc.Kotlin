// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import jakarta.servlet.http.HttpServletRequest

/**
 * Deployment-owned proof that this request arrived through authenticated, header-rewriting ingress.
 * The default bean denies every request. Never trust caller-authored forwarding headers. If using
 * remoteAddr, the container and forwarding wrappers must preserve a verified transport peer; this
 * interface cannot establish network isolation or authenticate a proxy on its own.
 */
public fun interface ArcPlatformIdentityTrust {
    /** Returns true only inside the application's explicitly configured ingress boundary. */
    public fun isTrusted(request: HttpServletRequest): Boolean
}
