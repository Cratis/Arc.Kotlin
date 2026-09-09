// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import com.mongodb.client.ChangeStreamIterable
import com.mongodb.client.MongoChangeStreamCursor
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.changestream.ChangeStreamDocument
import com.mongodb.client.model.changestream.FullDocument
import com.mongodb.client.model.changestream.OperationType
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.NullSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.mongodb.core.MongoOperations

internal class SpringDataMongoChangeStreamSourceTests {
    @ParameterizedTest
    @EnumSource(OperationType::class, names = ["INSERT", "UPDATE", "REPLACE", "DELETE", "INVALIDATE"])
    fun `native source maps supported operations and preserves payload metadata`(operation: OperationType) {
        val fixture = NativeCursorFixture()
        val key = BsonDocument("_id", BsonString("task-one"))
        val token = BsonDocument("_data", BsonString("next-token"))
        val resumeAfter = BsonDocument("_data", BsonString("previous-token"))
        val event = fixture.event(operation, key, token)
        `when`(fixture.cursor.tryNext()).thenReturn(event).thenReturn(null)

        fixture.source.open(MongoTaskReadModel::class.java, "tenant-a", resumeAfter).use { cursor ->
            val change = checkNotNull(cursor.next())
            assertEquals(MongoChangeOperation.valueOf(operation.name), change.operation)
            assertEquals("mapped-tasks", change.collectionName)
            assertEquals("tenant-a", change.tenantId)
            assertSame(key, change.documentKey)
            assertSame(token, change.resumeToken)
            assertNull(cursor.next())
        }

        verify(event).operationType
        verify(fixture.stream).resumeAfter(resumeAfter)
        fixture.verifyConfigurationAndClosure("tenant-a")
    }

    @ParameterizedTest
    @EnumSource(OperationType::class, names = ["INSERT", "UPDATE", "REPLACE", "DELETE", "INVALIDATE"])
    fun `native source preserves absent tenant document key and resume token`(operation: OperationType) {
        val fixture = NativeCursorFixture()
        val event = fixture.event(operation, null, null)
        `when`(fixture.cursor.tryNext()).thenReturn(event)

        fixture.source.open(MongoTaskReadModel::class.java, null, null).use { cursor ->
            val change = checkNotNull(cursor.next())
            assertEquals(MongoChangeOperation.valueOf(operation.name), change.operation)
            assertEquals("mapped-tasks", change.collectionName)
            assertNull(change.tenantId)
            assertNull(change.documentKey)
            assertNull(change.resumeToken)
        }

        verify(event).operationType
        fixture.verifyConfigurationAndClosure(null)
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(
        OperationType::class,
        mode = EnumSource.Mode.EXCLUDE,
        names = ["INSERT", "UPDATE", "REPLACE", "DELETE", "INVALIDATE"]
    )
    fun `native source skips other and null operations without consuming the following change`(operation: OperationType?) {
        val fixture = NativeCursorFixture()
        val ignored = fixture.event(operation, null, null)
        val following = fixture.event(OperationType.INSERT, null, null)
        `when`(fixture.cursor.tryNext()).thenReturn(ignored).thenReturn(following)

        fixture.source.open(MongoTaskReadModel::class.java, null, null).use { cursor ->
            assertNull(cursor.next())
            verify(fixture.cursor).tryNext()
            assertEquals(MongoChangeOperation.INSERT, checkNotNull(cursor.next()).operation)
        }

        verify(ignored).operationType
        verify(ignored, never()).documentKey
        verify(ignored, never()).resumeToken
        fixture.verifyConfigurationAndClosure(null)
    }

    private class NativeCursorFixture {
        val operations: MongoOperations = mock()
        val collection: MongoCollection<Document> = mock()
        val stream: ChangeStreamIterable<Document> = mock()
        val cursor: MongoChangeStreamCursor<ChangeStreamDocument<Document>> = mock()
        val resolver: MongoOperationsResolver = mock()
        val source = SpringDataMongoChangeStreamSource(
            resolver,
            MongoObservationOptions(cursorAwaitTime = Duration.ofMillis(37))
        )

        init {
            `when`(resolver.resolve("tenant-a")).thenReturn(operations)
            `when`(resolver.resolve(null)).thenReturn(operations)
            `when`(operations.getCollectionName(MongoTaskReadModel::class.java)).thenReturn("mapped-tasks")
            `when`(operations.getCollection("mapped-tasks")).thenReturn(collection)
            `when`(collection.watch()).thenReturn(stream)
            `when`(stream.fullDocument(FullDocument.UPDATE_LOOKUP)).thenReturn(stream)
            `when`(stream.maxAwaitTime(37, TimeUnit.MILLISECONDS)).thenReturn(stream)
            `when`(stream.resumeAfter(BsonDocument("_data", BsonString("previous-token")))).thenReturn(stream)
            `when`(stream.cursor()).thenReturn(cursor)
        }

        fun event(
            operation: OperationType?,
            key: BsonDocument?,
            token: BsonDocument?
        ): ChangeStreamDocument<Document> {
            val event: ChangeStreamDocument<Document> = mock()
            `when`(event.operationType).thenReturn(operation)
            `when`(event.documentKey).thenReturn(key)
            `when`(event.resumeToken).thenReturn(token)
            return event
        }

        fun verifyConfigurationAndClosure(tenantId: String?) {
            verify(resolver).resolve(tenantId)
            verify(operations).getCollectionName(MongoTaskReadModel::class.java)
            verify(operations).getCollection("mapped-tasks")
            verify(collection).watch()
            verify(stream).fullDocument(FullDocument.UPDATE_LOOKUP)
            verify(stream).maxAwaitTime(37, TimeUnit.MILLISECONDS)
            verify(stream).cursor()
            if (tenantId == null) {
                verify(stream, never()).resumeAfter(BsonDocument("_data", BsonString("previous-token")))
            }
            verify(cursor).close()
        }
    }
}
