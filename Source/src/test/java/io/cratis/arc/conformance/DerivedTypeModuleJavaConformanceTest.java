// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import tools.jackson.databind.ObjectMapper;
import io.cratis.arc.artifacts.ArcArtifactModule;
import io.cratis.arc.artifacts.ArcArtifactModuleRegistry;
import io.cratis.arc.json.ArcObjectMapper;
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry;
import io.cratis.arc.polymorphism.DerivedType;
import io.cratis.arc.polymorphism.DerivedTypeRegistrar;
import io.cratis.arc.polymorphism.DerivedTypeRegistration;
import io.cratis.arc.polymorphism.DerivedTypeRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Java consumer contract for the derived types a generated artifact module contributes. */
final class DerivedTypeModuleJavaConformanceTest {
    @Test
    void aJavaModuleContributesRegistrationsThatPopulateARegistry() {
        DerivedTypeRegistry registry = new ConcurrentDerivedTypeRegistry();
        ArcArtifactModule module = new JavaDerivedTypeModule();

        ArcArtifactModuleRegistry.registerDerivedTypes(module, registry);

        DerivedTypeRegistration registration = module.getDerivedTypes().get(0);
        assertEquals(JavaHostedShape.class, registration.getBaseType());
        assertEquals(JavaHostedCircle.class, registration.getDerivedType());
        assertEquals(JavaHostedCircle.class, registry.resolve(JavaHostedShape.class, "java-circle"));
        assertEquals("java-circle", registry.idFor(JavaHostedShape.class, JavaHostedCircle.class));
    }

    @Test
    void aJavaRegistrarAddsAHierarchyTheModuleDidNotCarry() {
        DerivedTypeRegistry registry = new ConcurrentDerivedTypeRegistry();
        DerivedTypeRegistrar registrar = target -> target.register(JavaHostedShape.class, JavaHostedSquare.class);

        registrar.registerDerivedTypes(registry);

        assertEquals(JavaHostedSquare.class, registry.resolve(JavaHostedShape.class, "java-square"));
    }

    @Test
    void aPopulatedRegistryReadsAPolymorphicValueBackFromJava() throws Exception {
        DerivedTypeRegistry registry = new ConcurrentDerivedTypeRegistry();
        ArcArtifactModuleRegistry.registerDerivedTypes(new JavaDerivedTypeModule(), registry);
        ObjectMapper mapper = ArcObjectMapper.create(registry);

        String json = mapper.writeValueAsString(new JavaHostedCircle(2.0));

        assertEquals("{\"radius\":2.0,\"_derivedTypeId\":\"java-circle\"}", json);
        assertEquals(new JavaHostedCircle(2.0), mapper.readValue(json, JavaHostedShape.class));
    }

    /** Hand-written stand-in for the module Arc's code generation emits. */
    static final class JavaDerivedTypeModule extends ArcArtifactModule {
        JavaDerivedTypeModule() {
            super(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new DerivedTypeRegistration(JavaHostedShape.class, JavaHostedCircle.class)));
        }
    }

    /** Base contract a Java consumer declares a polymorphic property as. */
    public interface JavaHostedShape {
    }

    /** Java record derivative of {@link JavaHostedShape}. */
    @DerivedType(id = "java-circle")
    public record JavaHostedCircle(double radius) implements JavaHostedShape {
    }

    /** Second Java record derivative, contributed by a registrar rather than a module. */
    @DerivedType(id = "java-square")
    public record JavaHostedSquare(double side) implements JavaHostedShape {
    }
}
