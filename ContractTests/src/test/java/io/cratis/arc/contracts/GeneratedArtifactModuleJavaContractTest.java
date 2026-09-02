// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts;

import io.cratis.arc.artifacts.ArcArtifactModule;
import io.cratis.arc.contracts.fixtures.JavaCustomerCode;
import io.cratis.arc.contracts.fixtures.JavaDeliveryDate;
import io.cratis.arc.contracts.fixtures.JavaDeliveryTime;
import io.cratis.arc.contracts.fixtures.JavaFixtureState;
import io.cratis.arc.contracts.fixtures.JavaOrderId;
import io.cratis.arc.contracts.fixtures.JavaQuantity;
import io.cratis.arc.contracts.fixtures.JavaStateCode;
import io.cratis.arc.generated.ContractTestsArcArtifactModule;
import io.cratis.arc.metadata.CommandDescriptor;
import io.cratis.arc.metadata.CommandResponseValueDisposition;
import io.cratis.arc.metadata.ConceptDescriptor;
import io.cratis.arc.metadata.PropertyDescriptor;
import io.cratis.arc.metadata.ValidationRuleDescriptor;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ServiceLoader;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Java consumer contract for the generated artifact module. */
final class GeneratedArtifactModuleJavaContractTest {
    @Test
    void generatedModuleIsAJavaFriendlyServiceProvider() {
        ArcArtifactModule explicit = new ContractTestsArcArtifactModule();
        long discovered = ServiceLoader.load(ArcArtifactModule.class).stream().count();

        assertEquals(25, explicit.getCommandHandlers().size());
        assertEquals(23, explicit.getQueryPerformers().size());
        assertEquals(1L, discovered);
    }

    @Test
    void javaRecordsImplementGenericConceptsWithoutBoilerplate() {
        UUID identifier = UUID.randomUUID();
        LocalDate date = LocalDate.of(2025, 1, 2);
        LocalTime time = LocalTime.of(3, 4, 5);

        assertEquals("CODE", new JavaCustomerCode("CODE").value());
        assertEquals(42, new JavaQuantity(42).value());
        assertEquals(identifier, new JavaOrderId(identifier).value());
        assertEquals(date, new JavaDeliveryDate(date).value());
        assertEquals(time, new JavaDeliveryTime(time).value());
        assertEquals(JavaFixtureState.READY, new JavaStateCode(JavaFixtureState.READY).value());

        ArcArtifactModule module = new ContractTestsArcArtifactModule();
        ConceptDescriptor descriptor = module.getConcepts().stream()
            .filter(concept -> concept.getName().equals("JavaCustomerCode"))
            .findFirst()
            .orElseThrow();
        assertEquals("java.lang.String", descriptor.getUnderlyingTypeName());
        assertThrows(UnsupportedOperationException.class, () -> module.getConcepts().add(descriptor));
    }

    @Test
    void aggregateResponseMetadataIsJavaFriendlyAndOrdered() {
        ArcArtifactModule module = new ContractTestsArcArtifactModule();
        CommandDescriptor command = module.getCommandHandlers().stream()
            .map(handler -> handler.getMetadata())
            .filter(metadata -> metadata.getName().equals("JavaPairResponseCommand"))
            .findFirst()
            .orElseThrow();

        assertEquals(2, command.getResponseValues().size());
        assertEquals(
            "io.cratis.arc.contracts.fixtures.JavaAggregateClientResponse",
            command.getResponseValues().get(0).getTypeName()
        );
        assertEquals(CommandResponseValueDisposition.CLIENT, command.getResponseValues().get(0).getDisposition());
        assertEquals(
            "io.cratis.arc.contracts.fixtures.HandledResponse",
            command.getResponseValues().get(1).getTypeName()
        );
        assertEquals(CommandResponseValueDisposition.HANDLED, command.getResponseValues().get(1).getDisposition());
        assertEquals("io.cratis.arc.contracts.fixtures.JavaAggregateClientResponse", command.getResponseTypeName());
    }

    @Test
    void generatedValidationMetadataIsJavaFriendlyAndImmutable() {
        ArcArtifactModule module = new ContractTestsArcArtifactModule();
        CommandDescriptor command = module.getCommandHandlers().stream()
            .map(handler -> handler.getMetadata())
            .filter(metadata -> metadata.getName().equals("JavaAsyncCommand"))
            .findFirst()
            .orElseThrow();
        PropertyDescriptor value = command.getProperties().stream()
            .filter(property -> property.getName().equals("value"))
            .findFirst()
            .orElseThrow();
        ValidationRuleDescriptor length = value.getValidationRules().get(0);

        assertEquals("length", length.getRuleName());
        assertEquals(java.util.List.of(2, 12), length.getArguments());
        assertEquals("Java value length is invalid", length.getMessage());
        assertThrows(UnsupportedOperationException.class, () -> command.getProperties().add(value));
        assertThrows(UnsupportedOperationException.class, () -> value.getValidationRules().add(length));
        assertEquals(
            command.getProperties(),
            module.getTypes().stream()
                .filter(type -> type.getName().equals("JavaAsyncCommand"))
                .findFirst()
                .orElseThrow()
                .getProperties()
        );
    }
}
