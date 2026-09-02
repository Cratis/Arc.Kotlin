// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandKeyProvider
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.testing.CommandScenario
import io.cratis.chronicle.eventSequences.EventForEventSourceId
import io.cratis.chronicle.eventSequences.EventSequenceNumber
import io.cratis.chronicle.events.EventType
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChronicleCommandScenarioTests {
    @Test
    fun `discovers Chronicle extension and captures appended events without givens`() = runBlocking {
        val first = ScenarioEvent("first")
        val second = ScenarioOtherEvent("second")
        val scenario = CommandScenario<ScenarioCommand>(ScenarioCommandHandler(listOf(first, second)))
            .withSerializationRoundTrip(false)

        scenario.givenChronicle().events("source", ScenarioEvent("given"))
        val result = scenario.execute(ScenarioCommand("source"))

        result.shouldSucceed()
        scenario.chronicle().shouldHaveAppendedEvents(2)
        assertSame(first, scenario.chronicle().shouldHaveAppendedEvent("source", ScenarioEvent::class.java))
        assertEquals(listOf(1L, 2L), scenario.chronicle().appendedEvents.map { it.sequenceNumber })
    }

    @Test
    fun `event log keeps namespace histories and sequence numbers isolated`() = runBlocking {
        val eventLog = ChronicleScenarioEventLog()
        val tenantEventLog = eventLog.forNamespace("tenant-one")
        eventLog.given("shared", listOf(ScenarioEvent("default-given")))
        tenantEventLog.given("shared", listOf(ScenarioEvent("tenant-given")))

        val defaultResult = eventLog.append("shared", ScenarioEvent("default-appended"), null)
        val tenantResult = tenantEventLog.append("shared", ScenarioEvent("tenant-appended"), null)

        assertEquals(1L, defaultResult.sequenceNumber.value)
        assertNull(defaultResult.concurrencyViolation)
        assertEquals(1L, tenantResult.sequenceNumber.value)
        assertEquals(
            listOf("default", "default"),
            eventLog.getFromSequenceNumber(EventSequenceNumber.first).map { it.context.namespace }
        )
        assertEquals(
            listOf("tenant-one", "tenant-one"),
            tenantEventLog.getFromSequenceNumber(EventSequenceNumber.first).map { it.context.namespace }
        )
    }

    @Test
    fun `scenario cross-source append is atomic when event preparation fails`() = runBlocking {
        val eventLog = ChronicleScenarioEventLog()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                eventLog.appendMany(
                    listOf(
                        EventForEventSourceId("valid", ScenarioEvent("valid")),
                        EventForEventSourceId("invalid", NotAScenarioEvent("invalid"))
                    ),
                    emptyMap(),
                    UUID.randomUUID()
                )
            }
        }

        assertTrue(eventLog.getFromSequenceNumber(EventSequenceNumber.first).isEmpty())
    }

    @Test
    fun `can arrange and assert a constraint failure`() = runBlocking {
        val scenario = CommandScenario<ScenarioCommand>(
            ScenarioCommandHandler(listOf(ScenarioEvent("event"), ScenarioOtherEvent("second")))
        ).withSerializationRoundTrip(false)
        scenario.chronicle().given().constraintViolation("unique-name", "The name is already used.")

        val result = scenario.execute(ScenarioCommand("source"))

        result.shouldFail()
        assertEquals(
            ValidationResultReasons.CONSTRAINT_VIOLATION,
            result.shouldHaveConstraintViolation("unique-name").reason
        )
        scenario.chronicle().shouldHaveNoAppendedEvents()
    }

    @Test
    fun `can arrange and assert a concurrency failure`() = runBlocking {
        val scenario = CommandScenario<ScenarioCommand>(ScenarioCommandHandler(listOf(ScenarioEvent("event"))))
            .withSerializationRoundTrip(false)
        scenario.chronicle().given().concurrencyViolation("source", 2, 3)

        val result = scenario.execute(ScenarioCommand("source"))

        result.shouldFail()
        val violation = result.shouldHaveConcurrencyViolation("source")
        assertEquals(mapOf("expectedSequenceNumber" to 2L, "actualSequenceNumber" to 3L), violation.state)
        scenario.chronicle().shouldHaveNoAppendedEvents()
    }
}

public data class ScenarioCommand(public val key: String) : CommandKeyProvider {
    override fun commandKey(): Any = key
}

@EventType
public data class ScenarioEvent(public val value: String)

@EventType
public data class ScenarioOtherEvent(public val value: String)

private data class NotAScenarioEvent(val value: String)

public class ScenarioCommandHandler(private val response: List<Any>) : CommandHandler {
    override val commandType: Class<*> = ScenarioCommand::class.java
    override val metadata: CommandDescriptor = CommandDescriptor(
        "ScenarioCommand",
        commandType.name,
        authorization = AuthorizationMetadata(allowAnonymous = true)
    )

    override suspend fun invoke(context: CommandContext): Any = response
}
