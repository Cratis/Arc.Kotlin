// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.json.ArcCamelCase
import io.cratis.arc.metadata.QueryDescriptor

/** Which generated query base class a hook set is rendered for. */
internal enum class QueryHookFlavor(val queryKind: String, val whenType: String, val resultPrefix: String) {
    QUERY("Query", "QueryWhen", "[result, perform"),
    OBSERVABLE("ObservableQuery", "ObservableQueryWhen", "[result")
}

private class QueryHookContext(
    val flavor: QueryHookFlavor,
    val query: QueryDescriptor,
    val className: String,
    val model: String,
    hasParameters: Boolean
) {
    private val argsType = if (hasParameters) "${className}Parameters" else null
    val argsDeclaration = argsType?.let { "args?: $it" }
    val argsValue = if (hasParameters) "args" else "undefined"
    val genericParameter = argsType?.let { ", $it" }.orEmpty()
    val performType = "PerformQuery${argsType?.let { "<$it>" }.orEmpty()}"
    val sortingArgument = "sorting?: Sorting".takeIf { query.supportsSorting }
    val sortingValue = if (query.supportsSorting) "sorting" else "undefined"
    val firstArguments = listOfNotNull(argsDeclaration, sortingArgument).joinToString(", ")
    val pagingArguments = (listOf("pageSize: number") + listOfNotNull(argsDeclaration, sortingArgument)).joinToString(", ")

    private val sortingType = listOfNotNull("SetSorting".takeIf { query.supportsSorting })
    val useTypes = listOf("QueryResultWithState<$model[]>") +
        listOfNotNull(performType.takeIf { flavor == QueryHookFlavor.QUERY }) + sortingType
    val pagingTypes = listOf("QueryResultWithState<$model[]>") +
        listOfNotNull("PerformQuery".takeIf { flavor == QueryHookFlavor.QUERY }) + sortingType +
        listOf("SetPage", "SetPageSize")

    fun hookName(suspense: Boolean, paging: Boolean) =
        (if (suspense) "useSuspense" else "use") + flavor.queryKind + if (paging) "WithPaging" else ""
}

internal fun StringBuilder.appendQueryHooks(
    query: QueryDescriptor,
    className: String,
    model: String,
    hasParameters: Boolean
) = appendHooks(QueryHookContext(QueryHookFlavor.QUERY, query, className, model, hasParameters), hasParameters)

internal fun StringBuilder.appendObservableQueryHooks(
    query: QueryDescriptor,
    className: String,
    model: String,
    hasParameters: Boolean
) = appendHooks(QueryHookContext(QueryHookFlavor.OBSERVABLE, query, className, model, hasParameters), hasParameters)

private fun StringBuilder.appendHooks(context: QueryHookContext, hasParameters: Boolean) {
    if (context.query.isEnumerable) {
        appendEnumerableHooks(context)
    } else {
        appendSingleHooks(context, hasParameters)
    }
}

private fun StringBuilder.appendEnumerableHooks(context: QueryHookContext) {
    appendUseHook(context, suspense = false)
    if (context.query.supportsPaging) appendPagingHook(context, suspense = false)
    appendUseHook(context, suspense = true)
    if (context.query.supportsPaging) appendPagingHook(context, suspense = true)
    if (context.flavor == QueryHookFlavor.OBSERVABLE) appendChangeStreamHook(context)
    val (className, model, generic) = Triple(context.className, context.model, context.genericParameter)
    val whenType = context.flavor.whenType
    append("\n    static when(condition: boolean): $whenType<$className, $model[]$generic> {\n")
    append("        return new $whenType<$className, $model[]$generic>($className, condition);\n    }\n")
}

private fun StringBuilder.appendUseHook(context: QueryHookContext, suspense: Boolean) {
    val name = if (suspense) "useSuspense" else "use"
    val hook = context.hookName(suspense, paging = false)
    val call = "$hook<${context.model}[], ${context.className}${context.genericParameter}>"
    append("\n    static $name(${context.firstArguments}): [${context.useTypes.joinToString(", ")}] {\n")
    if (context.query.supportsSorting) {
        append("        return $call(${context.className}, ${context.argsValue}, sorting);\n    }\n")
    } else {
        append("        const ${context.flavor.resultPrefix}] = $call(${context.className}, ${context.argsValue});\n")
        append("        return ${context.flavor.resultPrefix}];\n    }\n")
    }
}

private fun StringBuilder.appendPagingHook(context: QueryHookContext, suspense: Boolean) {
    val name = (if (suspense) "useSuspense" else "use") + "WithPaging"
    val hook = context.hookName(suspense, paging = true)
    val call = "$hook<${context.model}[], ${context.className}>" +
        "(${context.className}, new Paging(0, pageSize), ${context.argsValue}, ${context.sortingValue})"
    append("\n    static $name(${context.pagingArguments}): [${context.pagingTypes.joinToString(", ")}] {\n")
    if (context.query.supportsSorting) {
        append("        return $call;\n    }\n")
    } else {
        val prefix = context.flavor.resultPrefix
        append("        const $prefix, , setPage, setPageSize] = $call;\n")
        append("        return $prefix, setPage, setPageSize];\n    }\n")
    }
}

private fun StringBuilder.appendChangeStreamHook(context: QueryHookContext) {
    val changeStreamArguments = listOfNotNull(
        context.argsDeclaration,
        "getKey?: (item: ${context.model}) => unknown",
        context.sortingArgument
    ).joinToString(", ")
    append("\n    static useChangeStream($changeStreamArguments): ChangeSet<${context.model}> {\n")
    append("        return useChangeStream<${context.model}, ${context.className}${context.genericParameter}>(${context.className}, ${context.argsValue}, getKey, ${context.sortingValue});\n    }\n")
}

private fun StringBuilder.appendSingleHooks(context: QueryHookContext, hasParameters: Boolean) {
    val (className, model, generic) = Triple(context.className, context.model, context.genericParameter)
    val declaration = context.argsDeclaration.orEmpty()
    val argument = if (hasParameters) ", args" else ""
    val resultType = if (context.flavor == QueryHookFlavor.QUERY) {
        "[QueryResultWithState<$model>, ${context.performType}, SetSorting]"
    } else {
        "[QueryResultWithState<$model>]"
    }
    for (suspense in listOf(false, true)) {
        val name = if (suspense) "useSuspense" else "use"
        val call = "${context.hookName(suspense, paging = false)}<$model, $className$generic>($className$argument)"
        append("\n    static $name($declaration): $resultType {\n")
        if (context.flavor == QueryHookFlavor.QUERY) {
            append("        return $call;\n    }\n")
        } else {
            append("        const [result] = $call;\n")
            append("        return [result];\n    }\n")
        }
    }
    val whenType = context.flavor.whenType
    append("\n    static when(condition: boolean): $whenType<$className, $model$generic> {\n")
    append("        return new $whenType<$className, $model$generic>($className, condition);\n    }\n")
}

internal fun StringBuilder.appendObservableSortHelpers(
    className: String,
    model: String,
    properties: List<String>
) = appendSortHelpers(className, model, properties, "SortingActionsForObservableQuery")

internal fun StringBuilder.appendSortHelpers(
    className: String,
    model: String,
    properties: List<String>,
    actions: String = "SortingActionsForQuery"
) {
    // `_name` and `name` are both valid row fields. Private storage must not collide with either
    // public accessor, nor with another private slot (including collisions inherited from a base).
    val reserved = properties.map(::lowerCamel).toMutableSet()
    val storage = properties.associate { property ->
        val name = lowerCamel(property)
        var slot = "_$name"
        while (!reserved.add(slot)) slot = "_$slot"
        name to slot
    }
    append("\nclass ${className}SortBy {\n")
    storage.values.forEach { append("    private $it: $actions<$model[]>;\n") }
    append("\n    constructor(query: $className) {\n")
    storage.forEach { (name, slot) ->
        append("        this.$slot = new $actions<$model[]>('$name', query);\n")
    }
    append("    }\n")
    if (properties.isNotEmpty()) append("\n")
    storage.forEach { (name, slot) ->
        append("    get $name(): $actions<$model[]> {\n        return this.$slot;\n    }\n")
    }
    if (properties.isEmpty()) append("\n")
    append("}\n\nclass ${className}SortByWithoutQuery {\n")
    storage.forEach { (name, slot) ->
        append("    private $slot: SortingActions  = new SortingActions('$name');\n")
    }
    append("\n")
    storage.forEach { (name, slot) ->
        append("    get $name(): SortingActions {\n        return this.$slot;\n    }\n")
    }
    append("}\n")
    if (properties.isEmpty()) append("\n")
}

private fun lowerCamel(value: String): String = requireNotNull(ArcCamelCase.convert(value))
