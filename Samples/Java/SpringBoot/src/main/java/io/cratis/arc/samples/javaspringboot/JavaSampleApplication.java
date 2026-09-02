// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot;

import io.cratis.arc.commands.CommandValidator;
import io.cratis.arc.java.BlockingCommandValidatorAdapter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Runs the Java Spring Boot sample host. */
@SpringBootApplication
public class JavaSampleApplication {
    public static void main(String[] args) {
        SpringApplication.run(JavaSampleApplication.class, args);
    }

    /** Adapts the ordinary Java validator to Arc's host-neutral command validation contract. */
    @Bean
    public CommandValidator<CreateTask> createTaskValidator() {
        return new BlockingCommandValidatorAdapter<>(new CreateTaskValidator());
    }
}
