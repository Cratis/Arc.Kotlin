// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.concepts.ConceptAs;
import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.validation.ConceptValidator;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Java-authored record graphs and synchronous rule beans, shared by context and servlet checks. */
public final class ConceptValidationJavaFixture {
    private ConceptValidationJavaFixture() { }

    public record Code(String value) implements ConceptAs<String> { }
    public record Command(Code code, List<Code> items, Map<String, Code> named) { }

    public static final class Rule implements ConceptValidator<Code> {
        @Override
        public Class<Code> getConceptType() { return Code.class; }

        @Override
        public List<ValidationResult> validate(Code concept) {
            return concept.value().isBlank()
                ? List.of(ValidationResult.error("Java code is required", List.of("value")))
                : List.of();
        }
    }

    @Configuration(proxyBeanMethods = false)
    public static class Contributions {
        @Bean
        public ConceptValidator<Code> javaConceptRule() { return new Rule(); }
    }
}
