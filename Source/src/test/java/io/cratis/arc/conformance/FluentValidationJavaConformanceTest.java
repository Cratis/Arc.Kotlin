// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.artifacts.ArcArtifactModule;
import io.cratis.arc.artifacts.ArcArtifactModuleRegistry;
import io.cratis.arc.metadata.ValidationRuleDescriptor;
import io.cratis.arc.validation.FluentModelValidator;
import io.cratis.arc.validation.FluentValidationMember;
import io.cratis.arc.validation.FluentValidatorRegistration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FluentValidationJavaConformanceTest {
    @Test
    void ordinaryJavaConstructorAuthorsAndExecutesWithoutContinuation() {
        var validator = new FluentPersonValidator();
        assertTrue(validator.validate(new FluentPerson("Ada", 30)).isEmpty());
        var result = validator.validate(new FluentPerson(null, 17));
        assertEquals(List.of("age", "name"), result.stream().map(item -> item.getMembers().get(0)).toList());
        assertEquals("name is too long", validator.validate(new FluentPerson("much too long", 30)).get(0).getMessage());
        assertEquals(FluentPerson.class, validator.getModelType());
        assertEquals(Integer.class, validator.getRules().get(0).getMemberType());
    }

    @Test
    void JavaModuleContributesTypedCompilerDescriptorsAndOldConstructorStillWorks() {
        var validator = new FluentPersonValidator();
        var expected = List.of(
            new FluentValidationMember("age", Integer.class, List.of(
                new ValidationRuleDescriptor("greaterThanOrEqual", List.of(18)),
                new ValidationRuleDescriptor("lessThan", List.of(150)))),
            new FluentValidationMember("name", String.class, List.of(
                new ValidationRuleDescriptor("notNull"),
                new ValidationRuleDescriptor("minLength", List.of(2)),
                new ValidationRuleDescriptor("maxLength", List.of(10), "{PropertyName} is too long")))
        );
        var registration = new FluentValidatorRegistration(validator, FluentPerson.class, expected);
        var module = new ArcArtifactModule(List.of(), List.of()) {
            @Override
            public List<FluentValidatorRegistration> getFluentValidators() { return List.of(registration); }
        };
        assertSame(validator, ArcArtifactModuleRegistry.modelValidators(List.of(module)).get(0));
        var bean = new FluentPersonValidator();
        assertSame(bean, ArcArtifactModuleRegistry.modelValidators(List.of(module), List.of(bean)).get(0));
        assertThrows(UnsupportedOperationException.class, () -> validator.getRules().clear());
    }

    public static final class Fields {
        public String name;
        public Fields(String value) { name = value; }
    }

    public static final class FieldsValidator extends FluentModelValidator<Fields> {
        public FieldsValidator() { super(Fields.class); ruleFor("name").notEmpty(); }
    }

    @Test
    void publicJavaFieldsAndNullValuesUseTheSameRules() {
        assertEquals(1, new FieldsValidator().validate(new Fields(null)).size());
        assertEquals(1, new FieldsValidator().validate(new Fields("\u00a0")).size());
        assertTrue(new FieldsValidator().validate(new Fields("ok")).isEmpty());
    }
}
