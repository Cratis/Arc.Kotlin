// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import java.security.MessageDigest

/** Exact expected-side correction of three captured parameter-derived helper blocks; never reads actual output. */
internal object ExpectedSortHelperPreparation {
    val properties = listOf("identifier", "labelsByCategory", "permissions", "state", "updatedAt")
    private val capturedBlocks = mapOf(
        "Models/All.ts" to "394257BC325FC1D5C141721CEF8A96319C763D78688C44C09B972B127E4B1481",
        "Models/Search.ts" to "5788EEC0F8F7A9D2A8CFE0804A7B2177778300DB9D5EAA7D0A936ED2071AEBEB",
        "Models/Observe.ts" to "75FA78EF2986839CDA0D69DD1B80533E37EEBB1D687E88E2A6A7152A58B8087E"
    )

    fun prepare(path: String, text: String): String {
        val expectedHash = capturedBlocks[path] ?: return text
        val query = path.substringAfterLast('/').removeSuffix(".ts")
        val first = "class ${query}SortBy {"
        val following = if (query == "All") "export class All " else "export interface ${query}Parameters {"
        require(occurrences(text, first) == 1 && occurrences(text, following) == 1) {
            "Missing or duplicate captured sort-helper anchor in $path"
        }
        val start = text.indexOf(first)
        val end = text.indexOf(following)
        require(end > start) { "Reordered captured sort-helper anchors in $path" }
        val original = text.substring(start, end)
        val digest = MessageDigest.getInstance("SHA-256").digest(original.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
        require(digest == expectedHash) { "Changed captured sort-helper block in $path" }
        var result = text.substring(0, start) + helpers(query, query == "Observe") + text.substring(end)
        if (query == "All") {
            val oldImport = "import { QueryFor, QueryResultWithState, Sorting, Paging } from '@cratis/arc/queries';"
            require(occurrences(result, oldImport) == 1) { "Missing or duplicate captured All sorting import" }
            result = result.replace(oldImport,
                "import { QueryFor, QueryResultWithState, Sorting, SortingActions, SortingActionsForQuery, Paging } from '@cratis/arc/queries';")
        }
        return result
    }

    // Independent literal fixture contract. Field selection is deliberately NOT delegated to the production renderer.
    private fun helpers(query: String, observable: Boolean): String = buildString {
        val actions = if (observable) "SortingActionsForObservableQuery" else "SortingActionsForQuery"
        append("class ${query}SortBy {\n")
        properties.forEach { append("    private _$it: $actions<FixtureModel[]>;\n") }
        append("\n    constructor(query: $query) {\n")
        properties.forEach { append("        this._$it = new $actions<FixtureModel[]>('$it', query);\n") }
        append("    }\n\n")
        properties.forEach { append("    get $it(): $actions<FixtureModel[]> {\n        return this._$it;\n    }\n") }
        append("}\n\nclass ${query}SortByWithoutQuery {\n")
        properties.forEach { append("    private _$it: SortingActions  = new SortingActions('$it');\n") }
        append("\n")
        properties.forEach { append("    get $it(): SortingActions {\n        return this._$it;\n    }\n") }
        append("}\n\n")
    }

    private fun occurrences(text: String, fragment: String): Int = text.split(fragment).size - 1
}
