// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated

/** Service-loaded only from the private native-test JAR, never the published processor. */
internal class ArcFluentValidationNativePrototypeProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val loader = javaClass.classLoader
        val parserName = "org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment"
        // Reflect on compiler infrastructure only, never on application declarations or rule bodies.
        val parser = try {
            Class.forName(parserName, false, loader)
        } catch (missing: ClassNotFoundException) {
            environment.logger.error("[PROTOTYPE-PARSER-MISSING] Explicit native processor compiler-embeddable dependency required.")
            throw IllegalStateException("[PROTOTYPE-PARSER-MISSING] $parserName", missing)
        }
        check(parser.classLoader === loader) { "[PROTOTYPE-ISOLATION] Parser leaked from parent: ${parser.classLoader}" }
        val parentParser = try {
            Class.forName(parserName, false, loader.parent)
        } catch (_: ClassNotFoundException) {
            null
        }
        check(parentParser == null) { "[PROTOTYPE-ISOLATION] Parser is visible through processor parent" }
        val compilerVersion = Class.forName("org.jetbrains.kotlin.config.KotlinCompilerVersion", true, loader)
            .getField("VERSION").get(null)
        environment.logger.warn("[PROTOTYPE-ORIGIN] provider=${javaClass.protectionDomain.codeSource.location}; " +
            "parser=${parser.protectionDomain.codeSource.location}; version=$compilerVersion; " +
            "sameLoader=true; parentParser=false; loader=${loader.javaClass.name}; java=${System.getProperty("java.version")}; " +
            "javac=${com.sun.source.util.JavacTask::class.java.module.name}")
        val delegate = PrototypeProvider().create(environment)
        return object : SymbolProcessor {
            private var round = 0
            override fun process(resolver: Resolver): List<KSAnnotated> {
                round++
                environment.logger.warn("[PROTOTYPE-ROUND] $round")
                return delegate.process(resolver)
            }
            override fun finish() {
                delegate.finish()
                environment.logger.warn("[PROTOTYPE-FINISH] rounds=$round")
            }
            override fun onError() = delegate.onError()
        }
    }
}
