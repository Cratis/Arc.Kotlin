// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.observability.springboot

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.lang.reflect.Method
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext

internal class ArcObservationRecorder(
    private val registry: ObservationRegistry,
    private val properties: ArcObservabilityProperties
) {
    suspend fun <T> record(
        name: String,
        contextualName: String,
        tags: List<Pair<String, String>>,
        correlationId: UUID? = null,
        outcome: (T) -> String,
        block: suspend () -> T
    ): T {
        val observation = Observation.createNotStarted(name, registry).contextualName(contextualName)
        tags.forEach { (key, value) -> observation.lowCardinalityKeyValue(key, value) }
        correlationId?.let { observation.context.put(ArcObservationTags.CORRELATION_ID, it.toString()) }
        observation.start()
        return try {
            val result = withContext(
                ArcObservationContextElement(
                    observation,
                    correlationId?.toString(),
                    properties.isCorrelationLoggingEnabled,
                    properties.isCorrelationBaggageEnabled
                )
            ) {
                block()
            }
            val recordedOutcome = outcome(result)
            observation.lowCardinalityKeyValue(ArcObservationTags.OUTCOME, recordedOutcome)
            if (recordedOutcome == "error") observation.error(ArcErrorOutcome)
            result
        } catch (exception: CancellationException) {
            observation.lowCardinalityKeyValue(ArcObservationTags.OUTCOME, "cancelled")
            throw exception
        } catch (exception: Throwable) {
            observation.lowCardinalityKeyValue(ArcObservationTags.OUTCOME, "error")
            observation.error(exception)
            throw exception
        } finally {
            observation.stop()
        }
    }
}

private object ArcErrorOutcome : RuntimeException("Arc operation returned an error outcome.")

private class ArcObservationContextElement(
    private val observation: Observation,
    private val correlationId: String?,
    private val loggingEnabled: Boolean,
    private val baggageEnabled: Boolean
) : ThreadContextElement<ArcObservationThreadState> {
    companion object Key : CoroutineContext.Key<ArcObservationContextElement>

    override val key: CoroutineContext.Key<ArcObservationContextElement>
        get() = Key

    override fun updateThreadContext(context: CoroutineContext): ArcObservationThreadState {
        val observationScope = observation.openScope()
        val previousLoggingValue = if (loggingEnabled && correlationId != null) LoggingCorrelation.get() else null
        if (loggingEnabled && correlationId != null) LoggingCorrelation.put(correlationId)
        val baggageScope = if (baggageEnabled && correlationId != null) BaggageCorrelation.open(correlationId) else null
        return ArcObservationThreadState(observationScope, previousLoggingValue, baggageScope)
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: ArcObservationThreadState) {
        runCatching { oldState.baggageScope?.close() }
        if (loggingEnabled && correlationId != null) {
            if (oldState.previousLoggingValue == null) LoggingCorrelation.remove() else LoggingCorrelation.put(oldState.previousLoggingValue)
        }
        oldState.observationScope.close()
    }
}

private data class ArcObservationThreadState(
    val observationScope: Observation.Scope,
    val previousLoggingValue: String?,
    val baggageScope: AutoCloseable?
)

private object LoggingCorrelation {
    private data class Api(val get: Method, val put: Method, val remove: Method)

    private val api: Api? = runCatching {
        val type = Class.forName("org.slf4j.MDC")
        Api(
            type.getMethod("get", String::class.java),
            type.getMethod("put", String::class.java, String::class.java),
            type.getMethod("remove", String::class.java)
        )
    }.getOrNull()

    fun get(): String? = runCatching { api?.get?.invoke(null, ArcObservationTags.CORRELATION_ID) as? String }.getOrNull()

    fun put(value: String) {
        runCatching { api?.put?.invoke(null, ArcObservationTags.CORRELATION_ID, value) }
    }

    fun remove() {
        runCatching { api?.remove?.invoke(null, ArcObservationTags.CORRELATION_ID) }
    }
}

private object BaggageCorrelation {
    private data class Api(
        val current: Method,
        val toBuilder: Method,
        val put: Method,
        val build: Method,
        val makeCurrent: Method
    )

    private val api: Api? = runCatching {
        val baggageType = Class.forName("io.opentelemetry.api.baggage.Baggage")
        val builderType = Class.forName("io.opentelemetry.api.baggage.BaggageBuilder")
        Api(
            baggageType.getMethod("current"),
            baggageType.getMethod("toBuilder"),
            builderType.getMethod("put", String::class.java, String::class.java),
            builderType.getMethod("build"),
            baggageType.getMethod("makeCurrent")
        )
    }.getOrNull()

    fun open(value: String): AutoCloseable? = runCatching {
        val availableApi = api ?: return null
        val current = availableApi.current.invoke(null)
        val builder = availableApi.toBuilder.invoke(current)
        availableApi.put.invoke(builder, ArcObservationTags.CORRELATION_ID, value)
        val baggage = availableApi.build.invoke(builder)
        availableApi.makeCurrent.invoke(baggage) as? AutoCloseable
    }.getOrNull()
}
