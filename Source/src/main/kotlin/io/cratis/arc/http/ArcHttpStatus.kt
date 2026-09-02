// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.http

/** Host-neutral HTTP statuses used by Arc result mapping. */
public enum class ArcHttpStatus(public val code: Int) {
    OK(200),
    ACCEPTED(202),
    BAD_REQUEST(400),
    FORBIDDEN(403),
    INTERNAL_SERVER_ERROR(500)
}
