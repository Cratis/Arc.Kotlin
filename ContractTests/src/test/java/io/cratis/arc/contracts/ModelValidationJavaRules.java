// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts;

import io.cratis.arc.java.AsyncModelValidator;
import io.cratis.arc.java.AsyncModelValidatorAdapter;
import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.validation.ModelValidationContext;
import io.cratis.arc.validation.ModelValidator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** An ordinary Java application contributes the adapter as the one ModelValidator SPI. */
@Configuration(proxyBeanMethods = false)
public class ModelValidationJavaRules {
    @Bean
    public ModelValidator<String> javaModelRule() {
        return new AsyncModelValidatorAdapter<>(new AsyncModelValidator<String>() {
            @Override public Class<String> getModelType() { return String.class; }
            @Override public CompletionStage<List<ValidationResult>> validate(String value, ModelValidationContext context) {
                return CompletableFuture.completedFuture(value.equals("invalid-java")
                    ? List.of(ValidationResult.error("Java model rejected")) : List.of());
            }
        });
    }
}
