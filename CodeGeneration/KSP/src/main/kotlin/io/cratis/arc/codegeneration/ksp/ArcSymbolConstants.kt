// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import io.cratis.arc.metadata.QueryParameterSource

// Fully qualified names, option keys and lookup tables shared by the Arc symbol processing collaborators.
internal val SHARED_WIRE_COLLECTION_TYPES = setOf(
    "kotlin.Array", "kotlin.collections.List", "kotlin.collections.Set", "kotlin.collections.Collection",
    "java.util.List", "java.util.Set", "java.util.Collection"
)
internal const val MODULE_NAME_OPTION = "arc.moduleName"
internal const val COMMAND_ANNOTATION = "io.cratis.arc.artifacts.Command"
internal const val COMMAND_SIMPLE_NAME = "Command"
internal const val COMMAND_EVENT_SOURCE_TYPE_ANNOTATION = "io.cratis.arc.artifacts.CommandEventSourceType"
internal const val COMMAND_EVENT_STREAM_TYPE_ANNOTATION = "io.cratis.arc.artifacts.CommandEventStreamType"
internal const val COMMAND_EVENT_STREAM_ID_ANNOTATION = "io.cratis.arc.artifacts.CommandEventStreamId"
internal const val COMMAND_EVENT_SUBJECT_ANNOTATION = "io.cratis.arc.artifacts.CommandEventSubject"
internal const val COMMAND_EVENT_STREAM_ID_PROVIDER = "io.cratis.arc.artifacts.CommandEventStreamIdProvider"
internal const val COMMAND_EVENT_SUBJECT_PROVIDER = "io.cratis.arc.artifacts.CommandEventSubjectProvider"
internal const val COMMAND_KEY_ANNOTATION = "io.cratis.arc.artifacts.CommandKey"
internal const val READ_MODEL_ANNOTATION = "io.cratis.arc.artifacts.ReadModel"
internal const val READ_MODEL_SIMPLE_NAME = "ReadModel"
internal const val EXPORTED_TYPE_ANNOTATION = "io.cratis.arc.artifacts.ExportedType"
internal const val EXPORTED_TYPE_SIMPLE_NAME = "ExportedType"
internal const val FROM_SERVICES_ANNOTATION = "io.cratis.arc.artifacts.FromServices"
internal const val TREAT_WARNINGS_AS_ERRORS_ANNOTATION = "io.cratis.arc.artifacts.TreatWarningsAsErrors"
internal const val AUTHORIZE_ANNOTATION = "io.cratis.arc.authorization.Authorize"
internal const val ROLES_ANNOTATION = "io.cratis.arc.authorization.Roles"
internal const val ROLES_CONTAINER_ANNOTATION = "io.cratis.arc.authorization.RolesContainer"
internal const val ALLOW_ANONYMOUS_ANNOTATION = "io.cratis.arc.authorization.AllowAnonymous"
internal const val PATH_ANNOTATION = "io.cratis.arc.queries.Path"
internal const val QUERY_HTTP_METHOD_ANNOTATION = "io.cratis.arc.queries.QueryHttpMethod"
internal const val QUERY_TRANSPORT_ANNOTATION = "io.cratis.arc.queries.QueryTransport"
internal const val QUERY_PAGE_TYPE = "io.cratis.arc.queries.QueryPage"
internal const val JAVA_OPTIONAL_TYPE = "java.util.Optional"
internal const val QUERY_REQUEST_TYPE = "io.cratis.arc.queries.QueryRequest"
internal const val QUERY_CONTEXT_TYPE = "io.cratis.arc.queries.QueryContext"
internal const val SPRING_PAGE_TYPE = "org.springframework.data.domain.Page"
internal const val SPRING_PAGEABLE_TYPE = "org.springframework.data.domain.Pageable"
internal const val SPRING_SORT_TYPE = "org.springframework.data.domain.Sort"
internal const val MAX_DEFAULTED_CLIENT_QUERY_PARAMETERS = 6
internal val QUERY_INFRASTRUCTURE_TYPES = mapOf(
    QUERY_REQUEST_TYPE to QueryParameterSource.QUERY_REQUEST,
    QUERY_CONTEXT_TYPE to QueryParameterSource.QUERY_CONTEXT
)
internal val QUERY_INFRASTRUCTURE_SIMPLE_NAMES = QUERY_INFRASTRUCTURE_TYPES.keys
    .map { typeName -> typeName.substringAfterLast('.') }
    .toSet()
internal val QUERY_HOST_ADAPTER_TYPES = mapOf(
    SPRING_PAGEABLE_TYPE to QueryHostAdapterKind.PAGEABLE,
    SPRING_SORT_TYPE to QueryHostAdapterKind.SORT
)
internal val QUERY_HOST_ADAPTER_SIMPLE_NAMES = QUERY_HOST_ADAPTER_TYPES.keys
    .map { typeName -> typeName.substringAfterLast('.') }
    .toSet()
internal val RESERVED_QUERY_PARAMETER_NAMES = setOf("page", "pagesize", "sortby", "sortdirection")
internal const val HANDLER_NAME = COMMAND_HANDLER_METHOD_NAME
internal const val PROVIDE_NAME = COMMAND_PROVIDE_METHOD_NAME
internal const val COMPLETION_STAGE_TYPE = "java.util.concurrent.CompletionStage"
internal const val KOTLIN_FLOW_TYPE = "kotlinx.coroutines.flow.Flow"
internal val JDK_PUBLISHER_TYPES = setOf("java.util.concurrent.Flow.Publisher", "java.util.concurrent.Flow\$Publisher")
internal const val COMMAND_RESULT_TYPE = "io.cratis.arc.results.CommandResult"
internal const val COMMAND_RESPONSE_VALUES_TYPE = "io.cratis.arc.commands.CommandResponseValues"
internal const val ARC_ONE_OF_TYPE = "io.cratis.arc.commands.ArcOneOf"
internal const val HANDLES_COMMAND_RESPONSE_VALUES_ANNOTATION =
    "io.cratis.arc.commands.HandlesCommandResponseValues"
internal const val HANDLES_COMMAND_RESPONSE_VALUES_SIMPLE_NAME = "HandlesCommandResponseValues"
internal const val ARRAY_TYPE = "kotlin.Array"
internal const val CHRONICLE_EVENT_TYPE_ANNOTATION = "io.cratis.chronicle.events.EventType"
internal val VOID_TYPE_NAMES = setOf("void", "java.lang.Void", "kotlin.Unit")
internal val PROVIDE_CONTROL_TYPES = setOf(
    "io.cratis.arc.results.ValidationResult",
    "io.cratis.arc.authorization.AuthorizationResult",
    "io.cratis.arc.results.CommandResult"
)
internal val PROVIDE_DYNAMIC_TYPES = setOf("kotlin.Any", "java.lang.Object")
internal val AGGREGATE_TYPE_ARITIES = mapOf(
    "kotlin.Pair" to 2,
    "kotlin.Triple" to 3
)
internal val HANDLED_RESPONSE_LEAF_TYPES = setOf(
    "io.cratis.arc.results.ValidationResult",
    "io.cratis.arc.authorization.AuthorizationResult",
    COMMAND_RESULT_TYPE,
    "io.cratis.chronicle.eventSequences.EventForEventSourceId",
    "io.cratis.arc.chronicle.EventsWithConcurrencyScopes"
)
internal val HANDLED_COLLECTION_ELEMENT_TYPES = setOf(
    "io.cratis.arc.results.ValidationResult",
    "io.cratis.chronicle.eventSequences.EventForEventSourceId"
)
internal val SUPPORTED_RESPONSE_VALUE_HANDLER_TYPES = setOf(
    "io.cratis.arc.commands.CommandResponseValueHandler",
    "io.cratis.arc.java.BlockingCommandResponseValueHandler",
    "io.cratis.arc.java.AsyncCommandResponseValueHandler"
)
internal val COLLECTION_TYPE_NAMES = setOf(
    "kotlin.collections.List",
    "kotlin.collections.MutableList",
    "kotlin.collections.Collection",
    "kotlin.collections.MutableCollection",
    "java.util.List",
    "java.util.Collection"
)
internal val MAP_TYPE_NAMES = setOf("kotlin.collections.Map", "kotlin.collections.MutableMap", "java.util.Map")
internal val RXJAVA3_OBSERVABLE_TYPES = setOf(
    "io.reactivex.rxjava3.core.Observable",
    "io.reactivex.rxjava3.core.ObservableSource",
    "io.reactivex.rxjava3.subjects.Subject"
)
internal val UNSUPPORTED_STREAM_TYPES = setOf("org.reactivestreams.Publisher")
