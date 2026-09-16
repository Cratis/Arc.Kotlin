// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class ResponseHandlerMetadataJavaConformanceTest {
    @TempDir Path directory;

    @Test
    void managedTaskAndProviderHaveUsableJavaAccessors() throws Exception {
        Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
        ExtractArcResponseHandlerMetadata task = project.getTasks().register("extract", ExtractArcResponseHandlerMetadata.class).get();
        Path output = directory.resolve("index with spaces.json");
        task.getDependencyArtifacts().setFrom(List.of());
        task.getOutputFile().set(output.toFile());
        assertSame(task.getDependencyArtifacts(), task.getDependencyArtifacts());
        task.extract();
        assertEquals("{\"formatVersion\":1,\"modules\":[]}\n", Files.readString(output, StandardCharsets.UTF_8));
        var timestamp = Files.getLastModifiedTime(output);
        task.extract();
        assertEquals(timestamp, Files.getLastModifiedTime(output));
        ArcResponseHandlerMetadataArgumentProvider provider = project.getObjects().newInstance(ArcResponseHandlerMetadataArgumentProvider.class);
        provider.getMetadataFile().set(task.getOutputFile());
        assertEquals(List.of("arc.responseHandlerMetadata=" + output.toFile().toURI().toASCIIString()), provider.asArguments());
    }
}
