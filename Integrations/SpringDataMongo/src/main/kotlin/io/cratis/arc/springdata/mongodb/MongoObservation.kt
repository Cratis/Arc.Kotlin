// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import com.mongodb.client.model.changestream.ChangeStreamDocument
import com.mongodb.client.model.changestream.FullDocument
import com.mongodb.client.model.changestream.OperationType
import java.time.Duration
import java.util.concurrent.Flow as JdkFlow
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bson.BsonDocument
import org.bson.BsonValue
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.MongoOperations
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query

/** The MongoDB change operation that invalidated an observable query snapshot. */
public enum class MongoChangeOperation {
    INSERT,
    UPDATE,
    REPLACE,
    DELETE,
    INVALIDATE
}

/** A bounded change-stream notification. Documents are re-read instead of being exposed as mutable event payloads. */
public data class MongoChange(
    public val operation: MongoChangeOperation,
    public val collectionName: String,
    public val tenantId: String?,
    public val documentKey: BsonDocument?,
    public val resumeToken: BsonDocument?
)

/** Configures reconnect and buffering behavior for MongoDB change streams. */
public data class MongoObservationOptions @JvmOverloads constructor(
    public val initialReconnectDelay: Duration = Duration.ofMillis(100),
    public val maximumReconnectDelay: Duration = Duration.ofSeconds(30),
    public val cursorAwaitTime: Duration = Duration.ofMillis(500),
    public val bufferCapacity: Int = 1
) {
    init {
        require(!initialReconnectDelay.isNegative && !initialReconnectDelay.isZero)
        require(maximumReconnectDelay >= initialReconnectDelay)
        require(!cursorAwaitTime.isNegative && !cursorAwaitTime.isZero)
        require(bufferCapacity >= 0)
    }
}

/** Resolves Spring Data Mongo operations for a captured tenant. */
public fun interface MongoOperationsResolver {
    /** Returns operations for [tenantId]. The resolver must not consult thread-local request state. */
    public fun resolve(tenantId: String?): MongoOperations
}

/** A closeable, blocking cursor opened by [MongoChangeStreamSource]. */
public interface MongoChangeStreamCursor : AutoCloseable {
    /** Returns the next change, or null when the server await interval elapsed. */
    public fun next(): MongoChange?
}

/** Opens native MongoDB change-stream cursors. This seam also makes reconnect behavior deterministic to test. */
public fun interface MongoChangeStreamSource {
    /** Opens a cursor for [documentType], optionally resuming after [resumeToken]. */
    public fun open(documentType: Class<*>, tenantId: String?, resumeToken: BsonDocument?): MongoChangeStreamCursor
}

/** Provides cold MongoDB change notifications. Every collector owns and cancels its cursor. */
public fun interface MongoChangeStreamWatcher {
    /** Watches the mapped collection and optionally narrows notifications to [documentKey]. */
    public fun watch(documentType: Class<*>, tenantId: String?, documentKey: Any?): Flow<MongoChange>
}

/** Spring Data implementation of [MongoChangeStreamSource] using native MongoDB change streams. */
public class SpringDataMongoChangeStreamSource(
    private val operationsResolver: MongoOperationsResolver,
    private val options: MongoObservationOptions = MongoObservationOptions()
) : MongoChangeStreamSource {
    override fun open(
        documentType: Class<*>,
        tenantId: String?,
        resumeToken: BsonDocument?
    ): MongoChangeStreamCursor {
        val operations = operationsResolver.resolve(tenantId)
        val collectionName = operations.getCollectionName(documentType)
        var stream = operations.getCollection(collectionName)
            .watch()
            .fullDocument(FullDocument.UPDATE_LOOKUP)
            .maxAwaitTime(options.cursorAwaitTime.toMillis(), TimeUnit.MILLISECONDS)
        if (resumeToken != null) stream = stream.resumeAfter(resumeToken)
        val cursor = stream.cursor()
        return object : MongoChangeStreamCursor {
            override fun next(): MongoChange? = cursor.tryNext()?.toMongoChange(collectionName, tenantId)
            override fun close() = cursor.close()
        }
    }

    private fun ChangeStreamDocument<org.bson.Document>.toMongoChange(
        collectionName: String,
        tenantId: String?
    ): MongoChange? {
        val mapped = when (operationType) {
            OperationType.INSERT -> MongoChangeOperation.INSERT
            OperationType.UPDATE -> MongoChangeOperation.UPDATE
            OperationType.REPLACE -> MongoChangeOperation.REPLACE
            OperationType.DELETE -> MongoChangeOperation.DELETE
            OperationType.INVALIDATE -> MongoChangeOperation.INVALIDATE
            else -> null
        } ?: return null
        return MongoChange(mapped, collectionName, tenantId, documentKey, resumeToken)
    }
}

/** Reconnecting, cancellation-aware [MongoChangeStreamWatcher] with a finite downstream buffer. */
public class ReconnectingMongoChangeStreamWatcher @JvmOverloads constructor(
    private val source: MongoChangeStreamSource,
    private val options: MongoObservationOptions = MongoObservationOptions()
) : MongoChangeStreamWatcher {
    private val logger = LoggerFactory.getLogger(ReconnectingMongoChangeStreamWatcher::class.java)

    override fun watch(documentType: Class<*>, tenantId: String?, documentKey: Any?): Flow<MongoChange> {
        val changes = flow {
            var resumeToken: BsonDocument? = null
            var reconnectDelay = options.initialReconnectDelay
            while (currentCoroutineContext().isActive) {
                try {
                    val cursor = withContext(Dispatchers.IO) { source.open(documentType, tenantId, resumeToken) }
                    try {
                        reconnectDelay = options.initialReconnectDelay
                        while (currentCoroutineContext().isActive) {
                            currentCoroutineContext().ensureActive()
                            val change = withContext(Dispatchers.IO) { cursor.next() }
                            if (change == null) continue
                            resumeToken = change.resumeToken
                            if (change.operation == MongoChangeOperation.INVALIDATE) resumeToken = null
                            if (documentKey == null || change.operation == MongoChangeOperation.INVALIDATE ||
                                change.documentKey.matches(documentKey)) {
                                emit(change)
                            }
                            if (change.operation == MongoChangeOperation.INVALIDATE) break
                        }
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) { cursor.close() }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    logger.warn(
                        "MongoDB change stream for {} and tenant {} failed; reconnecting in {} ms",
                        documentType.name,
                        tenantId,
                        reconnectDelay.toMillis(),
                        failure
                    )
                }
                currentCoroutineContext().ensureActive()
                delay(reconnectDelay.toMillis())
                reconnectDelay = reconnectDelay.multipliedBy(2).coerceAtMost(options.maximumReconnectDelay)
            }
        }
        return if (options.bufferCapacity == 0) changes else changes.buffer(options.bufferCapacity)
    }

    private fun BsonDocument?.matches(expected: Any): Boolean {
        val actual = this?.get("_id") ?: return false
        return actual.matches(expected)
    }

    private fun BsonValue.matches(expected: Any): Boolean = when {
        isString -> asString().value == expected.toString()
        isObjectId -> asObjectId().value == expected || asObjectId().value.toHexString() == expected.toString()
        isInt32 && expected is Number -> asInt32().value.toLong() == expected.toLong()
        isInt64 && expected is Number -> asInt64().value == expected.toLong()
        else -> toString() == expected.toString()
    }
}

/** Injectable snapshot query service intended for `@ReadModel` query methods through `@FromServices`. */
public class MongoObservableQuery @JvmOverloads constructor(
    private val operationsResolver: MongoOperationsResolver,
    private val watcher: MongoChangeStreamWatcher,
    private val callbackScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : AutoCloseable {
    /** Emits an initial list snapshot and a replacement snapshot after each relevant change. */
    @JvmOverloads
    public fun <T : Any> observe(
        documentType: Class<T>,
        query: Query = Query(),
        tenantId: String? = null
    ): Flow<List<T>> = snapshots(documentType, query, tenantId, null)

    /** Alias emphasizing that each emission is a complete list snapshot. */
    @JvmOverloads
    public fun <T : Any> observeList(
        documentType: Class<T>,
        query: Query = Query(),
        tenantId: String? = null
    ): Flow<List<T>> = observe(documentType, query, tenantId)

    /** Emits matching single-document snapshots. Absence is not emitted, matching Arc's existing observe-single semantics. */
    @JvmOverloads
    public fun <T : Any> observeSingle(
        documentType: Class<T>,
        query: Query = Query(),
        tenantId: String? = null
    ): Flow<T> = snapshots(documentType, query.limit(1), tenantId, null).mapNotNull(List<T>::firstOrNull)

    /** Emits snapshots for the mapped `_id`; unrelated document changes are ignored. */
    @JvmOverloads
    public fun <T : Any> observeById(documentType: Class<T>, id: Any, tenantId: String? = null): Flow<T> =
        snapshots(documentType, Query.query(Criteria.where("_id").`is`(id)).limit(1), tenantId, id)
            .mapNotNull(List<T>::firstOrNull)

    /** Shares one cold observation while subscribers exist and replays the latest complete snapshot. */
    @JvmOverloads
    public fun <T : Any> observeShared(
        scope: CoroutineScope,
        documentType: Class<T>,
        query: Query = Query(),
        tenantId: String? = null
    ): SharedFlow<List<T>> = observe(documentType, query, tenantId)
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

    /** Java bridge with demand-aware backpressure. */
    @JvmOverloads
    public fun <T : Any> observePublisher(
        documentType: Class<T>,
        query: Query = Query(),
        tenantId: String? = null
    ): JdkFlow.Publisher<List<T>> = DemandAwareFlowPublisher(observe(documentType, query, tenantId))

    /** Java callback bridge. Closing the result cancels the collection immediately. */
    @JvmOverloads
    public fun <T : Any> observe(
        documentType: Class<T>,
        query: Query = Query(),
        tenantId: String? = null,
        callback: Consumer<List<T>>
    ): AutoCloseable {
        val job = callbackScope.launch { observe(documentType, query, tenantId).collect(callback::accept) }
        return AutoCloseable(job::cancel)
    }

    override fun close() {
        callbackScope.coroutineContext[Job]?.cancel()
    }

    private fun <T : Any> snapshots(
        documentType: Class<T>,
        query: Query,
        tenantId: String?,
        documentKey: Any?
    ): Flow<List<T>> = flow {
        fun snapshot(): List<T> = operationsResolver.resolve(tenantId).find(query, documentType).toList()
        emit(snapshot())
        watcher.watch(documentType, tenantId, documentKey).collect { emit(snapshot()) }
    }.flowOn(Dispatchers.IO).buffer(0).distinctUntilChanged()
}

private fun Duration.coerceAtMost(maximum: Duration): Duration = if (this > maximum) maximum else this
