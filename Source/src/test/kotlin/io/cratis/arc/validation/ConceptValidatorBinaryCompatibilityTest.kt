// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

@file:Suppress("DEPRECATION")

package io.cratis.arc.validation

import io.cratis.arc.concepts.ConceptAs
import java.io.ByteArrayOutputStream
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConceptValidatorBinaryCompatibilityTest {
    @TempDir lateinit var work: Path

    @Test
    fun `caller and implementation compiled against the original API still link and dispatch`() {
        compileBaseline()
        URLClassLoader(arrayOf(work.toUri().toURL()), ConceptValidator::class.java.classLoader).use { loader ->
            val type = loader.loadClass("compatibility.OldValidator")
            val validator = type.getConstructor().newInstance() as ConceptValidator<*>
            val conceptType = validator.conceptType
            val concept = conceptType.getConstructor(String::class.java).newInstance("value")
            val result = type.getMethod("call", ConceptValidator::class.java, conceptType)
                .invoke(null, validator, concept)
            assertEquals(emptyList<Any>(), result)
            assertTrue(ConceptValidation(listOf(validator)).validate(concept).isEmpty())
        }
        val method = ConceptValidator::class.java.getMethod("validate", ConceptAs::class.java)
        assertTrue(Modifier.isAbstract(method.modifiers))
    }

    private fun compileBaseline() {
        val sources = baselineSources().map { (name, source) ->
            work.resolve(name).also { it.parent.createDirectories(); it.writeText(source) }.toString()
        }
        val classpath = Path.of(ConceptValidator::class.java.protectionDomain.codeSource.location.toURI()).toString()
        val output = ByteArrayOutputStream()
        val arguments = listOf("-classpath", classpath, "-d", work.toString()) + sources
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, output, output, *arguments.toTypedArray()), output.toString())
    }

    private fun baselineSources(): Map<String, String> = mapOf(
        "io/cratis/arc/concepts/ConceptAs.java" to """
            package io.cratis.arc.concepts;
            public interface ConceptAs<T> { T value(); }
        """.trimIndent(),
        "io/cratis/arc/validation/ConceptValidator.java" to """
            package io.cratis.arc.validation;
            import io.cratis.arc.concepts.ConceptAs;
            import io.cratis.arc.results.ValidationResult;
            import java.util.List;
            public interface ConceptValidator<T extends ConceptAs<?>> {
                Class<T> getConceptType();
                List<ValidationResult> validate(T concept);
            }
        """.trimIndent(),
        "compatibility/OldValidator.java" to """
            package compatibility;
            import io.cratis.arc.concepts.ConceptAs;
            import io.cratis.arc.results.ValidationResult;
            import io.cratis.arc.validation.ConceptValidator;
            import java.util.List;
            public final class OldValidator implements ConceptValidator<OldValidator.Code> {
                public record Code(String value) implements ConceptAs<String> { }
                public Class<Code> getConceptType() { return Code.class; }
                public List<ValidationResult> validate(Code concept) { return List.of(); }
                public static List<ValidationResult> call(ConceptValidator<Code> validator, Code concept) {
                    return validator.validate(concept);
                }
            }
        """.trimIndent(),
    )
}
