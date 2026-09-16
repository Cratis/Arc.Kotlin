// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.validation.IgnoreValidation;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.validator.group.GroupSequenceProvider;
import org.hibernate.validator.spi.group.DefaultGroupSequenceProvider;

@IgnoreValidationHttpInput.Owner
@GroupSequenceProvider(IgnoreValidationHttpInput.Groups.class)
public final class IgnoreValidationHttpInput {
    public static final AtomicInteger READS = new AtomicInteger();
    public static final AtomicInteger OWNER_CALLS = new AtomicInteger();
    public static final AtomicInteger GROUP_CALLS = new AtomicInteger();
    @NotBlank public String sibling;
    @IgnoreValidation @NotBlank @Valid public String getIgnored() {
        READS.incrementAndGet();
        throw new AssertionError("Ignored Java HTTP getter read");
    }

    @Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME) @Constraint(validatedBy = OwnerRule.class)
    public @interface Owner {
        String message() default "whole owner remains active";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }
    public static final class OwnerRule implements ConstraintValidator<Owner, IgnoreValidationHttpInput> {
        public boolean isValid(IgnoreValidationHttpInput value, ConstraintValidatorContext context) {
            OWNER_CALLS.incrementAndGet();
            return !"owner".equals(value.sibling);
        }
    }
    public static final class Groups implements DefaultGroupSequenceProvider<IgnoreValidationHttpInput> {
        @Override public List<Class<?>> getValidationGroups(Class<?> owner, IgnoreValidationHttpInput value) {
            GROUP_CALLS.incrementAndGet();
            return List.of(IgnoreValidationHttpInput.class);
        }
    }
}
