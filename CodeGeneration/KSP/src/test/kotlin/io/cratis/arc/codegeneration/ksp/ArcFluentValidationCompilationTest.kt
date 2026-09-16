// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSDeclaration
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.kspProcessorOptions
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.metadata.ValidationRuleDescriptor
import java.io.File
import java.nio.file.Files
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCompilerApi::class)
internal class ArcFluentValidationCompilationTest {
    private val root = File(System.getProperty("arc.fluent.evidence") ?: System.getProperty("java.io.tmpdir"))
    private data class Compilation(val result: JvmCompilationResult, val directory: File) {
        fun resource(path: String): File = directory.resolve("ksp/sources/resources/$path")
    }

    @Test
    fun `real Kotlin and ordinary Java declarations compile merged metadata and verified runtime registration`() {
        val compiled = compile(listOf(model, javaModel, kotlinRules("KotlinRules", "ruleFor(\"name\").notNull().maxLength(10)"), javaRules("JavaRules", "ruleFor(\"name\").notNull().maxLength(10);")))
        assertEquals(KotlinCompilation.ExitCode.OK, compiled.result.exitCode, compiled.result.messages)
        val module = compiled.result.classLoader.loadClass("io.cratis.arc.generated.FluentArcArtifactModule").getConstructor().newInstance() as ArcArtifactModule
        assertEquals(2, module.fluentValidators.size)
        assertEquals(listOf("fixture.JavaRules", "fixture.KotlinRules"), module.fluentValidators.map { it.validator.javaClass.name })
        module.fluentValidators.forEach { registration ->
            assertEquals(registration.expectedRules, registration.validator.rules)
            val name = registration.validator.rules.single()
            assertEquals(String::class.java, name.memberType)
            assertEquals(listOf("notNull", "maxLength"), name.rules.map { it.ruleName })
        }
        val kotlinModel = module.types.single { it.fullyQualifiedName == "fixture.Person" }
        assertEquals(listOf(ValidationRuleDescriptor("notNull"), ValidationRuleDescriptor("maxLength", listOf(10))), kotlinModel.properties.single { it.name == "name" }.validationRules)
        assertEquals(listOf("notNull"), kotlinModel.properties.single { it.name == "annotationOnly" }.validationRules.map { it.ruleName })
        assertTrue(compiled.resource("META-INF/cratis/arc-fluent-validation/Fluent.json").isFile)
        assertTrue(compiled.directory.resolve("classes/fixture/JavaRules.class").isFile)
        assertEquals(compiled.resource("META-INF/cratis/arc-fluent-validation/Fluent.json").readText(),
            compile(listOf(model, javaModel, kotlinRules("KotlinRules", "ruleFor(\"name\").notNull().maxLength(10)"), javaRules("JavaRules", "ruleFor(\"name\").notNull().maxLength(10);")))
                .resource("META-INF/cratis/arc-fluent-validation/Fluent.json").readText())
    }

    @Test
    fun `validator only compilation exports its model and Java record JVM primitive boxed collection types`() {
        val record = SourceFile.java("Record.java", "package fixture; public record Record(int count, Integer boxed, java.util.List<String> items) {}")
        val rule = SourceFile.java("RecordRules.java", """
            package fixture;
            import io.cratis.arc.validation.FluentModelValidator;
            public final class RecordRules extends FluentModelValidator<Record> {
                public RecordRules() { super(Record.class); ruleFor("count").greaterThan(0); ruleFor("boxed").lessThan(10); ruleFor("items").minLength(1); }
            }
        """.trimIndent())
        val compiled = compile(listOf(record, rule))
        assertEquals(KotlinCompilation.ExitCode.OK, compiled.result.exitCode, compiled.result.messages)
        val module = compiled.result.classLoader.loadClass("io.cratis.arc.generated.FluentArcArtifactModule").getConstructor().newInstance() as ArcArtifactModule
        assertTrue(module.commandHandlers.isEmpty() && module.queryPerformers.isEmpty())
        assertEquals(listOf(Int::class.javaObjectType, Int::class.java, List::class.java), module.fluentValidators.single().expectedRules.map { it.memberType })
        assertEquals(listOf("fixture.Record"), module.types.map { it.fullyQualifiedName })
    }

    @Test
    fun `whole bodies reject Kotlin and compiled Java dynamic shapes and never publish partial aggregates`() {
        val kotlinBodies = listOf("if (true) ruleFor(\"name\").notNull()", "val x = ruleFor(\"name\"); x.notNull()",
            "ruleFor(\"name\").maxLength(2 + 2)", "ruleFor(\"name\").notNull().also { println(it) }")
        val javaBodies = listOf("if (true) ruleFor(\"name\").notNull();", "var x = ruleFor(\"name\"); x.notNull();",
            "ruleFor(\"name\").maxLength(2 + 2);", "ruleFor(\"name\").withMessage(\"a\" + \"b\");")
        val sources = listOf(model, javaModel) + kotlinBodies.mapIndexed { i, body -> kotlinRules("BadK$i", body) } +
            javaBodies.mapIndexed { i, body -> javaRules("BadJ$i", body) } + kotlinRules("Good", "ruleFor(\"name\").notNull()")
        val baseline = compile(sources, processor = false)
        assertEquals(KotlinCompilation.ExitCode.OK, baseline.result.exitCode, baseline.result.messages)
        val compiled = compile(sources)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, compiled.result.exitCode, compiled.result.messages)
        for (name in (0..3).flatMap { listOf("BadK$it", "BadJ$it") }) {
            assertTrue("[ARCKSP0308] Fluent validator 'fixture.$name':" in compiled.result.messages, compiled.result.messages)
        }
        assertFalse(compiled.resource("META-INF/cratis/arc-fluent-validation/Fluent.json").exists())
        assertFalse(compiled.resource("META-INF/cratis/arc/Fluent.json").exists())
    }

    @Test
    fun `invalid typed vocabulary diagnostics include member and call`() {
        val chains = listOf("ruleFor(\"missing\").notNull()", "ruleFor(\"name\").greaterThan(0)", "ruleFor(\"name\").creditCard()",
            "ruleFor(\"name\").maxLength(-1)", "ruleFor(\"name\").matches(\".\")", "ruleFor(\"name\").minLength(20).maxLength(10)")
        val sources = listOf(model, javaModel) + chains.flatMapIndexed { i, chain -> listOf(kotlinRules("RuleK$i", chain), javaRules("RuleJ$i", "$chain;")) }
        val compiled = compile(sources)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, compiled.result.exitCode, compiled.result.messages)
        for (name in chains.indices.flatMap { listOf("RuleK$it", "RuleJ$it") }) {
            assertTrue("[ARCKSP0309] Fluent validator 'fixture.$name':" in compiled.result.messages, compiled.result.messages)
        }
        assertTrue("call 'creditCard'" in compiled.result.messages)
        assertTrue("member 'missing'" in compiled.result.messages)
        assertFalse(compiled.resource("META-INF/cratis/arc/Fluent.json").exists())
    }

    @Test
    fun `missing index and malformed constructors fail visibly`() {
        val missing = compile(listOf(model, kotlinRules("Rules", "ruleFor(\"name\").notNull()")), indexed = false)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, missing.result.exitCode)
        assertTrue("[ARCKSP0310]" in missing.result.messages && "verified dependency index" in missing.result.messages)
        val invalid = compile(listOf(model, kotlinRules("Rules", "ruleFor(\"name\").notNull()", "val extra = 1")))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, invalid.result.exitCode)
        assertTrue("[ARCKSP0308]" in invalid.result.messages && "no other members" in invalid.result.messages)
    }

    @Test
    fun `all thirteen Kotlin and Java calls retain typed literal bounds and message ownership`() {
        val values = SourceFile.kotlin("Values.kt", """
            package fixture
            class Values(val text: String?, val phone: String?, val url: String?, val code: String?, val number: Double?)
        """.trimIndent())
        val chains = listOf("ruleFor(\"text\").notNull().notEmpty().minLength(1).maxLength(40).length(1, 40).emailAddress()",
            "ruleFor(\"phone\").phone()", "ruleFor(\"url\").url()", "ruleFor(\"code\").matches(\"^[A-Z]+$\")",
            "ruleFor(\"number\").greaterThan(-1.25).greaterThanOrEqual(0).lessThan(11).lessThanOrEqual(10).withMessage(\"Last only\")")
        val kotlin = SourceFile.kotlin("AllK.kt", "package fixture\nimport io.cratis.arc.validation.FluentModelValidator\nclass AllK : FluentModelValidator<Values>(Values::class.java) { init { ${chains.joinToString("; ")} } }")
        val java = SourceFile.java("AllJ.java", "package fixture; import io.cratis.arc.validation.FluentModelValidator; public final class AllJ extends FluentModelValidator<Values> { public AllJ() { super(Values.class); ${chains.joinToString("; ")}; } }")
        val compiled = compile(listOf(values, kotlin, java))
        assertEquals(KotlinCompilation.ExitCode.OK, compiled.result.exitCode, compiled.result.messages)
        val module = compiled.result.classLoader.loadClass("io.cratis.arc.generated.FluentArcArtifactModule").getConstructor().newInstance() as ArcArtifactModule
        val first = module.fluentValidators.first().expectedRules
        assertEquals(first, module.fluentValidators.last().expectedRules)
        assertEquals(13, first.sumOf { it.rules.size })
        assertEquals(listOf("Last only"), first.flatMap { it.rules }.mapNotNull { it.message })
        assertEquals(Double::class.javaObjectType, first.single { it.member == "number" }.memberType)
    }

    @Test
    fun `merged annotation contradictions fail while annotation only recursion and credit card remain intact`() {
        val bad = compile(listOf(model, kotlinRules("Bad", "ruleFor(\"name\").minLength(20)")))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, bad.result.exitCode)
        assertTrue("[ARCKSP0309] Validation rules on 'fixture.Person.name' declare contradictory length bounds" in bad.result.messages, bad.result.messages)
        val validModel = SourceFile.kotlin("Person.kt", """
            package fixture
            class Child(val value: String)
            class Person(val name: String, @field:io.cratis.arc.validation.CreditCard val card: String, @field:jakarta.validation.Valid val child: Child)
        """.trimIndent())
        val good = compile(listOf(validModel, kotlinRules("Good", "ruleFor(\"name\").notEmpty()")))
        assertEquals(KotlinCompilation.ExitCode.OK, good.result.exitCode, good.result.messages)
        val module = good.result.classLoader.loadClass("io.cratis.arc.generated.FluentArcArtifactModule").getConstructor().newInstance() as ArcArtifactModule
        val properties = module.types.single { it.fullyQualifiedName == "fixture.Person" }.properties
        assertEquals(listOf("creditCard"), properties.single { it.name == "card" }.validationRules.map { it.ruleName })
        assertTrue(properties.single { it.name == "child" }.validateRecursively)
        assertEquals(listOf("name"), module.fluentValidators.single().expectedRules.map { it.member })
    }

    @Test
    fun `ignored shared edges and open shared ancestors fail rather than disappearing from clients`() {
        val compiled = compile(listOf(SourceFile.kotlin("HiddenGraph.kt", """
            package fixture
            import io.cratis.arc.validation.FluentModelValidator
            class Child(val name: String)
            class ChildRules : FluentModelValidator<Child>(Child::class.java) { init { ruleFor("name").notEmpty() } }
            @io.cratis.arc.artifacts.Command
            class Hidden(@get:com.fasterxml.jackson.annotation.JsonIgnore val child: Child) { fun handle() {} }
            @io.cratis.arc.artifacts.Command
            open class OpenInput(val child: Child) { fun handle() {} }
        """.trimIndent())))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, compiled.result.exitCode, compiled.result.messages)
        assertTrue("Shared validation edge 'fixture.Hidden.child' is not a generated wire member" in compiled.result.messages, compiled.result.messages)
        assertTrue("Shared validation graph 'fixture.OpenInput' is open, abstract or polymorphic" in compiled.result.messages, compiled.result.messages)
        assertFalse(compiled.resource("META-INF/cratis/arc/Fluent.json").exists())
    }

    @Test
    fun `computed shared selectors reject even without a command input root`() {
        val compiled = compile(listOf(SourceFile.kotlin("ComputedRules.kt", """
            package fixture
            import io.cratis.arc.validation.FluentModelValidator
            class ComputedModel(val first: String) { val name: String get() = first + "server" }
            class ComputedRules : FluentModelValidator<ComputedModel>(ComputedModel::class.java) { init { ruleFor("name").maxLength(5) } }
        """.trimIndent())))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, compiled.result.exitCode, compiled.result.messages)
        assertTrue("member 'name' is computed; use backed wire state" in compiled.result.messages, compiled.result.messages)
        assertFalse(compiled.resource("META-INF/cratis/arc/Fluent.json").exists())
    }

    @Test
    fun `scalar concept targets and erased inline members fail instead of losing shared metadata`() {
        val compiled = compile(listOf(SourceFile.kotlin("UnsupportedTargets.kt", """
            package fixture
            import io.cratis.arc.validation.FluentModelValidator
            class Code(val value: String) : io.cratis.arc.concepts.ConceptAs<String> { override fun value(): String = value }
            class CodeRules : FluentModelValidator<Code>(Code::class.java) { init { ruleFor("value").notEmpty() } }
            @JvmInline value class Token(val value: String)
            class TokenOwner(val token: Token)
            class TokenRules : FluentModelValidator<TokenOwner>(TokenOwner::class.java) { init { ruleFor("token").notNull() } }
        """.trimIndent())))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, compiled.result.exitCode, compiled.result.messages)
        assertTrue("[ARCKSP0308] Fluent model 'fixture.Code' has no concrete model-property metadata" in compiled.result.messages, compiled.result.messages)
        assertTrue("[ARCKSP0309] Fluent validator 'fixture.TokenRules': member 'token' has an erased inline-value type" in compiled.result.messages, compiled.result.messages)
        assertFalse(compiled.resource("META-INF/cratis/arc/Fluent.json").exists())
    }

    @Test
    fun `supported Kotlin array rules retain exact JVM array type without widening Java graph support`() {
        val compiled = compile(listOf(SourceFile.kotlin("ArrayRules.kt", """
            package fixture
            import io.cratis.arc.validation.FluentModelValidator
            class ArrayModel(val values: Array<String>)
            class ArrayRules : FluentModelValidator<ArrayModel>(ArrayModel::class.java) { init { ruleFor("values").maxLength(3) } }
        """.trimIndent())))
        assertEquals(KotlinCompilation.ExitCode.OK, compiled.result.exitCode, compiled.result.messages)
        val module = compiled.result.classLoader.loadClass("io.cratis.arc.generated.FluentArcArtifactModule").getConstructor().newInstance() as ArcArtifactModule
        assertEquals(Array<String>::class.java, module.fluentValidators.single().expectedRules.single().memberType)
    }

    @Test
    fun `late invalid declaration suppresses all current aggregate metadata and reports one real source site`() {
        val late = object : SymbolProcessorProvider {
            override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
                private var emitted = false
                override fun process(resolver: Resolver): List<KSAnnotated> {
                    if (!emitted) {
                        emitted = true
                        environment.codeGenerator.createNewFile(Dependencies(true, *resolver.getAllFiles().toList().toTypedArray()), "fixture", "InvalidLate")
                            .bufferedWriter().use { it.write("""
                                package fixture
                                import io.cratis.arc.validation.FluentModelValidator
                                class InvalidLate : FluentModelValidator<Person>(Person::class.java) { init { if (true) ruleFor("name").notNull() } }
                            """.trimIndent()) }
                    }
                    return emptyList()
                }
            }
        }
        val command = SourceFile.kotlin("Anchor.kt", "package fixture\n@io.cratis.arc.artifacts.Command class Anchor(val value: String) { fun handle(): String = value }")
        val compiled = compile(listOf(model, command, kotlinRules("Good", "ruleFor(\"name\").notNull()")), providers = listOf(ArcSymbolProcessorProvider(), late))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, compiled.result.exitCode, compiled.result.messages)
        assertEquals(1, Regex("\\[ARCKSP0308]").findAll(compiled.result.messages).count())
        assertTrue("InvalidLate.kt:3:" in compiled.result.messages, compiled.result.messages)
        assertFalse(compiled.resource("META-INF/cratis/arc/Fluent.json").exists())
        assertFalse(compiled.resource("META-INF/cratis/arc-fluent-validation/Fluent.json").exists())
    }

    @Test
    fun `genuine unresolved model is deferred and late validator discoveries rebuild the terminal graph`() {
        val deferred = mutableListOf<String>()
        var rounds = 0
        val observed = object : SymbolProcessorProvider {
            override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
                val delegate = ArcSymbolProcessorProvider().create(environment)
                return object : SymbolProcessor {
                    override fun process(resolver: Resolver): List<KSAnnotated> {
                        rounds++
                        return delegate.process(resolver).also { values -> deferred += values.filterIsInstance<KSDeclaration>().map { it.simpleName.asString() } }
                    }
                    override fun finish() = delegate.finish()
                    override fun onError() = delegate.onError()
                }
            }
        }
        val late = object : SymbolProcessorProvider {
            override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
                private var emitted = false
                override fun process(resolver: Resolver): List<KSAnnotated> {
                    if (!emitted) {
                        emitted = true
                        environment.codeGenerator.createNewFile(Dependencies(true, *resolver.getAllFiles().toList().toTypedArray()), "fixture", "Late")
                            .bufferedWriter().use { it.write("""
                                package fixture
                                import io.cratis.arc.validation.FluentModelValidator
                                class LatePerson(val name: String)
                                class LateRules : FluentModelValidator<LatePerson>(LatePerson::class.java) { init { ruleFor("name").maxLength(10) } }
                            """.trimIndent()) }
                    }
                    return emptyList()
                }
            }
        }
        val early = SourceFile.kotlin("Early.kt", """
            package fixture
            import io.cratis.arc.validation.FluentModelValidator
            class EarlyRules : FluentModelValidator<LatePerson>(LatePerson::class.java) { init { ruleFor("name").notEmpty() } }
        """.trimIndent())
        val compiled = compile(listOf(early), providers = listOf(observed, late))
        assertEquals(KotlinCompilation.ExitCode.OK, compiled.result.exitCode, compiled.result.messages)
        assertTrue("EarlyRules" in deferred, deferred.toString())
        assertTrue(rounds >= 2)
        val module = compiled.result.classLoader.loadClass("io.cratis.arc.generated.FluentArcArtifactModule").getConstructor().newInstance() as ArcArtifactModule
        assertEquals(2, module.fluentValidators.size)
        assertEquals(listOf("notEmpty", "maxLength"), module.types.single().properties.single().validationRules.map { it.ruleName })
    }

    @Test
    fun `backed computed Kotlin selectors and custom Java record accessors reject atomically`() {
        val sources = listOf(
            SourceFile.kotlin("Backed.kt", """
                package fixture
                import io.cratis.arc.validation.FluentModelValidator
                class Backed(input: String) { val name: String = input; get() = field.trim() }
                class BackedRules : FluentModelValidator<Backed>(Backed::class.java) { init { ruleFor("name").minLength(2) } }
            """.trimIndent()),
            SourceFile.java("Trimmed.java", "package fixture; public record Trimmed(String name) { public String name() { return name.trim(); } }"),
            SourceFile.java("TrimmedRules.java", """
                package fixture;
                import io.cratis.arc.validation.FluentModelValidator;
                public final class TrimmedRules extends FluentModelValidator<Trimmed> {
                    public TrimmedRules() { super(Trimmed.class); ruleFor("name").minLength(2); }
                }
            """.trimIndent()))
        val baseline = compile(sources, processor = false)
        assertEquals(KotlinCompilation.ExitCode.OK, baseline.result.exitCode, baseline.result.messages)
        val record = baseline.result.classLoader.loadClass("fixture.Trimmed")
        assertEquals("a", record.getMethod("name").invoke(record.getConstructor(String::class.java).newInstance(" a ")))
        val compiled = compile(sources)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, compiled.result.exitCode, compiled.result.messages)
        for (name in listOf("BackedRules", "TrimmedRules")) assertTrue("[ARCKSP0309] Fluent validator 'fixture.$name':" in compiled.result.messages, compiled.result.messages)
        assertFalse(compiled.resource("META-INF/cratis/arc/Fluent.json").exists())
        assertFalse(compiled.resource("META-INF/cratis/arc-fluent-validation/Fluent.json").exists())
    }

    @Test
    fun `shadowed Kotlin class mapping rejects same package explicit and aliased imports without executing bodies`() {
        for ((packageName, importText, extensionName) in listOf(
            Triple("fixture", "", "java"), Triple("shadow", "import shadow.java", "java"), Triple("shadow", "import shadow.mapping as java", "mapping"))) {
            val sources = listOf(model, SourceFile.kotlin("Shadow.kt", """
                package $packageName
                import kotlin.reflect.KClass
                import fixture.Person
                val KClass<Person>.$extensionName: Class<Person>
                    get() { check(System.getProperty("allow.application.mapping") == "true") { "APPLICATION_MAPPING_EXECUTED" }; return javaObjectType }
            """.trimIndent()), SourceFile.kotlin("ShadowRules.kt", """
                package fixture
                import io.cratis.arc.validation.FluentModelValidator
                $importText
                class ShadowRules : FluentModelValidator<Person>(Person::class.java) { init { ruleFor("name").minLength(2) } }
            """.trimIndent()))
            val baseline = compile(sources, processor = false)
            assertEquals(KotlinCompilation.ExitCode.OK, baseline.result.exitCode, baseline.result.messages)
            val compiled = compile(sources)
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, compiled.result.exitCode, compiled.result.messages)
            assertTrue("[ARCKSP0308]" in compiled.result.messages && "class mapping" in compiled.result.messages, compiled.result.messages)
            assertFalse(compiled.resource("META-INF/cratis/arc/Fluent.json").exists())
            assertFalse(compiled.resource("META-INF/cratis/arc-fluent-validation/Fluent.json").exists())
        }
    }

    @Test
    fun `documentation Kotlin and ordinary Java declarations compile and execute`() {
        val compiled = compile(listOf(SourceFile.kotlin("KotlinExample.kt", """
            package docs.kotlin
            import io.cratis.arc.validation.FluentModelValidator
            class Person(val name: String?)
            class PersonRules : FluentModelValidator<Person>(Person::class.java) {
                init {
                    ruleFor("name").notNull().minLength(2).maxLength(40)
                        .withMessage("{PropertyName} is too long")
                }
            }
            fun verifyPersonRules() {
                val rules = PersonRules()
                check(rules.validate(Person("Ada")).isEmpty())
                check(rules.validate(Person("a")).single().members == listOf("name"))
            }
        """.trimIndent()), SourceFile.java("Person.java", "package docs.java; public record Person(String name) {}"),
            SourceFile.java("PersonRules.java", """
                package docs.java;
                import io.cratis.arc.validation.FluentModelValidator;
                public final class PersonRules extends FluentModelValidator<Person> {
                    public PersonRules() {
                        super(Person.class);
                        ruleFor("name").notEmpty().maxLength(40);
                    }
                }
            """.trimIndent()), SourceFile.java("VerifyPersonRules.java", """
                package docs.java;
                public final class VerifyPersonRules {
                    public static void main(String[] args) {
                        var rules = new PersonRules();
                        if (!rules.validate(new Person("Ada")).isEmpty()) throw new AssertionError();
                        var rejected = rules.validate(new Person(""));
                        if (!rejected.get(0).getMembers().equals(java.util.List.of("name"))) throw new AssertionError();
                    }
                }
            """.trimIndent())))
        assertEquals(KotlinCompilation.ExitCode.OK, compiled.result.exitCode, compiled.result.messages)
        compiled.result.classLoader.loadClass("docs.kotlin.KotlinExampleKt").getMethod("verifyPersonRules").invoke(null)
        compiled.result.classLoader.loadClass("docs.java.VerifyPersonRules").getMethod("main", Array<String>::class.java).invoke(null, emptyArray<String>())
    }

    @Test
    fun `default record public field and identity Kotlin getter preserve space input`() {
        val sources = listOf(
            SourceFile.java("Plain.java", "package fixture; public record Plain(String name) {}"),
            SourceFile.java("Identity.java", "package fixture; public record Identity(String name) { public String name() { return this.name; } }"),
            SourceFile.java("Field.java", "package fixture; public final class Field { public String name; public Field(String name) { this.name = name; } }"),
            SourceFile.kotlin("Getter.kt", "package fixture\nclass Getter(input: String) { val name = input; get() = field }")) +
            listOf("Plain", "Identity", "Field", "Getter").map { name -> SourceFile.kotlin("${name}Rules.kt", """
                package fixture
                import io.cratis.arc.validation.FluentModelValidator
                class ${name}Rules : FluentModelValidator<$name>($name::class.java) { init { ruleFor("name").minLength(2) } }
            """.trimIndent()) }
        val compiled = compile(sources)
        assertEquals(KotlinCompilation.ExitCode.OK, compiled.result.exitCode, compiled.result.messages)
        val module = compiled.result.classLoader.loadClass("io.cratis.arc.generated.FluentArcArtifactModule").getConstructor().newInstance() as ArcArtifactModule
        assertEquals(4, module.fluentValidators.size)
        fun <T : Any> evaluate(validator: io.cratis.arc.validation.FluentModelValidator<T>, text: String) =
            validator.validate(validator.modelType.getConstructor(String::class.java).newInstance(text))
        module.fluentValidators.forEach { registration ->
            assertTrue(evaluate(registration.validator, " a ").isEmpty())
            assertEquals(listOf("name"), evaluate(registration.validator, "a").single().members)
        }
    }

    @Test
    fun `computed nested edges reject for Kotlin and Java records without direct owner rules`() {
        val compiled = compile(listOf(SourceFile.kotlin("Edges.kt", """
            package fixture
            import io.cratis.arc.validation.FluentModelValidator
            class Child(val name: String)
            class ChildRules : FluentModelValidator<Child>(Child::class.java) { init { ruleFor("name").minLength(2) } }
            @io.cratis.arc.artifacts.Command
            class BackedEdge(input: Child) { val child: Child = input; get() = Child(field.name.trim()); fun handle() {} }
        """.trimIndent()), SourceFile.java("RecordEdge.java", """
            package fixture;
            @io.cratis.arc.artifacts.Command
            public record RecordEdge(Child child) {
                public Child child() { return new Child(child.getName().trim()); }
                public void handle() {}
            }
        """.trimIndent())))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, compiled.result.exitCode, compiled.result.messages)
        for (name in listOf("BackedEdge", "RecordEdge")) assertTrue("Shared validation edge 'fixture.$name.child': member 'child' is computed" in compiled.result.messages, compiled.result.messages)
        assertFalse(compiled.resource("META-INF/cratis/arc/Fluent.json").exists())
    }

    @Test
    fun `inherited and binary model selectors fail when source accessor identity is unproved`() {
        val inherited = compile(listOf(SourceFile.kotlin("Inherited.kt", """
            package fixture
            import io.cratis.arc.validation.FluentModelValidator
            open class Base(val name: String)
            class Inherited(name: String) : Base(name)
            class InheritedRules : FluentModelValidator<Inherited>(Inherited::class.java) { init { ruleFor("name").minLength(2) } }
        """.trimIndent())))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, inherited.result.exitCode, inherited.result.messages)
        assertTrue("inherited or unproved member" in inherited.result.messages, inherited.result.messages)
        val binary = compile(listOf(model), processor = false)
        val compiled = compile(listOf(kotlinRules("BinaryRules", "ruleFor(\"name\").minLength(2)")), classpaths = listOf(binary.directory.resolve("classes")))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, compiled.result.exitCode, compiled.result.messages)
        assertTrue("no source accessor proof" in compiled.result.messages, compiled.result.messages)
        assertFalse(compiled.resource("META-INF/cratis/arc/Fluent.json").exists())
    }

    private fun compile(sources: List<SourceFile>, processor: Boolean = true, indexed: Boolean = true,
        providers: List<SymbolProcessorProvider> = listOf(ArcSymbolProcessorProvider()), classpaths: List<File> = emptyList()): Compilation {
        root.mkdirs()
        val directory = Files.createTempDirectory(root.toPath(), "fluent-compile-").toFile()
        val index = directory.resolve("index.json").apply { writeText("{\"formatVersion\":1,\"modules\":[]}") }
        val result = KotlinCompilation().apply {
            workingDir = directory
            useKsp2()
            this.sources = sources
            this.classpaths = classpaths
            inheritClassPath = true
            if (processor) symbolProcessorProviders = providers.toMutableList()
            kspProcessorOptions = mutableMapOf("arc.moduleName" to "Fluent").apply { if (indexed) put(FluentValidationMetadata.OPTION, index.toURI().toASCIIString()) }
            kspWithCompilation = true
            messageOutputStream = System.out
        }.compile()
        directory.resolve("compile.log").writeText(result.messages)
        return Compilation(result, directory)
    }

    private val model = SourceFile.kotlin("Person.kt", """
        package fixture
        class Person(@field:jakarta.validation.constraints.Size(max=10) val name: String,
            @field:jakarta.validation.constraints.NotNull val annotationOnly: String)
    """.trimIndent())
    private val javaModel = SourceFile.java("JavaPerson.java", """
        package fixture;
        public final class JavaPerson { @jakarta.validation.constraints.Size(max=10) public String name; }
    """.trimIndent())
    private fun kotlinRules(name: String, body: String, extra: String = "") = SourceFile.kotlin("$name.kt", """
        package fixture
        import io.cratis.arc.validation.FluentModelValidator
        class $name : FluentModelValidator<Person>(Person::class.java) { init { $body }; $extra }
    """.trimIndent())
    private fun javaRules(name: String, body: String) = SourceFile.java("$name.java", """
        package fixture;
        import io.cratis.arc.validation.FluentModelValidator;
        public final class $name extends FluentModelValidator<JavaPerson> {
            public $name() { super(JavaPerson.class); $body }
        }
    """.trimIndent())
}
