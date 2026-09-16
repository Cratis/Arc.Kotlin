// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.validation.IgnoreValidation;
import io.cratis.arc.validation.IgnoreValidationValidator;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.TraversableResolver;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.ValueExtractor;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IgnoreValidationProviderJavaTest {
    @Test
    void factoryAdapterStopsGetterAndExtractorBeforeAccessAndKeepsOwnerAndSiblingConstraints() {
        try (ValidatorFactory factory = Validation.byDefaultProvider().configure()
                .addValueExtractor(new BoxExtractor()).buildValidatorFactory()) {
            Validator validator = IgnoreValidationValidator.fromFactory(factory);
            Bean model = new Bean();
            BoxExtractor.reads.set(0);
            OwnerValidator.calls.set(0);
            assertEquals(Set.of("", "sibling"), validator.validate(model).stream()
                .map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet()));
            assertEquals(0, model.reads);
            assertEquals(0, BoxExtractor.reads.get());
            assertEquals(1, OwnerValidator.calls.get());
            assertEquals(1, validator.validate(new Child()).size());
        }
    }

    @Test
    void factoryAdapterPreservesApplicationResolverDecisions() {
        AtomicInteger calls = new AtomicInteger();
        TraversableResolver resolver = new TraversableResolver() {
            public boolean isReachable(Object object, jakarta.validation.Path.Node property, Class<?> root,
                    jakarta.validation.Path path, ElementType element) {
                calls.incrementAndGet();
                return !property.getName().equals("sibling");
            }
            public boolean isCascadable(Object object, jakarta.validation.Path.Node property, Class<?> root,
                    jakarta.validation.Path path, ElementType element) { return false; }
        };
        try (ValidatorFactory factory = Validation.byDefaultProvider().configure().traversableResolver(resolver)
                .addValueExtractor(new BoxExtractor()).buildValidatorFactory()) {
            assertEquals(Set.of(""), IgnoreValidationValidator.fromFactory(factory).validate(new Bean()).stream()
                .map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet()));
            assertTrue(calls.get() > 0);
            assertEquals(resolver, factory.getTraversableResolver());
            // Obtaining the Arc context has not replaced the application factory's resolver.
        }
    }

    @Test
    void executableParameterConstraintsRemainWhileCascadedMemberIsIgnored() throws ReflectiveOperationException {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var validator = IgnoreValidationValidator.fromFactory(factory);
            var query = new Query();
            var method = Query.class.getMethod("find", Input.class, String.class);
            var input = new Input();
            var violations = validator.forExecutables().validateParameters(query, method, new Object[] { input, "" });
            assertEquals(2, violations.size());
            assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().endsWith(".sibling")));
            assertTrue(violations.stream().anyMatch(v -> v.getMessage().equals("parameter stays active")));
            assertEquals(0, input.reads);
        }
    }

    @Test
    void recordHeaderAndExplicitAccessorAreOneEdge() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(IgnoreValidationValidator.fromFactory(factory).validate(new IgnoredRecord(null)).isEmpty());
        }
    }

    public static final class Child { @NotBlank public String name = ""; }
    public static final class Box<T> { }
    public static final class BoxExtractor implements ValueExtractor<Box<@ExtractedValue ?>> {
        static final AtomicInteger reads = new AtomicInteger();
        public void extractValues(Box<?> box, ValueReceiver receiver) {
            reads.incrementAndGet();
            throw new AssertionError("ignored extractor read");
        }
    }
    @Owner
    public static final class Bean {
        public int reads;
        @IgnoreValidation public Box<@NotNull @Valid Child> box = new Box<>();
        @NotBlank public String sibling = "";
        @IgnoreValidation @NotNull @Valid public Child getIgnored() { reads++; throw new AssertionError("ignored getter read"); }
    }
    public static final class Input {
        public int reads;
        @NotBlank public String sibling = "";
        @IgnoreValidation @Valid public Child getIgnored() { reads++; throw new AssertionError("ignored getter read"); }
    }
    public static final class Query {
        public String find(@Valid Input input, @NotBlank(message = "parameter stays active") String parameter) { return parameter; }
    }
    public record IgnoredRecord(@IgnoreValidation @NotNull String name) {
        @Override public String name() { throw new AssertionError("ignored record accessor read"); }
    }
    @Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME) @Constraint(validatedBy = OwnerValidator.class)
    public @interface Owner {
        String message() default "owner stays active";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }
    public static final class OwnerValidator implements ConstraintValidator<Owner, Bean> {
        static final AtomicInteger calls = new AtomicInteger();
        public boolean isValid(Bean value, ConstraintValidatorContext context) { calls.incrementAndGet(); return false; }
    }
}
