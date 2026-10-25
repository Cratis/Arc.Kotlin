// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

@file:Suppress("DEPRECATION")

package io.cratis.arc.springdata.mongodb

import io.cratis.arc.naming.NamingPolicy
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Proves callers and implementations compiled against the pre-Fundamentals Arc naming API still link:
 * the Arc [NamingPolicy] and its `DefaultImpls`, the Arc-typed [TenantContextMongoAccess] constructors,
 * [DefaultNamingPolicy] and the auto-configuration's compatibility factory.
 */
class NamingPolicyBinaryCompatibilityTest {
    @TempDir lateinit var work: Path

    @Test
    fun `caller and implementation compiled against the original API still link and dispatch`() {
        compileBaseline()
        URLClassLoader(arrayOf(work.toUri().toURL()), NamingPolicy::class.java.classLoader).use { loader ->
            val policyType = loader.loadClass("compatibility.OldPolicy")
            val policy = policyType.getConstructor().newInstance()
            assertTrue(policy is NamingPolicy)
            assertTrue(policy is io.cratis.fundamentals.serialization.NamingPolicy)

            val caller = loader.loadClass("compatibility.OldCaller")
            val resolver = TenantAwareMongoOperationsResolver { tenantId ->
                TenantMongoOperations(tenantId, org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoOperations::class.java))
            }
            val result = caller.getMethod("call", NamingPolicy::class.java, TenantAwareMongoOperationsResolver::class.java)
                .invoke(null, policy, resolver) as Array<*>
            assertEquals("Name", result[0])
            assertEquals("MongoTaskReadModel", result[1])
            assertTrue(result[2] is DefaultNamingPolicy)
            assertEquals(true, result[3])
        }
    }

    @Test
    fun `the Arc naming policy interface keeps its members and DefaultImpls`() {
        val members = NamingPolicy::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue("getReadModelName" in members)
        assertTrue("getPropertyName" in members)
        val defaultImpls = Class.forName("io.cratis.arc.naming.NamingPolicy\$DefaultImpls")
        assertEquals(
            "java.lang.String",
            defaultImpls.getMethod("getPropertyName", NamingPolicy::class.java, String::class.java).returnType.name
        )
    }

    private fun compileBaseline() {
        val sources = baselineSources().map { (name, source) ->
            work.resolve(name).also { it.parent.createDirectories(); it.writeText(source) }.toString()
        }
        val output = ByteArrayOutputStream()
        val arguments = listOf("-classpath", System.getProperty("java.class.path"), "-d", work.toString()) + sources
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, output, output, *arguments.toTypedArray()), output.toString())
    }

    private fun baselineSources(): Map<String, String> = mapOf(
        "compatibility/OldPolicy.java" to """
            package compatibility;
            import io.cratis.arc.naming.NamingPolicy;
            public final class OldPolicy implements NamingPolicy {
                public String getReadModelName(Class<?> readModelType) { return readModelType.getSimpleName(); }
                public String getPropertyName(String name) { return NamingPolicy.DefaultImpls.getPropertyName(this, name); }
            }
        """.trimIndent(),
        "compatibility/OldCaller.java" to """
            package compatibility;
            import io.cratis.arc.naming.NamingPolicy;
            import io.cratis.arc.springdata.mongodb.DefaultNamingPolicy;
            import io.cratis.arc.springdata.mongodb.TenantAwareMongoOperationsResolver;
            import io.cratis.arc.springdata.mongodb.TenantContextMongoAccess;
            import io.cratis.arc.springdata.mongodb.springboot.ArcSpringDataMongoAutoConfiguration;
            public final class OldCaller {
                public static final class MongoTaskReadModel { }
                public static Object[] call(NamingPolicy policy, TenantAwareMongoOperationsResolver resolver) {
                    var twoArg = new TenantContextMongoAccess(resolver, policy);
                    var threeArg = new TenantContextMongoAccess(resolver, policy, null);
                    return new Object[] {
                        NamingPolicy.DefaultImpls.getPropertyName(policy, "Name"),
                        new DefaultNamingPolicy(false).getReadModelName(MongoTaskReadModel.class),
                        new ArcSpringDataMongoAutoConfiguration().arcMongoNamingPolicy(),
                        twoArg != null && threeArg != null
                    };
                }
            }
        """.trimIndent(),
    )
}
