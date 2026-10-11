// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSClassDeclaration

/** Reads and validates the event metadata annotations declared on one command. */
internal class CommandEventMetadataReader(
    private val logger: ArcDiagnosticReporter,
    private val command: KSClassDeclaration,
    private val commandName: String
) {
    private var isValid = true

    fun read(): CommandEventMetadataModel? {
        rejectAnnotationWithProvider(
            COMMAND_EVENT_STREAM_ID_ANNOTATION,
            COMMAND_EVENT_STREAM_ID_PROVIDER,
            "Command '$commandName' cannot declare @CommandEventStreamId and implement " +
                "CommandEventStreamIdProvider; choose one event-stream ID source."
        )
        rejectAnnotationWithProvider(
            COMMAND_EVENT_SUBJECT_ANNOTATION,
            COMMAND_EVENT_SUBJECT_PROVIDER,
            "Command '$commandName' cannot declare @CommandEventSubject and implement " +
                "CommandEventSubjectProvider; choose one subject source."
        )
        val metadata = CommandEventMetadataModel(
            eventSourceType = annotationValue(COMMAND_EVENT_SOURCE_TYPE_ANNOTATION),
            eventStreamType = annotationValue(COMMAND_EVENT_STREAM_TYPE_ANNOTATION),
            eventStreamId = annotationValue(COMMAND_EVENT_STREAM_ID_ANNOTATION),
            subject = annotationValue(COMMAND_EVENT_SUBJECT_ANNOTATION),
            isValid = isValid
        )
        return metadata.takeIf { !it.isValid || it.declaresAnyValue() }
    }

    private fun CommandEventMetadataModel.declaresAnyValue(): Boolean =
        listOf(eventSourceType, eventStreamType, eventStreamId, subject).any { it != null }

    private fun annotationValue(annotationName: String): String? {
        val annotation = command.annotationsNamed(annotationName).singleOrNull() ?: return null
        val raw = annotation.stringArgument("value")
        if (raw.isNullOrBlank() || raw.any(Char::isISOControl)) {
            logger.error(
                ArcDiagnostic.COMMAND_EVENT_METADATA,
                "Command '$commandName' declares @${annotation.shortName.asString()} with a blank or " +
                    "control-character value; omit the annotation or supply a safe nonblank value.",
                annotation
            )
            isValid = false
            return null
        }
        return raw
    }

    private fun provides(interfaceName: String): Boolean = command.getAllSuperTypes().any { superType ->
        superType.declaration.qualifiedName?.asString() == interfaceName
    }

    private fun rejectAnnotationWithProvider(annotationName: String, providerName: String, message: String) {
        if (!command.hasAnnotation(annotationName) || !provides(providerName)) return
        logger.error(ArcDiagnostic.COMMAND_EVENT_METADATA, message, command)
        isValid = false
    }
}
