// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle.springboot;

import io.cratis.arc.naming.NamingPolicy;
import io.cratis.arc.springdata.mongodb.DefaultNamingPolicy;
import io.cratis.chronicle.readModels.ReadModelNamingPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("deprecation")
class ChronicleArcAutoConfigurationJavaConformanceTest {
    @Test
    void arcNamingPolicyIsAdaptedIntoAChronicleReadModelNamingPolicy() {
        NamingPolicy arcPolicy = new DefaultNamingPolicy();

        ReadModelNamingPolicy chroniclePolicy = new ChronicleArcAutoConfiguration()
            .arcReadModelNamingPolicy(arcPolicy);

        assertEquals("Authors", chroniclePolicy.getReadModelName(Author.class));
    }

    @Test
    void anApplicationCanDeclareItsOwnReadModelNamingPolicyAsALambda() {
        ReadModelNamingPolicy policy = readModelClass -> readModelClass.getSimpleName() + "Documents";

        assertEquals("AuthorDocuments", policy.getReadModelName(Author.class));
    }

    @Test
    void aFundamentalsNamingPolicyIsAdaptedIntoAChronicleReadModelNamingPolicy() {
        io.cratis.fundamentals.serialization.NamingPolicy policy =
            new io.cratis.fundamentals.serialization.DefaultNamingPolicy();

        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ChronicleArcAutoConfiguration.class))
            .withBean(io.cratis.fundamentals.serialization.NamingPolicy.class, () -> policy)
            .run(context -> assertEquals(
                "Authors",
                context.getBean(ReadModelNamingPolicy.class).getReadModelName(Author.class)));
    }

    private static final class Author { }
}
