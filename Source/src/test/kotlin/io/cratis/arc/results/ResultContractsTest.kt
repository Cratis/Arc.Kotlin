// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.results

import com.fasterxml.jackson.databind.JsonNode
import io.cratis.arc.ExceptionDetailRedactor
import io.cratis.arc.http.ArcHttpStatus
import io.cratis.arc.http.ArcHttpStatusMapper
import io.cratis.arc.json.ArcObjectMapper
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResultContractsTest {
    private val mapper = ArcObjectMapper.create()
    private val correlationId = UUID.fromString("ca2f4765-d22f-4829-94f9-b0060e0fe1f6")

    @Test
    fun `paging uses exact ceiling semantics and zero size has zero pages`() {
        assertEquals(3, PagingInfo(1, 10, 21).totalPages)
        assertEquals(2, PagingInfo(1, 10, 20).totalPages)
        assertEquals(0, PagingInfo(0, 0, 20).totalPages)
        assertEquals(0, PagingInfo(0, 10, 0).totalPages)
    }

    @Test
    fun `command result computes state and matches default wire shape`() {
        val result = CommandResult.success(correlationId)
        val tree = mapper.valueToTree<JsonNode>(result)

        assertEquals(
            setOf(
                "correlationId",
                "isAuthorized",
                "validationResults",
                "exceptionMessages",
                "exceptionStackTrace",
                "authorizationFailureReason",
                "isValid",
                "hasExceptions",
                "isSuccess"
            ),
            tree.fieldNames().asSequence().toSet()
        )
        assertEquals(correlationId.toString(), tree["correlationId"].textValue())
        assertTrue(tree["isAuthorized"].booleanValue())
        assertTrue(tree["isValid"].booleanValue())
        assertFalse(tree["hasExceptions"].booleanValue())
        assertTrue(tree["isSuccess"].booleanValue())
        assertTrue(tree["validationResults"].isArray && tree["validationResults"].isEmpty)
        assertTrue(tree["exceptionMessages"].isArray && tree["exceptionMessages"].isEmpty)
        assertEquals("", tree["exceptionStackTrace"].textValue())
        assertEquals("", tree["authorizationFailureReason"].textValue())
        assertNull(tree["response"])
        assertEquals(ArcHttpStatus.OK, ArcHttpStatusMapper.map(result))
    }

    @Test
    fun `validation defaults to rule and uses numeric severity`() {
        val validation = ValidationResult(ValidationResultSeverity.Error, "Name is required.")
        val result = CommandResult.invalid(correlationId, listOf(validation))
        val tree = mapper.valueToTree<JsonNode>(result)

        assertFalse(result.isValid)
        assertFalse(result.isSuccess)
        assertEquals(ArcHttpStatus.BAD_REQUEST, ArcHttpStatusMapper.map(result))
        assertEquals(3, tree["validationResults"][0]["severity"].intValue())
        assertEquals("rule", tree["validationResults"][0]["reason"].textValue())
        assertTrue(tree["validationResults"][0]["members"].isEmpty)
        assertNull(tree["validationResults"][0]["state"])
        assertNull(tree["validationResults"][0]["reasonDetail"])
        assertTrue(tree["exceptionMessages"].isEmpty)
        assertNull(tree["response"])
    }

    @Test
    fun `query result has exact ready success and paging shape`() {
        val result = QueryResult.success(
            correlationId,
            listOf("one", "two"),
            PagingInfo(2, 10, 21),
            ChangeSet(added = listOf("two"))
        )
        val tree = mapper.valueToTree<JsonNode>(result)

        assertEquals(
            setOf(
                "data",
                "correlationId",
                "isReady",
                "isAuthorized",
                "validationResults",
                "exceptionMessages",
                "exceptionStackTrace",
                "paging",
                "changeSet",
                "isValid",
                "hasExceptions",
                "isSuccess"
            ),
            tree.fieldNames().asSequence().toSet()
        )
        assertEquals(listOf("one", "two"), tree["data"].map { it.textValue() })
        assertEquals(2, tree["paging"]["page"].intValue())
        assertEquals(10, tree["paging"]["size"].intValue())
        assertEquals(21, tree["paging"]["totalItems"].intValue())
        assertEquals(3, tree["paging"]["totalPages"].intValue())
        assertEquals(listOf("two"), tree["changeSet"]["added"].map { it.textValue() })
        assertTrue(tree["changeSet"]["replaced"].isEmpty)
        assertTrue(tree["changeSet"]["removed"].isEmpty)
        assertTrue(tree["validationResults"].isEmpty)
        assertTrue(tree["exceptionMessages"].isEmpty)
        assertEquals("", tree["exceptionStackTrace"].textValue())
        assertNull(tree["authorizationFailureReason"])
    }

    @Test
    fun `status mapping follows Arc precedence`() {
        val validation = ValidationResult(ValidationResultSeverity.Error, "invalid")

        assertEquals(ArcHttpStatus.FORBIDDEN, ArcHttpStatusMapper.map(CommandResult.unauthorized(correlationId)))
        assertEquals(ArcHttpStatus.BAD_REQUEST, ArcHttpStatusMapper.map(CommandResult.invalid(correlationId, listOf(validation))))
        assertEquals(ArcHttpStatus.INTERNAL_SERVER_ERROR, ArcHttpStatusMapper.map(CommandResult.error(correlationId, "failed")))
        assertEquals(ArcHttpStatus.ACCEPTED, ArcHttpStatusMapper.map(QueryResult.notReady<String>(correlationId)))
        assertEquals(ArcHttpStatus.FORBIDDEN, ArcHttpStatusMapper.map(QueryResult.unauthorized<String>(correlationId)))
        assertEquals(ArcHttpStatus.BAD_REQUEST, ArcHttpStatusMapper.map(QueryResult.invalid<String>(correlationId, listOf(validation))))
        assertEquals(ArcHttpStatus.INTERNAL_SERVER_ERROR, ArcHttpStatusMapper.map(QueryResult.error<String>(correlationId, "failed")))
    }

    @Test
    fun `malformed command has exact client-safe body failure`() {
        val malformed = CommandResult.malformed(correlationId)
        val validation = malformed.validationResults.single()

        assertEquals("The request body could not be read or is not valid for this command.", validation.message)
        assertEquals(ValidationResultReasons.MALFORMED_REQUEST, validation.reason)
        assertNull(malformed.response)
    }

    @Test
    fun `exception factories retain detail for later host redaction`() {
        val exception = IllegalStateException("secret parser detail")
        val commandResult = CommandResult.exception(correlationId, exception)
        val queryResult = QueryResult.exception<String>(correlationId, exception)

        assertEquals(listOf("secret parser detail"), commandResult.exceptionMessages)
        assertTrue(commandResult.exceptionStackTrace.contains("IllegalStateException: secret parser detail"))
        assertEquals(listOf("secret parser detail"), queryResult.exceptionMessages)
        assertTrue(queryResult.exceptionStackTrace.contains("IllegalStateException: secret parser detail"))
        assertSame(commandResult, ExceptionDetailRedactor.redact(commandResult, true))
        assertSame(queryResult, ExceptionDetailRedactor.redact(queryResult, true))
    }

    @Test
    fun `redactor uses the shared production message and clears stack traces`() {
        val exception = IllegalStateException("secret parser detail")
        val commandResult = ExceptionDetailRedactor.redact(CommandResult.exception(correlationId, exception), false)
        val queryResult = ExceptionDetailRedactor.redact(QueryResult.exception<String>(correlationId, exception), false)

        assertEquals(
            "An internal error occurred while processing the request. See server logs for details.",
            ExceptionDetailRedactor.REDACTED_MESSAGE
        )
        assertEquals(listOf(ExceptionDetailRedactor.REDACTED_MESSAGE), commandResult.exceptionMessages)
        assertEquals("", commandResult.exceptionStackTrace)
        assertEquals(listOf(ExceptionDetailRedactor.REDACTED_MESSAGE), queryResult.exceptionMessages)
        assertEquals("", queryResult.exceptionStackTrace)
    }

    @Test
    fun `failed command result does not retain its response`() {
        val result = CommandResult(
            correlationId = correlationId,
            exceptionMessages = listOf("failed"),
            response = "must not escape"
        )

        assertNull(result.response)
        assertFalse(result.isSuccess)
        assertNull(mapper.valueToTree<JsonNode>(result)["response"])
    }
}
