// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode

internal enum class ArcDiagnostic(
    val code: String,
    val severity: String,
    val title: String
) {
    CONFIGURATION("ARCKSP0001", "Error", "Invalid KSP configuration"),
    MISSING_COMMAND("ARCKSP0100", "Warning", "Command-like type is missing @Command"),
    COMMAND_SHAPE("ARCKSP0101", "Error", "Unsupported command declaration"),
    COMMAND_HANDLER("ARCKSP0102", "Error", "Invalid command handle function"),
    COMMAND_PROVIDE("ARCKSP0103", "Error", "Invalid command provide function"),
    COMMAND_PARAMETER("ARCKSP0104", "Error", "Unsupported command method parameter"),
    COMMAND_RESPONSE("ARCKSP0105", "Error", "Unsupported command method return type"),
    COMMAND_KEY("ARCKSP0106", "Error", "Ambiguous command key"),
    UNUSED_PROVIDED_VALUE("ARCKSP0107", "Warning", "Provided value is not consumed by handle"),
    AUTHORIZATION("ARCKSP0108", "Error", "Conflicting authorization metadata"),
    AMBIGUOUS_COMMAND_RESPONSE("ARCKSP0109", "Error", "Ambiguous command response values"),
    READ_MODEL_SHAPE("ARCKSP0200", "Error", "Unsupported read model declaration"),
    QUERY_DECLARATION("ARCKSP0201", "Error", "Invalid query function"),
    QUERY_OVERLOAD("ARCKSP0202", "Error", "Ambiguous query overload"),
    QUERY_PARAMETER("ARCKSP0203", "Error", "Unsupported query parameter"),
    QUERY_RETURN("ARCKSP0204", "Error", "Unsupported query return type"),
    OBSERVABLE_MISMATCH("ARCKSP0205", "Error", "Query transport and return type disagree"),
    ROUTE("ARCKSP0206", "Error", "Ambiguous or duplicate query route"),
    DUPLICATE_QUERY("ARCKSP0207", "Error", "Duplicate fully qualified query name"),
    QUERY_INFRASTRUCTURE_PARAMETER("ARCKSP0208", "Error", "Invalid query infrastructure parameter"),
    QUERY_DEFAULT("ARCKSP0209", "Error", "Unsupported Kotlin query parameter default"),
    HOST_ADAPTER_PARAMETER("ARCKSP0210", "Error", "Invalid host query adapter shape"),
    PROXY_SHAPE("ARCKSP0300", "Error", "Unsupported generated proxy model shape"),
    VALIDATION("ARCKSP0301", "Error", "Invalid or unrepresentable Jakarta validation metadata"),
    ENUM_VALUE("ARCKSP0302", "Error", "Ambiguous or unprovable Arc enum wire value"),
    INTEROP("ARCKSP0400", "Warning", "Java/Kotlin interoperability hazard"),
    INTERNAL("ARCKSP9999", "Error", "Unclassified Arc KSP diagnostic");

    companion object {
        fun referenceMarkdown(): String = buildString {
            appendLine("# Arc KSP diagnostics")
            appendLine()
            appendLine("Arc KSP prefixes every compile-time diagnostic with a stable code. Errors stop code generation; warnings identify risky but compilable conventions.")
            appendLine()
            appendLine("| Code | Severity | Description |")
            appendLine("| --- | --- | --- |")
            entries.forEach { diagnostic ->
                appendLine("| `${diagnostic.code}` | ${diagnostic.severity} | ${diagnostic.title} |")
            }
        }
    }
}

internal class ArcDiagnosticReporter(private val logger: KSPLogger) {
    fun error(message: String, node: KSNode? = null) {
        error(classify(message), message, node)
    }

    fun error(diagnostic: ArcDiagnostic, message: String, node: KSNode? = null) {
        logger.error("[${diagnostic.code}] $message", node)
    }

    fun warn(message: String, node: KSNode? = null) {
        warn(classify(message), message, node)
    }

    fun warn(diagnostic: ArcDiagnostic, message: String, node: KSNode? = null) {
        logger.warn("[${diagnostic.code}] $message", node)
    }

    private fun classify(message: String): ArcDiagnostic = when {
        message.startsWith("KSP option") -> ArcDiagnostic.CONFIGURATION
        "missing @Command" in message || message.startsWith("Type '") && "has a handle function" in message ->
            ArcDiagnostic.MISSING_COMMAND
        message.startsWith("Command '") && ("top-level" in message || "concrete class" in message ||
            "must be public" in message || "must not be abstract" in message || "type parameters" in message) ->
            ArcDiagnostic.COMMAND_SHAPE
        message.startsWith("Handler '") || "instance function named 'handle'" in message ||
            "overloaded 'handle'" in message || message.startsWith("External handler") -> ArcDiagnostic.COMMAND_HANDLER
        message.startsWith("Provide method") || "overloaded 'provide'" in message -> ArcDiagnostic.COMMAND_PROVIDE
        message.startsWith("Command method") || message.contains("response type") ||
            message.contains("response element") || message.contains("must return CommandResult") ->
            ArcDiagnostic.COMMAND_RESPONSE
        message.startsWith("Parameter '") || message.startsWith("Java Optional parameter '") ->
            ArcDiagnostic.COMMAND_PARAMETER
        message.contains("@CommandKey") -> ArcDiagnostic.COMMAND_KEY
        message.contains("produced by") && message.contains("not consumed") -> ArcDiagnostic.UNUSED_PROVIDED_VALUE
        message.contains("@AllowAnonymous") || message.contains("authorization policies") -> ArcDiagnostic.AUTHORIZATION
        message.startsWith("Read model '") && !message.contains("overloaded query") -> ArcDiagnostic.READ_MODEL_SHAPE
        message.startsWith("Java query") || message.startsWith("Query '") &&
            (message.contains("must be public") || message.contains("must not be abstract") ||
                message.contains("extension function") || message.contains("type parameters") ||
                message.contains("companion object")) -> ArcDiagnostic.QUERY_DECLARATION
        message.contains("overloaded query") -> ArcDiagnostic.QUERY_OVERLOAD
        message.startsWith("Query parameter") || message.startsWith("Query service parameter") ||
            message.contains("unnamed parameter") -> ArcDiagnostic.QUERY_PARAMETER
        message.contains("observable transport") || message.contains("request-response transport") ->
            ArcDiagnostic.OBSERVABLE_MISMATCH
        message.contains("@Path") || message.contains("query route") -> ArcDiagnostic.ROUTE
        message.contains("fully qualified query name") -> ArcDiagnostic.DUPLICATE_QUERY
        message.contains("Kotlin query parameter default") -> ArcDiagnostic.QUERY_DEFAULT
        message.startsWith("Query '") || message.startsWith("Java query '") -> ArcDiagnostic.QUERY_RETURN
        message.startsWith("Enum '") || message.contains("default value") -> ArcDiagnostic.INTEROP
        message.startsWith("Validation annotation") || message.contains("contradictory numeric bounds") -> ArcDiagnostic.VALIDATION
        message.startsWith("'") || message.contains("model shape") || message.contains("map type") ||
            message.contains("star projection") || message.contains("polymorphic") -> ArcDiagnostic.PROXY_SHAPE
        else -> ArcDiagnostic.INTERNAL
    }
}
