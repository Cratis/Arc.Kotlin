// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProxyMappingsJavaConformanceTest {
    @TempDir Path directory;

    @Test
    void javaOptionsAndStandaloneTaskApplyMappings() throws Exception {
        Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
        ArcProxyOptions options = project.getObjects().newInstance(ArcProxyOptions.class);
        assertEquals(List.of(), options.getTypeMappings().get());
        assertEquals(Map.of(), options.getPackageMappings().get());
        options.mapType("java.time.Duration", "string");
        options.mapType("shared.Money", "Money", "@arc-test/models");
        options.mapPackage("shared", "@arc-test/models");
        assertEquals(List.of("java.time.Duration=string", "shared.Money=Money=@arc-test/models"), options.getTypeMappings().get());
        GenerateArcProxies task = project.getTasks().register("mapped", GenerateArcProxies.class).get();
        task.getTypeMappings().set(options.getTypeMappings());
        task.getPackageMappings().set(options.getPackageMappings());
        task.getGenerationEnabled().set(true);
        task.getModuleName().set("Fixture");
        task.getRoutePrefix().set("api");
        task.getRouteSegmentsToSkip().set(0);
        task.getProxySegmentsToSkip().set(1);
        task.getIncludeCommandNames().set(true);
        task.getIncludeQueryNames().set(true);
        task.getEnableQueryHttpMethod().set(true);
        task.getRemoveStaleGeneratedFiles().set(true);
        task.getOutputDirectory().set(directory.resolve("generated").toFile());
        Path resources = directory.resolve("resources");
        Path manifest = resources.resolve("META-INF/cratis/arc/Fixture.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, """
            {"formatVersion":8,"moduleName":"Fixture","commands":[],"queries":[],"interfaces":[],"enums":[],"concepts":[],
             "types":[{"name":"View","fullyQualifiedName":"app.View","location":["app"],
              "properties":[{"name":"elapsed","ignoreValidation":false,"shape":{"kind":"VALUE","typeName":"java.time.Duration","nullable":false}}]}]}
            """);
        task.getManifestClasspath().from(resources.toFile());
        task.generate();
        String output = Files.readString(directory.resolve("generated/View.ts"));
        assertTrue(output.contains("@field(String)"), output);
        assertTrue(output.contains("elapsed!: string;"), output);
    }
}
