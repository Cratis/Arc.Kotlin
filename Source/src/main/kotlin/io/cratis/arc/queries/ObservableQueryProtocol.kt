// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue

/** Message types exchanged by observable-query hubs. */
@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum class ObservableQueryHubMessageType {
    Subscribe,
    Unsubscribe,
    QueryResult,
    Unauthorized,
    Error,
    Ping,
    Pong,
    Connected
}

/** Snapshot transfer behavior requested by an observable-query subscriber. */
public enum class ObservableQueryTransferMode(@get:JsonValue public val wireValue: String) {
    DELTA("delta"),
    FULL("full")
}

/** Wire-level revision limits shared with JavaScript clients. */
public object ObservableQuerySubscriptionRevision {
    public const val MAX_VALUE: Long = 9_007_199_254_740_991L

    @JvmStatic
    public fun isValid(revision: Long?): Boolean = revision == null || revision in 1..MAX_VALUE

    internal fun requireValid(revision: Long?) {
        require(isValid(revision)) { "revision must be positive and no greater than $MAX_VALUE" }
    }
}

/** A transport-neutral observable-query hub frame. */
public class ObservableQueryHubMessage @JvmOverloads constructor(
    public val type: ObservableQueryHubMessageType,
    public val queryId: String? = null,
    public val revision: Long? = null,
    public val payload: Any? = null,
    public val timestamp: Long? = null,
    public val keepAliveIntervalMs: Long? = null,
    @get:JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @get:JsonProperty("supportsSubscriptionRevisions")
    public val supportsSubscriptionRevisions: Boolean = false
) {
    init {
        ObservableQuerySubscriptionRevision.requireValid(revision)
    }
}

/** Payload of an observable-query subscribe message. */
public class ObservableQuerySubscriptionRequest @JvmOverloads constructor(
    public val queryName: String,
    arguments: Map<String, String?>? = null,
    public val page: Int? = null,
    public val pageSize: Int? = null,
    public val sortBy: String? = null,
    public val sortDirection: String? = null,
    public val transferMode: ObservableQueryTransferMode? = null
) {
    public val arguments: Map<String, String?>? = arguments?.let { java.util.Collections.unmodifiableMap(LinkedHashMap(it)) }
}

/** POST body for subscribing on an SSE observable-query connection. */
public class ObservableQuerySSESubscribeRequest @JvmOverloads constructor(
    public val connectionId: String,
    public val queryId: String,
    public val request: ObservableQuerySubscriptionRequest,
    public val revision: Long? = null
) {
    init {
        ObservableQuerySubscriptionRevision.requireValid(revision)
    }
}

/** POST body for unsubscribing on an SSE observable-query connection. */
public class ObservableQuerySSEUnsubscribeRequest @JvmOverloads constructor(
    public val connectionId: String,
    public val queryId: String,
    public val revision: Long? = null
) {
    init {
        ObservableQuerySubscriptionRevision.requireValid(revision)
    }
}
