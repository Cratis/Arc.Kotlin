// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import io.cratis.arc.metadata.QueryParameterSource

/** Renders the generated query performer source and query descriptor expressions. */
internal object ArcQueryRenderer {
    fun renderPerformer(query: QueryModel, moduleName: String?): String =
        QueryPerformerSource(query, moduleName).render()

    fun renderDescriptor(query: QueryModel): String {
        val parameters = renderParameters(query.parameters)
        val location = renderLocation(query.declaringTypeName)
        val explicitPath = quoteOrNull(query.explicitPath)

        return """io.cratis.arc.metadata.QueryDescriptor(
        name = ${quote(query.methodName)},
        declaringTypeName = ${quote(query.declaringTypeName)},
        returnTypeName = ${quote(query.returnTypeName)},
        parameters = $parameters,
        routeOptions = io.cratis.arc.metadata.RouteOptions(
            path = $explicitPath,
            transport = io.cratis.arc.queries.QueryTransportType.${query.transport}
        ),
        fullyQualifiedName = ${quote(query.fullyQualifiedName)},
        location = listOf($location),
        authorization = ${renderAuthorization(query.authorization)},
        explicitPath = $explicitPath,
        queryHttpMethod = io.cratis.arc.queries.QueryHttpMethodType.${query.queryHttpMethod},
        transport = io.cratis.arc.queries.QueryTransportType.${query.transport},
        isEnumerable = ${query.isEnumerable},
        supportsPaging = ${query.supportsPaging},
        supportsSorting = ${query.supportsSorting},
        treatWarningsAsErrors = ${query.treatWarningsAsErrors},
        summary = ${quoteOrNull(query.summary)}
    )"""
    }

    private fun renderParameters(parameters: List<QueryParameterModel>): String {
        if (parameters.isEmpty()) return "emptyList()"
        return parameters.joinToString(",\n", "listOf(\n", "\n        )") { parameter ->
            "            io.cratis.arc.metadata.ParameterDescriptor(" +
                "name = ${quote(parameter.name)}, shape = ${ArcDescriptorRenderer.renderTypeShape(parameter.shape)}, " +
                "source = io.cratis.arc.metadata.QueryParameterSource.${parameter.source.name}, " +
                "hasDefault = ${parameter.hasDefault}, " +
                "validationRules = ${ArcDescriptorRenderer.renderValidationRules(parameter.validationRules)}, " +
                "validateRecursively = ${parameter.validateRecursively}, " +
                "summary = ${quoteOrNull(parameter.summary)})"
        }
    }
}

/** Builds the source of one generated query performer. */
private class QueryPerformerSource(private val query: QueryModel, private val moduleName: String?) {
    private val declaringType = renderQualifiedName(query.declaringTypeName)
    private val methodName = renderQualifiedName(query.methodName)
    private val defaultParameterIndexes = query.parameters.mapIndexedNotNull { index, parameter ->
        index.takeIf { parameter.hasDefault }
    }
    private val defaultBits = defaultParameterIndexes.withIndex().associate { (bit, parameterIndex) ->
        parameterIndex to (1 shl bit)
    }

    fun render(): String {
        val performerBindings = listOf(renderArgumentBindings(), renderHostAdapterBindings())
            .filter(String::isNotBlank)
            .joinToString("\n")
        val invocationBody = renderInvocationBody()
        val rxJava3Import = if (query.invocationKind == QueryInvocationKind.RX_OBSERVABLE) {
            "\nimport io.cratis.arc.rxjava3.asKotlinFlow"
        } else {
            ""
        }
        return """// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package $GENERATED_QUERIES_PACKAGE

import io.cratis.arc.commands.await
import io.cratis.arc.commands.require
import io.cratis.arc.queries.asKotlinFlow${rxJava3Import}

/** Generated reflection-free query performer for [${query.fullyQualifiedName}]. */
public class ${query.performerClassName} : io.cratis.arc.queries.QueryPerformer {
    override val fullyQualifiedName: io.cratis.arc.queries.FullyQualifiedQueryName =
        io.cratis.arc.queries.FullyQualifiedQueryName(${quote(query.fullyQualifiedName)})

    override val descriptor: io.cratis.arc.metadata.QueryDescriptor =
        $GENERATED_PACKAGE.${moduleName}ArcArtifactMetadata.create${query.performerClassName}()

    @Suppress("UNCHECKED_CAST")
    override suspend fun perform(context: io.cratis.arc.queries.QueryContext): kotlin.Any? {
$performerBindings
        $invocationBody
    }
}
"""
    }

    private fun renderArgumentBindings(): String = query.parameters
        .mapIndexedNotNull { index, parameter -> renderArgumentBinding(index, parameter) }
        .joinToString("\n")

    private fun renderArgumentBinding(index: Int, parameter: QueryParameterModel): String? {
        if (parameter.source != QueryParameterSource.CLIENT) return null
        val resolution = renderArgumentResolution(index, parameter)
        return if (parameter.hasDefault) {
            """        fun _resolveArgument$index(): ${parameter.renderedTypeName} = run {
        $resolution
        }"""
        } else {
            """        val _argument$index: ${parameter.renderedTypeName} = run {
        $resolution
        }"""
        }
    }

    private fun renderArgumentResolution(index: Int, parameter: QueryParameterModel): String {
        val castValue = renderCastValue(index, parameter)
        return if (parameter.isNullable) {
            """val _argument${index}Value = io.cratis.arc.queries.QueryArgumentResolver.nullable(
            context.request.arguments,
            ${quote(parameter.name)}
        )
        if (_argument${index}Value == null) {
            null
        } else {
            $castValue
        }"""
        } else {
            """val _argument${index}Value = io.cratis.arc.queries.QueryArgumentResolver.required(
            context.request.arguments,
            ${quote(parameter.name)}
        )
        $castValue"""
        }
    }

    private fun renderCastValue(index: Int, parameter: QueryParameterModel): String {
        // Concrete object arrays have a reifiable JVM component type; a raw Kotlin Array
        // is not compilable and Array<*> would discard the generated component check.
        val checkedType = if (parameter.erasedTypeName == "kotlin.Array") {
            parameter.renderedTypeName.removeSuffix("?")
        } else {
            parameter.erasedTypeName
        }
        val requiresGenericCast = parameter.renderedTypeName.removeSuffix("?") != checkedType
        val checkedValue = """(_argument${index}Value as? $checkedType
                ?: io.cratis.arc.queries.QueryArgumentResolver.wrongType(
                    ${quote(parameter.name)},
                    ${quote(parameter.typeName)}
                ))"""
        return if (requiresGenericCast) "$checkedValue as ${parameter.renderedTypeName}" else checkedValue
    }

    private fun renderHostAdapterBindings(): String = query.parameters.mapIndexedNotNull { index, parameter ->
        when (parameter.hostAdapterKind) {
            QueryHostAdapterKind.PAGEABLE -> renderPageableBinding(index)
            QueryHostAdapterKind.SORT -> renderSortBinding(index)
            null -> null
        }
    }.joinToString("\n")

    private fun renderPageableBinding(index: Int): String = """        val _hostAdapter$index: org.springframework.data.domain.Pageable = run {
            val _sorting$index = if (context.request.sorting.field.isBlank()) {
                org.springframework.data.domain.Sort.unsorted()
            } else {
                org.springframework.data.domain.Sort.by(
                    if (context.request.sorting.direction == io.cratis.arc.queries.QuerySortDirection.DESCENDING) {
                        org.springframework.data.domain.Sort.Direction.DESC
                    } else {
                        org.springframework.data.domain.Sort.Direction.ASC
                    },
                    context.request.sorting.field
                )
            }
            if (context.request.paging.pageSize == 0) {
                org.springframework.data.domain.Pageable.unpaged(_sorting$index)
            } else {
                org.springframework.data.domain.PageRequest.of(
                    context.request.paging.page,
                    context.request.paging.pageSize,
                    _sorting$index
                )
            }
        }"""

    private fun renderSortBinding(index: Int): String = """        val _hostAdapter$index: org.springframework.data.domain.Sort =
            if (context.request.sorting.field.isBlank()) {
                org.springframework.data.domain.Sort.unsorted()
            } else {
                org.springframework.data.domain.Sort.by(
                    if (context.request.sorting.direction == io.cratis.arc.queries.QuerySortDirection.DESCENDING) {
                        org.springframework.data.domain.Sort.Direction.DESC
                    } else {
                        org.springframework.data.domain.Sort.Direction.ASC
                    },
                    context.request.sorting.field
                )
            }"""

    private fun argumentExpression(index: Int, parameter: QueryParameterModel): String = when (parameter.source) {
        QueryParameterSource.CLIENT -> if (parameter.hasDefault) "_resolveArgument$index()" else "_argument$index"
        QueryParameterSource.SERVICE ->
            "context.serviceResolver.require(${parameter.erasedTypeName}::class.java)"
        QueryParameterSource.QUERY_REQUEST -> "context.request"
        QueryParameterSource.QUERY_CONTEXT -> "context"
        QueryParameterSource.HOST_ADAPTER -> {
            requireNotNull(parameter.hostAdapterKind)
            "_hostAdapter$index"
        }
    }

    private fun adaptInvocation(invocation: String): String {
        val adaptedInvocation = when (query.invocationKind) {
            QueryInvocationKind.DIRECT, QueryInvocationKind.FLOW -> invocation
            QueryInvocationKind.COMPLETION_STAGE -> "$invocation.await()"
            QueryInvocationKind.JDK_PUBLISHER -> "$invocation.asKotlinFlow()"
            QueryInvocationKind.RX_OBSERVABLE -> "$invocation.asKotlinFlow()"
        }
        return if (query.adaptsSpringDataPage) {
            """($adaptedInvocation).let { _page ->
            io.cratis.arc.queries.QueryPage(
                _page.content,
                if (_page.pageable.isPaged) _page.number else 0,
                if (_page.pageable.isPaged) _page.size else 0,
                _page.totalElements
            )
        }"""
        } else {
            adaptedInvocation
        }
    }

    private fun positionalInvocation(): String {
        val arguments = query.parameters.mapIndexed { index, parameter ->
            "            ${argumentExpression(index, parameter)}"
        }.joinToString(",\n")
        return if (arguments.isEmpty()) {
            "$declaringType.$methodName()"
        } else {
            "$declaringType.$methodName(\n$arguments\n        )"
        }
    }

    private fun namedInvocation(mask: Int): String {
        val arguments = query.parameters.mapIndexedNotNull { index, parameter ->
            val defaultBit = defaultBits[index]
            if (defaultBit != null && mask and defaultBit == 0) return@mapIndexedNotNull null
            "                ${renderQualifiedName(parameter.name)} = ${argumentExpression(index, parameter)}"
        }.joinToString(",\n")
        val invocation = if (arguments.isEmpty()) {
            "$declaringType.$methodName()"
        } else {
            "$declaringType.$methodName(\n$arguments\n            )"
        }
        return adaptInvocation(invocation)
    }

    private fun renderInvocationBody(): String {
        if (defaultParameterIndexes.isEmpty()) return "return ${adaptInvocation(positionalInvocation())}"
        val maskExpression = defaultParameterIndexes.mapIndexed { bit, parameterIndex ->
            val parameter = query.parameters[parameterIndex]
            "            (if (context.request.arguments.containsKey(${quote(parameter.name)})) ${1 shl bit} else 0)"
        }.joinToString(" or\n")
        val branches = (0 until (1 shl defaultParameterIndexes.size)).joinToString("\n") { mask ->
            "            $mask -> ${namedInvocation(mask)}"
        }
        return """val _defaultArgumentMask =
$maskExpression
        return when (_defaultArgumentMask) {
$branches
            else -> error("Unreachable default query argument mask")
        }"""
    }
}
