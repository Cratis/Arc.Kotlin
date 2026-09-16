// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class FluentMetadataJavaConformanceTest {
    @TempDir Path directory;

    @Test
    void managedTaskChecksBothClasspathsAndProviderUsesAnEncodedFileUri() throws Exception {
        var project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
        var task = project.getTasks().register("extract", ExtractArcFluentValidationMetadata.class).get();
        Path output = directory.resolve("fluent index.json");
        task.getCompileArtifacts().setFrom(List.of());
        task.getRuntimeArtifacts().setFrom(List.of());
        task.getOutputFile().set(output.toFile());
        assertSame(task.getCompileArtifacts(), task.getCompileArtifacts());
        assertSame(task.getRuntimeArtifacts(), task.getRuntimeArtifacts());
        task.extract();
        assertEquals("{\"formatVersion\":1,\"modules\":[]}\n", Files.readString(output, StandardCharsets.UTF_8));
        var timestamp = Files.getLastModifiedTime(output);
        task.extract();
        assertEquals(timestamp, Files.getLastModifiedTime(output));
        ExtractArcFluentValidationMetadataCli.main(new String[] {"", "", output.toString()});
        assertEquals(timestamp, Files.getLastModifiedTime(output));
        var provider = project.getObjects().newInstance(ArcFluentValidationMetadataArgumentProvider.class);
        provider.getMetadataFile().set(task.getOutputFile());
        assertEquals(List.of("arc.fluentValidationMetadata=" + output.toFile().toURI().toASCIIString()), provider.asArguments());
        provider.getRootCompilation().set(true);
        assertEquals(List.of("arc.fluentValidationMetadata=" + output.toFile().toURI().toASCIIString(), "arc.fluentValidationRoot=true"), provider.asArguments());
    }
}
