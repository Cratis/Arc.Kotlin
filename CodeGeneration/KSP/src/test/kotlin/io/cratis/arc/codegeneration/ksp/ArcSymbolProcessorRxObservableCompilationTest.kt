// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspProcessorOptions
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import io.cratis.arc.artifacts.ArcArtifactModule
import java.io.File
import java.nio.file.Files
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Proves KSP recognises RxJava 3 return types as observable queries, in the same static-method shape
 * `Flow` and `Flow.Publisher` already use.
 */
@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorRxObservableCompilationTest {
    @Test
    fun `a Kotlin companion query returning Observable is an observable query`() {
        val result = compile(
            listOf(
                SourceFile.kotlin(
                    "RxObservableReadModel.kt",
                    """
                    package rx.fixtures

                    import io.cratis.arc.artifacts.ReadModel
                    import io.reactivex.rxjava3.core.Observable

                    @ReadModel
                    public data class RxObservableReadModel(public val value: String) {
                        public companion object {
                            public fun observe(): Observable<RxObservableReadModel> =
                                Observable.just(RxObservableReadModel("seed"))
                        }
                    }
                    """.trimIndent()
                )
            )
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val descriptor = singleQueryDescriptorTransport(result)
        assertEquals("OBSERVABLE", descriptor)
    }

    @Test
    fun `a Kotlin companion query returning Subject is an observable query`() {
        val result = compile(
            listOf(
                SourceFile.kotlin(
                    "RxSubjectReadModel.kt",
                    """
                    package rx.fixtures

                    import io.cratis.arc.artifacts.ReadModel
                    import io.reactivex.rxjava3.subjects.BehaviorSubject
                    import io.reactivex.rxjava3.subjects.Subject

                    @ReadModel
                    public data class RxSubjectReadModel(public val value: String) {
                        public companion object {
                            public fun observe(): Subject<RxSubjectReadModel> =
                                BehaviorSubject.createDefault(RxSubjectReadModel("seed"))
                        }
                    }
                    """.trimIndent()
                )
            )
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals("OBSERVABLE", singleQueryDescriptorTransport(result))
    }

    @Test
    fun `a static Java query returning Observable is an observable query`() {
        val result = compile(
            listOf(
                SourceFile.java(
                    "JavaRxReadModel.java",
                    """
                    package rx.fixtures;

                    import io.cratis.arc.artifacts.ReadModel;
                    import io.reactivex.rxjava3.core.Observable;

                    @ReadModel
                    public record JavaRxReadModel(String value) {
                        public static Observable<JavaRxReadModel> observe() {
                            return Observable.just(new JavaRxReadModel("seed"));
                        }
                    }
                    """.trimIndent()
                )
            )
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals("OBSERVABLE", singleQueryDescriptorTransport(result))
    }

    @Test
    fun `an instance-method Rx query is rejected exactly like the Flow equivalent`() {
        val result = compile(
            listOf(
                SourceFile.kotlin(
                    "InstanceRxReadModel.kt",
                    """
                    package rx.fixtures

                    import io.cratis.arc.artifacts.ReadModel
                    import io.reactivex.rxjava3.core.Observable

                    @ReadModel
                    public data class InstanceRxReadModel(public val value: String) {
                        public fun observe(): Observable<InstanceRxReadModel> =
                            Observable.just(this)
                    }
                    """.trimIndent()
                )
            )
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            "companion object" in result.messages,
            "an instance Rx query must fail for the same reason a Flow one does, was: ${result.messages}"
        )
    }

    @Test
    fun `declaring observable transport on a non-observable return type still fails`() {
        val result = compile(
            listOf(
                SourceFile.kotlin(
                    "MislabelledReadModel.kt",
                    """
                    package rx.fixtures

                    import io.cratis.arc.artifacts.ReadModel
                    import io.cratis.arc.queries.QueryTransport
                    import io.cratis.arc.queries.QueryTransportType

                    @ReadModel
                    public data class MislabelledReadModel(public val value: String) {
                        public companion object {
                            @QueryTransport(QueryTransportType.OBSERVABLE)
                            public fun observe(): MislabelledReadModel = MislabelledReadModel("seed")
                        }
                    }
                    """.trimIndent()
                )
            )
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(
            "declares observable transport" in result.messages,
            "the mislabelled-transport diagnostic must still fire, was: ${result.messages}"
        )
    }

    private fun singleQueryDescriptorTransport(result: JvmCompilationResult): String {
        val module = result.classLoader.loadClass("io.cratis.arc.generated.RxFixturesArcArtifactModule")
            .getConstructor().newInstance() as ArcArtifactModule
        assertEquals(1, module.queryPerformers.size, "exactly one query performer must be generated")
        return module.queryPerformers.single().descriptor.transport.toString()
    }

    private fun compile(sources: List<SourceFile>): JvmCompilationResult {
        val workingDirectory: File = Files.createTempDirectory("arc-rx-observable").toFile()
        return KotlinCompilation().apply {
            useKsp2()
            this.sources = sources
            workingDir = workingDirectory
            inheritClassPath = true
            symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
            kspProcessorOptions = mutableMapOf("arc.moduleName" to "RxFixtures")
            kspWithCompilation = true
            messageOutputStream = System.out
        }.compile()
    }
}
