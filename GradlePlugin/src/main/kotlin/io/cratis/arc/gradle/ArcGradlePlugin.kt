// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import com.google.devtools.ksp.gradle.KspExtension
import com.google.devtools.ksp.gradle.KspAATask
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/** Configures Arc compilation, manifest generation, dependencies, and TypeScript proxy generation. */
public class ArcGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("cratisArc", ArcExtension::class.java)
        extension.moduleName.convention(project.name)
        extension.dependencyVersion.convention(pluginVersion())

        project.pluginManager.apply("org.jetbrains.kotlin.jvm")
        project.pluginManager.apply("com.google.devtools.ksp")

        configureArcDependencies(project, extension)
        configureJvm(project)
        configureResponseHandlerMetadata(project)
        configureFluentValidationMetadata(project)
        project.extensions.configure(KspExtension::class.java) { ksp ->
            ksp.arg("arc.moduleName", extension.moduleName)
        }
        val generateTask = registerProxyTask(project, extension)

        project.afterEvaluate {
            require(extension.moduleName.get().isNotBlank()) { "cratisArc.moduleName cannot be blank." }
            require(extension.endpoints.segmentsToSkip.get() >= 0) {
                "cratisArc.endpoints.segmentsToSkip cannot be negative."
            }
            require(extension.proxies.segmentsToSkip.get() >= 0) {
                "cratisArc.proxies.segmentsToSkip cannot be negative."
            }
            if (extension.manageDependencies.get()) {
                require(extension.dependencyVersion.get().isNotBlank()) { "cratisArc.dependencyVersion cannot be blank." }
            }
            generateTask.configure { task ->
                task.onlyIf {
                    task.generationEnabled.get() && task.outputDirectory.isPresent
                }
            }
        }
    }

    private fun configureResponseHandlerMetadata(project: Project) {
        val kotlin = project.extensions.getByType(KotlinJvmProjectExtension::class.java)
        kotlin.target.compilations.configureEach { compilation ->
            val artifacts = project.configurations.getByName(compilation.compileDependencyConfigurationName)
                .incoming.artifactView { view ->
                    view.attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                        project.objects.named(LibraryElements::class.java, LibraryElements.JAR))
                }.files
            val suffix = compilation.name.replaceFirstChar { it.uppercase() }
            val extract = project.tasks.register("extract${suffix}ArcResponseHandlerMetadata", ExtractArcResponseHandlerMetadata::class.java) { task ->
                task.group = "arc"
                task.dependencyArtifacts.from(artifacts)
                task.outputFile.convention(project.layout.buildDirectory.file("arc/response-handlers/${compilation.name}.json"))
            }
            val kspTaskName = "ksp" + compilation.compileKotlinTaskName.removePrefix("compile")
            project.tasks.withType(KspAATask::class.java).configureEach { task ->
                if (task.name == kspTaskName) {
                    val provider = project.objects.newInstance(ArcResponseHandlerMetadataArgumentProvider::class.java)
                    provider.metadataFile.set(extract.flatMap { it.outputFile })
                    task.commandLineArgumentProviders.add(provider)
                    // NOT @Incremental: metadata changes require all source roots, not just a KSP task rerun.
                    task.inputs.file(provider.metadataFile).withPropertyName("arcResponseHandlerMetadata")
                        .withPathSensitivity(PathSensitivity.NONE)
                }
            }
        }
    }

    private fun configureFluentValidationMetadata(project: Project) {
        val kotlin = project.extensions.getByType(KotlinJvmProjectExtension::class.java)
        kotlin.target.compilations.configureEach { compilation ->
            fun artifacts(configuration: String) = project.configurations.getByName(configuration).incoming.artifactView { view ->
                view.attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    project.objects.named(LibraryElements::class.java, LibraryElements.JAR))
            }.files
            val suffix = compilation.name.replaceFirstChar { it.uppercase() }
            val extract = project.tasks.register("extract${suffix}ArcFluentValidationMetadata", ExtractArcFluentValidationMetadata::class.java) { task ->
                task.group = "arc"
                task.compileArtifacts.from(artifacts(compilation.compileDependencyConfigurationName))
                task.runtimeArtifacts.from(artifacts(compilation.runtimeDependencyConfigurationName))
                // Associated main/test-fixture outputs are on KSP's classpath but are not resolved
                // dependency artifacts. Include their classes AND resources after their owning tasks.
                val associated = project.provider {
                    compilation.allAssociatedCompilations.map { it.output.allOutputs.filter { file -> file.exists() } }
                }
                task.compileArtifacts.from(associated)
                task.runtimeArtifacts.from(associated)
                task.dependsOn(project.provider { compilation.allAssociatedCompilations.map { project.tasks.named(it.compileAllTaskName) } })
                task.outputFile.convention(project.layout.buildDirectory.file("arc/fluent-validation/${compilation.name}.json"))
            }
            val kspTaskName = "ksp" + compilation.compileKotlinTaskName.removePrefix("compile")
            project.tasks.withType(KspAATask::class.java).configureEach { task ->
                if (task.name == kspTaskName) {
                    val provider = project.objects.newInstance(ArcFluentValidationMetadataArgumentProvider::class.java)
                    provider.metadataFile.set(extract.flatMap { it.outputFile })
                    provider.rootCompilation.set(compilation.name == "main")
                    task.commandLineArgumentProviders.add(provider)
                    val binaryClasspath = project.objects.newInstance(ArcValidationClasspathArgumentProvider::class.java)
                    binaryClasspath.classpath.from(compilation.compileDependencyFiles)
                    task.commandLineArgumentProviders.add(binaryClasspath)
                    // Private annotation edits can leave public ABI and fluent declarations unchanged.
                    task.inputs.files(binaryClasspath.classpath).withPropertyName("arcValidationClasspath")
                        .withNormalizer(org.gradle.api.tasks.ClasspathNormalizer::class.java)
                    // Nonincremental input: changed dependency rules rebuild every unchanged source root.
                    task.inputs.file(provider.metadataFile).withPropertyName("arcFluentValidationMetadata").withPathSensitivity(PathSensitivity.NONE)
                }
            }
        }
    }

    private fun configureJvm(project: Project) {
        project.extensions.configure(JavaPluginExtension::class.java) { extension ->
            extension.toolchain.languageVersion.set(JavaLanguageVersion.of(17))
        }
        project.tasks.withType(JavaCompile::class.java).configureEach { task ->
            task.options.release.set(17)
            task.options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
        }
        project.extensions.configure(KotlinJvmProjectExtension::class.java) { extension ->
            extension.jvmToolchain(17)
        }
        project.tasks.withType(KotlinJvmCompile::class.java).configureEach { task ->
            task.compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            task.compilerOptions.allWarningsAsErrors.set(true)
        }
    }

    private fun registerProxyTask(
        project: Project,
        extension: ArcExtension
    ): org.gradle.api.tasks.TaskProvider<GenerateArcProxies> {
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")

        // Write the endpoint-options resource so the Spring Boot starter can verify agreement at
        // startup. The output directory is added to the main source set resources so it lands on
        // the application classpath via processResources.
        val writeEndpointOptions = project.tasks.register(
            "writeArcEndpointOptionsResource",
            WriteArcEndpointOptionsResource::class.java
        ) { task ->
            task.group = "arc"
            task.description = "Writes META-INF/arc/endpoint-options.json so the Spring Boot starter can verify route-convention agreement at startup"
            task.routePrefix.convention(extension.endpoints.routePrefix)
            task.segmentsToSkipForRoute.convention(extension.endpoints.segmentsToSkip)
            task.includeCommandNameInRoute.convention(extension.endpoints.includeCommandNames)
            task.includeQueryNameInRoute.convention(extension.endpoints.includeQueryNames)
            task.enableQueryHttpMethod.convention(extension.endpoints.enableQueryHttpMethod)
            task.outputDirectory.convention(
                project.layout.buildDirectory.dir("generated/arc-endpoint-options/main")
            )
        }
        // Add the output to the main resources so processResources includes it in the JAR.
        main.resources.srcDir(writeEndpointOptions.flatMap { it.outputDirectory })

        val task = project.tasks.register("generateArcProxies", GenerateArcProxies::class.java) { proxyTask ->
            proxyTask.group = "arc"
            proxyTask.description = "Generates TypeScript proxies from Arc artifact manifests"
            proxyTask.generationEnabled.convention(extension.proxies.enabled)
            proxyTask.moduleName.convention(extension.moduleName)
            proxyTask.routePrefix.convention(extension.endpoints.routePrefix)
            proxyTask.routeSegmentsToSkip.convention(extension.endpoints.segmentsToSkip)
            proxyTask.includeCommandNames.convention(extension.endpoints.includeCommandNames)
            proxyTask.includeQueryNames.convention(extension.endpoints.includeQueryNames)
            proxyTask.enableQueryHttpMethod.convention(extension.endpoints.enableQueryHttpMethod)
            proxyTask.removeStaleGeneratedFiles.convention(extension.proxies.removeStaleGeneratedFiles)
            proxyTask.useProxyFileSuffix.convention(extension.proxies.useProxyFileSuffix)
            proxyTask.proxySegmentsToSkip.convention(extension.proxies.segmentsToSkip)
            proxyTask.typeMappings.convention(extension.proxies.typeMappings)
            proxyTask.packageMappings.convention(extension.proxies.packageMappings)
            proxyTask.outputDirectory.convention(extension.proxies.outputDirectory)
            proxyTask.manifestClasspath.from(
                main.output,
                main.compileClasspath,
                main.runtimeClasspath
            )
            proxyTask.dependsOn(project.tasks.named(main.classesTaskName))
            proxyTask.dependsOn(project.tasks.matching { it.name == "kspKotlin" })
            proxyTask.dependsOn(project.tasks.matching { it.name == "processResources" })
        }
        project.tasks.named("build").configure { it.dependsOn(task) }
        return task
    }

    private fun configureArcDependencies(project: Project, extension: ArcExtension) {
        configureManagedDependency(project, extension, "arcManagedRuntime", "implementation", "arc",
            listOf("implementation", "api", "compileOnly", "runtimeOnly"))
        configureManagedDependency(project, extension, "arcManagedProcessor", "ksp", "arc-ksp", listOf("ksp"))
    }

    private fun configureManagedDependency(
        project: Project,
        extension: ArcExtension,
        configurationName: String,
        targetName: String,
        artifact: String,
        consumerConfigurations: List<String>
    ) {
        // KSP captures nonempty configurations before Arc's afterEvaluate validation. Register the
        // provider now, but read the extension and consumer declarations only when Gradle needs them.
        val managed = project.configurations.create(configurationName) {
            it.isCanBeConsumed = false
            it.isCanBeResolved = false
            it.isVisible = false
        }
        managed.dependencies.addAllLater(project.provider {
            if (!extension.manageDependencies.get() || consumerConfigurations
                    .mapNotNull(project.configurations::findByName)
                    .any { it.hasDependency("io.cratis", artifact) }) {
                emptyList()
            } else {
                val version = extension.dependencyVersion.get()
                require(version.isNotBlank()) { "cratisArc.dependencyVersion cannot be blank." }
                listOf(project.dependencies.create("io.cratis:$artifact:$version"))
            }
        })
        // Inspect only direct consumer dependencies above: allDependencies would include this
        // parent and recursively evaluate its own provider.
        project.configurations.getByName(targetName).extendsFrom(managed)
    }

    private fun Configuration.hasDependency(group: String, name: String): Boolean =
        dependencies.any { it.group == group && it.name == name }

    private fun pluginVersion(): String =
        ArcGradlePlugin::class.java.`package`.implementationVersion ?: "0.0.0-SNAPSHOT"
}
