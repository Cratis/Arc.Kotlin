// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.authorization.Authorize
import io.cratis.arc.queries.Path
import io.cratis.arc.queries.QueryTransport
import io.cratis.arc.queries.QueryTransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

public data class MissingCommand(public val value: String) {
    public fun handle(): String = value.uppercase()
}

@Command
public data class MissingHandle(public val value: String)

@Command
public data class MultipleHandles(public val value: String) {
    public fun handle(): String = value
    public fun handle(suffix: Int): String = "$value$suffix"
}

@Command
public data class NonPublicHandle(public val value: String) {
    private fun handle(): String = value
}

@Command
public data class MultipleKeys(
    @CommandKey public val first: String,
    @CommandKey public val second: String
) {
    public fun handle(): String = "$first:$second"
}

public class NestedArtifacts {
    @Command
    public data class NestedCommand(public val value: String) {
        public fun handle(): String = value
    }
}

@Command
public data class GenericCommand<T>(public val value: T) {
    public fun handle(): T = value
}

@Command
@AllowAnonymous
@Authorize(policy = "secured")
public data class ConflictingAuthorization(public val value: String) {
    public fun handle(): String = value
}

@Command
public data class GenericProvide(public val value: String) {
    public fun <T> provide(candidate: T): T = candidate
    public fun handle(): String = value
}

@Command
public data class UnusedProvidedValue(public val value: String) {
    public fun provide(): Int = value.length
    public fun handle(): String = value
}

@Command
public data class DefaultedHandlerParameter(public val value: String) {
    public fun handle(suffix: String = "default"): String = "$value:$suffix"
}

@Command
public data class NullableResponse(public val value: String) {
    public fun handle(): String? = value.takeIf(String::isNotEmpty)
}

@Command
public data class NothingResponse(public val value: String) {
    public fun handle(): Nothing = error(value)
}

@Command
public data class NullableDependency(public val value: String) {
    public fun handle(dependency: QueryDependency?): String = dependency?.prefix(value) ?: value
}

@Command
public data class StarProxyShape(public val values: List<*>) {
    public fun handle(): Int = values.size
}

@Command
public data class ValidCommand(public val value: String) {
    public fun handle(): String = value
}

public class ExternalHandler {
    public fun handle(command: ValidCommand): String = command.value
}

public interface QueryDependency {
    public fun prefix(value: String): String
}

@ReadModel
public data class InstanceQuery(public val value: String) {
    public fun all(): List<InstanceQuery> = listOf(this)
}

@ReadModel
public data class OverloadedQueries(public val value: String) {
    public companion object {
        public fun all(): List<OverloadedQueries> = emptyList()
        public fun all(prefix: String): List<OverloadedQueries> = listOf(OverloadedQueries(prefix))
    }
}

@ReadModel
public data class GenericReadModel<T>(public val value: T)

@ReadModel
public data class GenericQuery(public val value: String) {
    public companion object {
        public fun <T> byValue(value: T): List<GenericQuery> = listOf(GenericQuery(value.toString()))
    }
}

@ReadModel
public data class WrongReturn(public val value: String) {
    public companion object {
        @Path("/invalid-wrong-return")
        public fun all(): String = "not a read model"
    }
}

@ReadModel
public data class AmbiguousServiceParameter(public val value: String) {
    public companion object {
        public fun all(dependency: QueryDependency): List<AmbiguousServiceParameter> =
            listOf(AmbiguousServiceParameter(dependency.prefix("value")))
    }
}

@ReadModel
public data class NullableQueryReturn(public val value: String) {
    public companion object {
        public fun single(): NullableQueryReturn? = null
    }
}

@ReadModel
public data class ObservableMismatch(public val value: String) {
    public companion object {
        @QueryTransport(QueryTransportType.REQUEST_RESPONSE)
        public fun observe(): Flow<ObservableMismatch> = flowOf(ObservableMismatch("value"))
    }
}

@ReadModel
public data class RequestResponseMismatch(public val value: String) {
    public companion object {
        @QueryTransport(QueryTransportType.OBSERVABLE)
        public fun single(): RequestResponseMismatch = RequestResponseMismatch("value")
    }
}

@ReadModel
public data class FirstDuplicateRoute(public val value: String) {
    public companion object {
        @Path("/duplicate-route")
        public fun all(): List<FirstDuplicateRoute> = emptyList()
    }
}

@ReadModel
public data class SecondDuplicateRoute(public val value: String) {
    public companion object {
        @Path("duplicate-route/")
        public fun all(): List<SecondDuplicateRoute> = emptyList()
    }
}

public open class PolymorphicBase(public val value: String)

@ReadModel
public data class PolymorphicProxyShape(public val model: PolymorphicBase) {
    public companion object {
        public fun all(): List<PolymorphicProxyShape> = emptyList()
    }
}
