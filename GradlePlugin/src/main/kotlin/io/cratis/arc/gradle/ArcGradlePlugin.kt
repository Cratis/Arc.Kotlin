// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import com.google.devtools.ksp.gradle.KspExtension
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

        configureJvm(project)
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
                addArcDependencies(project, extension.dependencyVersion.get())
            }
            generateTask.configure { task ->
                task.onlyIf {
                    task.generationEnabled.get() && task.outputDirectory.isPresent
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
            proxyTask.proxySegmentsToSkip.convention(extension.proxies.segmentsToSkip)
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

    private fun addArcDependencies(project: Project, version: String) {
        require(version.isNotBlank()) { "cratisArc.dependencyVersion cannot be blank." }
        val runtimeConfigurations = listOf("implementation", "api", "compileOnly", "runtimeOnly")
        val hasRuntime = runtimeConfigurations.mapNotNull(project.configurations::findByName).any {
            it.hasDependency("io.cratis", "arc")
        }
        if (!hasRuntime) project.dependencies.add("implementation", "io.cratis:arc:$version")

        val ksp = project.configurations.getByName("ksp")
        if (!ksp.hasDependency("io.cratis", "arc-ksp")) {
            project.dependencies.add("ksp", "io.cratis:arc-ksp:$version")
        }
    }

    private fun Configuration.hasDependency(group: String, name: String): Boolean =
        dependencies.any { it.group == group && it.name == name }

    private fun pluginVersion(): String =
        ArcGradlePlugin::class.java.`package`.implementationVersion ?: "0.0.0-SNAPSHOT"
}
