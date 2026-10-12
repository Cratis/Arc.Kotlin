// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.CommandEventMetadata
import io.cratis.arc.metadata.CommandResponseValueDescriptor
import io.cratis.arc.metadata.ConceptDescriptor
import io.cratis.arc.metadata.EnumDescriptor
import io.cratis.arc.metadata.EnumMemberDescriptor
import io.cratis.arc.metadata.InterfaceDescriptor
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.RouteOptions
import io.cratis.arc.metadata.TypeDescriptor
import io.cratis.arc.metadata.ValidationRuleDescriptor
import io.cratis.arc.queries.QueryHttpMethodType
import io.cratis.arc.queries.QueryTransportType

/** The collected artifacts of one compilation that are published in its manifest. */
internal class ManifestContent(
    val commands: List<CommandModel>,
    val queries: List<QueryModel>,
    val types: List<TypeModel>,
    val interfaces: List<InterfaceModel>,
    val enums: List<EnumModel>,
    val concepts: List<ConceptModel>
)

/** Converts collected artifact models into the manifest published for a module. */
internal object ArcManifestBuilder {
    fun build(moduleName: String, content: ManifestContent): ArcArtifactManifest = ArcArtifactManifest(
        moduleName = moduleName,
        commands = content.commands.map(::toCommandDescriptor),
        queries = content.queries.map(::toQueryDescriptor),
        types = content.types.map(::toTypeDescriptor),
        enums = content.enums.map(::toEnumDescriptor),
        interfaces = content.interfaces.map(::toInterfaceDescriptor),
        concepts = content.concepts.map(::toConceptDescriptor)
    )

    private fun toTypeDescriptor(type: TypeModel): TypeDescriptor = TypeDescriptor(
        type.name,
        type.fullyQualifiedName,
        type.location,
        type.properties.map(::toPropertyDescriptor),
        type.baseTypeName,
        type.derivedTypeId,
        type.summary
    )

    private fun toEnumDescriptor(enum: EnumModel): EnumDescriptor = EnumDescriptor(
        enum.name,
        enum.fullyQualifiedName,
        enum.location,
        enum.members.map { member -> EnumMemberDescriptor(member.name, member.value) },
        enum.isFlags,
        enum.summary
    )

    private fun toInterfaceDescriptor(interfaceModel: InterfaceModel): InterfaceDescriptor = InterfaceDescriptor(
        interfaceModel.name,
        interfaceModel.fullyQualifiedName,
        interfaceModel.location,
        interfaceModel.properties.map(::toPropertyDescriptor),
        interfaceModel.summary
    )

    private fun toConceptDescriptor(concept: ConceptModel): ConceptDescriptor =
        ConceptDescriptor(concept.name, concept.fullyQualifiedName, concept.underlyingTypeName)

    private fun toCommandDescriptor(command: CommandModel): CommandDescriptor = CommandDescriptor(
        name = command.simpleName,
        typeName = command.qualifiedName,
        properties = command.properties.map(::toPropertyDescriptor),
        routeOptions = RouteOptions(),
        location = locationOf(command.qualifiedName),
        authorization = command.authorization.toDescriptor(),
        explicitPath = null,
        treatWarningsAsErrors = command.treatWarningsAsErrors,
        responseTypeName = command.responseTypeName,
        responseIsEnumerable = command.responseIsEnumerable,
        responseValues = command.responseValues.map { value ->
            CommandResponseValueDescriptor(value.typeName, value.isEnumerable, value.disposition)
        },
        summary = command.summary,
        eventMetadata = command.eventMetadata?.toDescriptor()
    )

    private fun CommandEventMetadataModel.toDescriptor(): CommandEventMetadata = CommandEventMetadata(
        eventSourceType,
        eventStreamType,
        eventStreamId,
        subject
    )

    private fun toQueryDescriptor(query: QueryModel): QueryDescriptor = QueryDescriptor(
        name = query.methodName,
        declaringTypeName = query.declaringTypeName,
        returnTypeName = query.returnTypeName,
        parameters = query.parameters.map(::toParameterDescriptor),
        routeOptions = RouteOptions(query.explicitPath, QueryTransportType.valueOf(query.transport)),
        fullyQualifiedName = query.fullyQualifiedName,
        location = locationOf(query.declaringTypeName),
        authorization = query.authorization.toDescriptor(),
        explicitPath = query.explicitPath,
        queryHttpMethod = QueryHttpMethodType.valueOf(query.queryHttpMethod),
        transport = QueryTransportType.valueOf(query.transport),
        isEnumerable = query.isEnumerable,
        supportsPaging = query.supportsPaging,
        supportsSorting = query.supportsSorting,
        treatWarningsAsErrors = query.treatWarningsAsErrors,
        summary = query.summary
    )

    private fun toParameterDescriptor(parameter: QueryParameterModel): ParameterDescriptor = ParameterDescriptor(
        parameter.name,
        parameter.shape,
        parameter.source,
        parameter.hasDefault,
        parameter.validationRules.map(::toValidationRuleDescriptor),
        parameter.validateRecursively,
        parameter.summary
    )

    private fun toPropertyDescriptor(property: PropertyModel): PropertyDescriptor = PropertyDescriptor(
        property.name,
        property.shape,
        property.isCommandKey,
        property.validationRules.map(::toValidationRuleDescriptor),
        property.validateRecursively,
        property.derivatives,
        property.summary,
        property.ignoreValidation
    )

    private fun toValidationRuleDescriptor(rule: ValidationRuleModel): ValidationRuleDescriptor =
        ValidationRuleDescriptor(rule.ruleName, rule.arguments, rule.message)

    private fun AuthorizationModel.toDescriptor(): AuthorizationMetadata =
        AuthorizationMetadata(allowAnonymous, policy, roles, schemes)

    private fun locationOf(qualifiedName: String): List<String> =
        qualifiedName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank)
}
