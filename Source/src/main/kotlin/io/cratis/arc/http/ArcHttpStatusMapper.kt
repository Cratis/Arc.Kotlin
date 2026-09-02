// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.http

import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.QueryResult

/** Maps host-agnostic Arc result contracts to their HTTP status semantics. */
public object ArcHttpStatusMapper {
    /** Maps a command result. */
    @JvmStatic
    public fun map(result: CommandResult<*>): ArcHttpStatus = when {
        result.isSuccess -> ArcHttpStatus.OK
        !result.isAuthorized -> ArcHttpStatus.FORBIDDEN
        !result.isValid -> ArcHttpStatus.BAD_REQUEST
        else -> ArcHttpStatus.INTERNAL_SERVER_ERROR
    }

    /** Maps a query result. */
    @JvmStatic
    public fun map(result: QueryResult<*>): ArcHttpStatus = when {
        result.isSuccess -> ArcHttpStatus.OK
        !result.isAuthorized -> ArcHttpStatus.FORBIDDEN
        !result.isValid -> ArcHttpStatus.BAD_REQUEST
        !result.isReady -> ArcHttpStatus.ACCEPTED
        else -> ArcHttpStatus.INTERNAL_SERVER_ERROR
    }
}
