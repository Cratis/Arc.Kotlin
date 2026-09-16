// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.sun.source.tree.ClassTree
import com.sun.source.tree.ExpressionStatementTree
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.LiteralTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.MethodInvocationTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.Tree
import com.sun.source.util.JavacTask
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.metadata.ValidationRuleDescriptor
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtUserType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

/** Embedded extraction experiment only: no production processor, DSL, packaging or enforcement claim. */
@OptIn(ExperimentalCompilerApi::class)
internal class ArcFluentValidationExtractionPrototypeCompilationTest {
    @Test
    fun `Kotlin and ordinary compiled Java share descriptors without constructing declarations`() {
        val kotlin = kotlinDeclaration("Rules", """
            // ruleFor("wrong").creditCard(); not source instructions
            ruleFor("name").maxLength(40).withMessage("Too \"long\"").notNull()
            ruleFor("name").maxLength(40)
        """.trimIndent())
        val java = javaDeclaration("Rules", """
            // class Rules { ruleFor("wrong").creditCard(); }
            ruleFor("name").maxLength(40).withMessage("Too \"long\"").notNull();
            ruleFor("name").maxLength(40);
        """.trimIndent())
        val probe = PrototypeProvider()
        val result = compile("positive", commonSources() + kotlin + java + listOf(
            SourceFile.kotlin("Decoy.kt", "package decoy; class Rules { val text = \"init { creditCard() }\" }")
        ), probe)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val expected = listOf(
            ValidationRuleDescriptor("notNull"),
            ValidationRuleDescriptor("maxLength", listOf(40)),
            ValidationRuleDescriptor("maxLength", listOf(40), "Too \"long\"")
        )
        assertEquals(setOf("kotlinfixture.Rules", "javafixture.Rules"), probe.descriptors.keys)
        probe.descriptors.values.forEach { assertEquals(mapOf("name" to expected), it) }
        assertEquals(mapOf("kotlinfixture.Rules" to "kotlinmodel.Person", "javafixture.Rules" to "prototype.Person"), probe.models)
        assertTrue(File(result.outputDirectory, "javafixture/Rules.class").isFile, "Java must actually compile, not just parse")
        assertTrue(File(result.outputDirectory, "kotlinfixture/Rules.class").isFile)
        val emitted = result.outputDirectory.parentFile.resolve("ksp/sources/resources/prototype-descriptors.txt")
        assertTrue(emitted.isFile, emitted.path)
        assertEquals(probe.render(), emitted.readText())
    }

    @Test
    fun `Kotlin unsupported whole declarations compile without processor then fail closed`() {
        proveNegatives("kotlin", kotlinCases())
    }

    @Test
    fun `Java unsupported whole declarations compile without processor then fail closed`() {
        proveNegatives("java", javaCases())
    }

    @Test
    fun `missing and malformed source fail closed for both parser paths`() {
        val sources = commonSources() + kotlinDeclaration("Rules", goodKotlin) + javaDeclaration("Rules", goodJava)
        for (fault in listOf("missing", "malformed")) {
            val probe = PrototypeProvider { if (fault == "missing") null else "class {" }
            val result = compile("source-$fault", sources, probe)
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            for (name in listOf("kotlinfixture.Rules", "javafixture.Rules")) {
                assertTrue(result.messages.contains("[PROTOTYPE] '$name': $fault source"), result.messages)
            }
            assertNoOutput(result, probe)
        }
    }

    private data class Negative(val name: String, val body: String, val reason: String, val members: String = "", val base: String? = null)

    private fun kotlinCases(): List<Negative> = listOf(
        Negative("Branch", "$goodKotlin; if (true) ruleFor(\"name\").notNull()", "only direct fluent chains"),
        Negative("Loop", "$goodKotlin; for (i in 1..2) ruleFor(\"name\").notNull()", "only direct fluent chains"),
        Negative("Local", "$goodKotlin; val alias = ruleFor(\"name\")", "only direct fluent chains"),
        Negative("Effect", "$goodKotlin; System.getProperty(\"user.home\")", "unsupported receiver"),
        Negative("Helper", "$goodKotlin; helper()", "only one init", "fun helper() = Unit"),
        Negative("Property", goodKotlin, "only one init", "val extra = 1"),
        Negative("ExtraInit", goodKotlin, "only one init", "init { System.getProperty(\"user.home\") }"),
        Negative("Extension", "ruleFor(\"name\").customRule()", "unsupported rule 'customRule'"),
        Negative("Secondary", goodKotlin, "plain class required", "constructor(unused: Int) : this()"),
        Negative("Lambda", "$goodKotlin; ruleFor(\"name\").also { it.notNull() }", "no lambdas or type arguments"),
        Negative("Computed", "ruleFor(\"name\").maxLength(20 + 20)", "literal arguments only"),
        Negative("External", "ruleFor(\"name\").maxLength(prototype.External.LIMIT)", "literal arguments only"),
        Negative("NegativeBound", "ruleFor(\"name\").maxLength(-1)", "literal arguments only"),
        Negative("Interpolation", "ruleFor(\"name\").notNull().withMessage(\"bad ${'$'}{prototype.External.LIMIT}\")", "literal strings only"),
        Negative("Concat", "ruleFor(\"name\").notNull().withMessage(\"bad\" + \"message\")", "literal arguments only"),
        Negative("MissingProperty", "ruleFor(\"absent\").notNull()", "unknown property 'absent'"),
        Negative("WrongType", "ruleFor(\"age\").maxLength(40)", "maxLength requires a string"),
        Negative("MessageFirst", "ruleFor(\"name\").withMessage(\"bad\").notNull()", "withMessage requires a preceding rule"),
        Negative("Contradiction", "ruleFor(\"longName\").maxLength(40)", "annotation merge rejected"),
        Negative("CreditCard", "ruleFor(\"name\").creditCard()", "unsupported client rule 'creditCard'"),
        Negative("Unknown", "ruleFor(\"name\").unknown()", "unsupported rule 'unknown'"),
        Negative("Receiver", "prototype.External.chain().notNull()", "unsupported receiver"),
        Negative("ThisReceiver", "this.ruleFor(\"name\").notNull()", "unsupported receiver"),
        Negative("Shadow", goodKotlin, "only one init", "fun ruleFor(value: Int) = prototype.External.chain()"),
        Negative("Indirect", goodKotlin, "direct exact ValidationDeclaration", base = "prototype.Indirect()"),
        Negative("Custom", goodKotlin, "direct exact ValidationDeclaration", base = "other.ValidationDeclaration<prototype.Person>()")
    )

    private fun javaCases(): List<Negative> = listOf(
        Negative("Branch", "$goodJava if (true) ruleFor(\"name\").notNull();", "only expression statements"),
        Negative("Loop", "$goodJava for (int i = 0; i < 2; i++) ruleFor(\"name\").notNull();", "only expression statements"),
        Negative("Local", "$goodJava var alias = ruleFor(\"name\");", "only expression statements"),
        Negative("Effect", "$goodJava System.getProperty(\"user.home\");", "unsupported receiver"),
        Negative("Helper", "$goodJava helper();", "only one public zero-arg constructor", "void helper() {}"),
        Negative("Field", goodJava, "only one public zero-arg constructor", "int extra = 1;"),
        Negative("Initializer", goodJava, "only one public zero-arg constructor", "{ System.getProperty(\"user.home\"); }"),
        Negative("Secondary", goodJava, "only one public zero-arg constructor", "public Secondary(int unused) { this(); }"),
        Negative("Lambda", "ruleFor(\"name\").apply(() -> {});", "literal arguments only"),
        Negative("Computed", "ruleFor(\"name\").maxLength(20 + 20);", "literal arguments only"),
        Negative("External", "ruleFor(\"name\").maxLength(prototype.External.LIMIT);", "literal arguments only"),
        Negative("NegativeBound", "ruleFor(\"name\").maxLength(-1);", "maxLength requires a nonnegative integer"),
        Negative("Concat", "ruleFor(\"name\").notNull().withMessage(\"bad\" + prototype.External.LIMIT);", "literal arguments only"),
        Negative("LiteralConcat", "ruleFor(\"name\").notNull().withMessage(\"bad\" + \"message\");", "literal arguments only"),
        Negative("MissingProperty", "ruleFor(\"absent\").notNull();", "unknown property 'absent'"),
        Negative("WrongType", "ruleFor(\"age\").maxLength(40);", "maxLength requires a string"),
        Negative("MessageFirst", "ruleFor(\"name\").withMessage(\"bad\").notNull();", "withMessage requires a preceding rule"),
        Negative("Contradiction", "ruleFor(\"longName\").maxLength(40);", "annotation merge rejected"),
        Negative("CreditCard", "ruleFor(\"name\").creditCard();", "unsupported client rule 'creditCard'"),
        Negative("Unknown", "ruleFor(\"name\").unknown();", "unsupported rule 'unknown'"),
        Negative("Receiver", "prototype.External.chain().notNull();", "unsupported receiver"),
        Negative("ThisReceiver", "this.ruleFor(\"name\").notNull();", "unsupported receiver"),
        Negative("Shadow", goodJava, "only one public zero-arg constructor", "Chain ruleFor(int value) { return prototype.External.chain(); }"),
        Negative("Indirect", goodJava, "direct exact ValidationDeclaration", base = "prototype.Indirect"),
        Negative("Custom", goodJava, "direct exact ValidationDeclaration", base = "other.ValidationDeclaration<prototype.Person>")
    )

    private fun proveNegatives(language: String, cases: List<Negative>) {
        val declarations = cases.map { case ->
            if (language == "kotlin") kotlinDeclaration(case.name, case.body, case.members, case.base)
            else javaDeclaration(case.name, case.body, case.members, case.base)
        }
        // A separately valid declaration must not escape as partial output when a sibling fails.
        val valid = if (language == "kotlin") kotlinDeclaration("ValidSibling", goodKotlin) else javaDeclaration("ValidSibling", goodJava)
        val sources = commonSources() + declarations + valid
        val baseline = compile("$language-negative-baseline", sources)
        assertEquals(KotlinCompilation.ExitCode.OK, baseline.exitCode, baseline.messages)
        cases.forEach {
            assertTrue(File(baseline.outputDirectory, "${language}fixture/${it.name}.class").isFile)
        }
        val probe = PrototypeProvider()
        val result = compile("$language-negative-processor", sources, probe)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        cases.forEach {
            assertTrue(result.messages.contains("[PROTOTYPE] '${language}fixture.${it.name}': ${it.reason}"), result.messages)
        }
        assertTrue(result.messages.contains("contradictory length bounds"), result.messages)
        assertNoOutput(result, probe)
        println("PROTOTYPE $language: ${cases.size} baseline-compiled negative declarations rejected with asserted reasons")
    }

    private fun assertNoOutput(result: JvmCompilationResult, probe: PrototypeProvider) {
        assertTrue(probe.descriptors.isEmpty(), "No partial descriptors may escape")
        assertFalse(result.outputDirectory.parentFile.resolve("ksp/sources/resources/prototype-descriptors.txt").exists())
    }

    private fun compile(label: String, sources: List<SourceFile>, probe: PrototypeProvider? = null): JvmCompilationResult {
        val root = System.getProperty("arc.prototype.evidence")?.let(::File)
            ?: File(System.getProperty("arc.ksp.projectDir"), "../../.ai-work/keep/epic-171-phase2-proof").canonicalFile
        root.mkdirs()
        val directory = Files.createTempDirectory(root.toPath(), "$label-").toFile()
        val output = ByteArrayOutputStream()
        val result = KotlinCompilation().apply {
            workingDir = directory
            this.sources = sources
            inheritClassPath = true
            jvmTarget = "17"
            if (probe != null) {
                useKsp2()
                symbolProcessorProviders = mutableListOf(probe)
                kspWithCompilation = true
            }
            messageOutputStream = output
        }.compile()
        File(directory, "compile.log").writeText(result.messages)
        println("PROTOTYPE $label: ${result.exitCode}; log=${directory.resolve("compile.log")}")
        return result
    }

    private fun kotlinDeclaration(name: String, body: String, members: String = "", base: String? = null): SourceFile =
        SourceFile.kotlin("$name.kt", """
            package kotlinfixture
            import prototype.ValidationDeclaration
            @prototype.Extract
            class $name${if (members.startsWith("constructor")) "()" else ""} : ${base ?: "ValidationDeclaration<kotlinmodel.Person>()"} {
                init { $body }
                $members
            }
        """.trimIndent())

    private fun javaDeclaration(name: String, body: String, members: String = "", base: String? = null): SourceFile =
        SourceFile.java("$name.java", """
            package javafixture;
            @prototype.Extract
            public final class $name extends ${base ?: "prototype.ValidationDeclaration<prototype.Person>"} {
                public $name() { $body }
                $members
            }
        """.trimIndent())

    private fun commonSources(): List<SourceFile> = listOf(
        SourceFile.kotlin("kotlinfixture/Extensions.kt", """
            package kotlinfixture
            fun prototype.ValidationDeclaration.Chain.customRule(): prototype.ValidationDeclaration.Chain = this
        """.trimIndent()),
        SourceFile.kotlin("kotlinmodel/Person.kt", """
            package kotlinmodel
            class Person {
                @field:jakarta.validation.constraints.Size(max=40) var name: String? = null
                @field:jakarta.validation.constraints.Size(min=50) var longName: String? = null
                var age: Int = 0
            }
        """.trimIndent()),
        SourceFile.java("prototype/Extract.java", "package prototype; public @interface Extract {}"),
        SourceFile.java("prototype/ValidationDeclaration.java", """
            package prototype;
            public abstract class ValidationDeclaration<T> {
                protected ValidationDeclaration() { throw new AssertionError("APPLICATION CONSTRUCTOR EXECUTED"); }
                protected final Chain ruleFor(String property) { throw new AssertionError("APPLICATION DSL EXECUTED"); }
                public static final class Chain {
                    public final Chain notNull() { throw new AssertionError(); }
                    public final Chain maxLength(int length) { throw new AssertionError(); }
                    public final Chain withMessage(String message) { throw new AssertionError(); }
                    public final Chain creditCard() { throw new AssertionError(); }
                    public final Chain unknown() { throw new AssertionError(); }
                    public final Chain apply(Runnable action) { throw new AssertionError(); }
                }
            }
        """.trimIndent()),
        SourceFile.java("prototype/Person.java", """
            package prototype;
            public final class Person {
                @jakarta.validation.constraints.Size(max=40) public String name;
                @jakarta.validation.constraints.Size(min=50) public String longName;
                public int age;
            }
        """.trimIndent()),
        SourceFile.java("prototype/External.java", """
            package prototype;
            public final class External {
                public static final int LIMIT = 40;
                public static ValidationDeclaration.Chain chain() { throw new AssertionError(); }
            }
        """.trimIndent()),
        SourceFile.java("prototype/Indirect.java", "package prototype; public class Indirect extends ValidationDeclaration<Person> {}"),
        SourceFile.java("other/ValidationDeclaration.java", """
            package other;
            public class ValidationDeclaration<T> extends prototype.ValidationDeclaration<T> {}
        """.trimIndent())
    )

    private companion object {
        const val goodKotlin = "ruleFor(\"name\").notNull()"
        const val goodJava = "ruleFor(\"name\").notNull();"
    }
}

private class PrototypeRejected(message: String) : RuntimeException(message)
private fun requirePrototype(condition: Boolean, reason: String) {
    if (!condition) throw PrototypeRejected(reason)
}

private data class PrototypeCall(val name: String, val arguments: List<Any>)

/** Marker selection and all property/type/annotation authority come from KSP, not source text. */
internal class PrototypeProvider(private val readSource: (File) -> String? = { it.takeIf(File::isFile)?.readText() }) : SymbolProcessorProvider {
    val descriptors = linkedMapOf<String, Map<String, List<ValidationRuleDescriptor>>>()
    val models = linkedMapOf<String, String>()
    fun render(): String = descriptors.toSortedMap().entries.joinToString("\n") { "${it.key}->${models.getValue(it.key)}=${it.value}" }

    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
        private var failed = false
        private val pending = linkedMapOf<String, Map<String, List<ValidationRuleDescriptor>>>()
        private var files = emptyList<com.google.devtools.ksp.symbol.KSFile>()
        private val identities = sortedSetOf<String>()
        override fun process(resolver: Resolver): List<KSAnnotated> {
            // Native KSP may run again after the real Arc processor emits invokers. Never keep
            // semantic symbols/files from an earlier round, including aggregation dependencies.
            files = resolver.getAllFiles().toList()
            pending.clear()
            models.clear()
            for (symbol in resolver.getSymbolsWithAnnotation("prototype.Extract")) {
                val declaration = symbol as? KSClassDeclaration ?: throw PrototypeRejected("class required")
                identities += declaration.qualifiedName!!.asString()
            }
            for (name in identities) {
                val declaration = resolver.getClassDeclarationByName(resolver.getKSNameFromString(name))
                    ?: throw PrototypeRejected("missing declaration '$name'")
                val identity = declaration.qualifiedName!!.asString()
                try {
                    pending[identity] = extract(declaration, environment)
                } catch (rejection: PrototypeRejected) {
                    failed = true
                    environment.logger.error("[PROTOTYPE] '$identity': ${rejection.message}; use only the restricted literal declaration grammar.", declaration)
                }
            }
            return emptyList()
        }

        private fun extract(declaration: KSClassDeclaration, environment: SymbolProcessorEnvironment): Map<String, List<ValidationRuleDescriptor>> {
            requirePrototype(declaration.parentDeclaration == null && declaration.getVisibility() == Visibility.PUBLIC &&
                Modifier.OPEN !in declaration.modifiers && Modifier.ABSTRACT !in declaration.modifiers &&
                Modifier.SEALED !in declaration.modifiers && declaration.typeParameters.isEmpty(), "public top-level final class required")
            val base = declaration.superTypes.singleOrNull()?.resolve()
            requirePrototype(base?.declaration?.qualifiedName?.asString() == "prototype.ValidationDeclaration", "direct exact ValidationDeclaration required")
            val modelType = base!!.arguments.singleOrNull()?.type?.resolve()
            val model = modelType?.declaration as? KSClassDeclaration
            requirePrototype(model != null && !modelType.isError && modelType.arguments.isEmpty() && !modelType.isMarkedNullable &&
                model.typeParameters.isEmpty() && model.classKind == com.google.devtools.ksp.symbol.ClassKind.CLASS &&
                Modifier.ABSTRACT !in model.modifiers, "one concrete model required")
            models[declaration.qualifiedName!!.asString()] = model!!.qualifiedName!!.asString()
            val file = declaration.containingFile?.filePath?.let(::File) ?: throw PrototypeRejected("missing source")
            val text = readSource(file) ?: throw PrototypeRejected("missing source")
            val chains = when (file.extension) {
                "kt" -> PrototypeKotlinParser.parse(text, declaration.packageName.asString(), declaration.simpleName.asString())
                "java" -> PrototypeJavaParser.parse(text, declaration.packageName.asString(), declaration.simpleName.asString())
                else -> throw PrototypeRejected("unsupported source language")
            }
            val rules = linkedMapOf<String, MutableList<ValidationRuleModel>>()
            for (chain in chains) {
                val root = chain.firstOrNull()
                requirePrototype(root?.name == "ruleFor" && root.arguments.size == 1 && root.arguments[0] is String, "literal ruleFor property required")
                val property = root!!.arguments[0] as String
                val member = model.getAllProperties().singleOrNull { it.simpleName.asString() == property }
                    ?: throw PrototypeRejected("unknown property '$property'")
                val memberType = member.type.resolve().declaration.qualifiedName?.asString()
                val chainRules = mutableListOf<ValidationRuleModel>()
                for (call in chain.drop(1)) {
                    when (call.name) {
                        "notNull" -> {
                            requirePrototype(call.arguments.isEmpty(), "notNull takes no arguments")
                            chainRules += ValidationRuleModel("notNull")
                        }
                        "maxLength" -> {
                            requirePrototype(memberType in setOf("kotlin.String", "java.lang.String"), "maxLength requires a string")
                            requirePrototype(call.arguments.size == 1 && call.arguments[0] is Int && (call.arguments[0] as Int) >= 0,
                                "maxLength requires a nonnegative integer")
                            chainRules += ValidationRuleModel("maxLength", call.arguments)
                        }
                        "withMessage" -> {
                            requirePrototype(chainRules.isNotEmpty() && chainRules.last().message == null, "withMessage requires a preceding rule")
                            requirePrototype(call.arguments.size == 1 && call.arguments[0] is String, "withMessage requires a literal string")
                            chainRules[chainRules.lastIndex] = chainRules.last().copy(message = call.arguments[0] as String)
                        }
                        "creditCard" -> throw PrototypeRejected("unsupported client rule 'creditCard'")
                        else -> throw PrototypeRejected("unsupported rule '${call.name}'")
                    }
                }
                requirePrototype(chainRules.isNotEmpty(), "at least one rule required")
                rules.getOrPut(property) { mutableListOf() }.addAll(chainRules)
            }
            requirePrototype(rules.isNotEmpty(), "at least one rule required")
            val extractor = ValidationMetadataExtractor(ArcDiagnosticReporter(environment.logger))
            return rules.toSortedMap().mapValues { (name, inherited) ->
                val member = model.getAllProperties().single { it.simpleName.asString() == name }
                val type = member.type.resolve()
                val typeName = type.declaration.qualifiedName!!.asString()
                val merged = extractor.extract(listOf(member), TypeShape(typeName, TypeShapeDescriptor.value(typeName, type.isMarkedNullable), type),
                    "${model.qualifiedName!!.asString()}.$name", member, inheritedRules = inherited)
                    ?: throw PrototypeRejected("annotation merge rejected")
                requirePrototype(merged.rules.none { it.ruleName == "creditCard" }, "unsupported client rule 'creditCard'")
                merged.rules.map { ValidationRuleDescriptor(it.ruleName, it.arguments, it.message) }
            }
        }

        override fun finish() {
            if (failed || pending.isEmpty()) return
            descriptors.putAll(pending)
            environment.codeGenerator.createNewFile(Dependencies(true, *files.toTypedArray()), "", "prototype-descriptors", "txt")
                .bufferedWriter().use { it.write(render()) }
        }
    }
}

/** PSI parses the entire file, selects the exact KSP identity, then allowlists every declaration/body node. */
@OptIn(org.jetbrains.kotlin.CoreEnvironmentDeprecation::class, CompilerConfiguration.Internals::class, ExperimentalCompilerApi::class)
private object PrototypeKotlinParser {
    fun parse(source: String, packageName: String, name: String): List<List<PrototypeCall>> {
        val disposable = Disposer.newDisposable()
        try {
            val configuration = CompilerConfiguration().apply {
                // Required by Kotlin 2.4.20, without loading application compiler plugins.
                extensionsStorage = CompilerPluginRegistrar.ExtensionStorage()
            }
            val environment = KotlinCoreEnvironment.createForProduction(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
            val file = KtPsiFactory(environment.project).createFile(source)
            requirePrototype(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) == null, "malformed source")
            requirePrototype(file.packageFqName.asString() == packageName, "source identity mismatch")
            val declaration = file.declarations.filterIsInstance<KtClass>().singleOrNull { it.name == name }
                ?: throw PrototypeRejected("source identity mismatch")
            requirePrototype(declaration.primaryConstructor == null && declaration.typeParameters.isEmpty() &&
                !declaration.isInterface() && !declaration.isEnum() && !declaration.isAnnotation() &&
                declaration.modifierList?.node?.getChildren(null)?.none {
                    it.elementType in setOf(KtTokens.DATA_KEYWORD, KtTokens.INNER_KEYWORD, KtTokens.VALUE_KEYWORD)
                } != false, "plain class required")
            val superCall = declaration.superTypeListEntries.singleOrNull() as? KtSuperTypeCallEntry
            val userType = superCall?.typeReference?.typeElement as? KtUserType
            requirePrototype(superCall != null && superCall.valueArguments.isEmpty() && userType?.referencedName == "ValidationDeclaration",
                "direct literal base constructor required")
            val init = declaration.declarations.singleOrNull() as? KtAnonymousInitializer
                ?: throw PrototypeRejected("only one init and no other members allowed")
            val body = init.body as? org.jetbrains.kotlin.psi.KtBlockExpression ?: throw PrototypeRejected("init block required")
            return body.statements.map(::chain)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun chain(expression: KtExpression): List<PrototypeCall> = when (expression) {
        is KtDotQualifiedExpression -> {
            val call = expression.selectorExpression as? KtCallExpression ?: throw PrototypeRejected("unsupported receiver")
            chain(expression.receiverExpression) + call(call)
        }
        is KtCallExpression -> listOf(call(expression))
        is KtNameReferenceExpression, is org.jetbrains.kotlin.psi.KtThisExpression -> throw PrototypeRejected("unsupported receiver")
        else -> throw PrototypeRejected("only direct fluent chains")
    }

    private fun call(expression: KtCallExpression): PrototypeCall {
        requirePrototype(expression.lambdaArguments.isEmpty() && expression.typeArguments.isEmpty(), "no lambdas or type arguments")
        val name = (expression.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            ?: throw PrototypeRejected("unsupported receiver")
        return PrototypeCall(name, expression.valueArguments.map {
            requirePrototype(!it.isNamed() && it.getSpreadElement() == null, "positional literal arguments only")
            literal(it.getArgumentExpression())
        })
    }

    private fun literal(expression: KtExpression?): Any = when (expression) {
        is KtStringTemplateExpression -> {
            requirePrototype(expression.entries.all { it is KtLiteralStringTemplateEntry || it is KtEscapeStringTemplateEntry }, "literal strings only")
            expression.entries.joinToString("") { if (it is KtEscapeStringTemplateEntry) it.unescapedValue else it.text }
        }
        is KtConstantExpression -> expression.text.takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()
            ?: throw PrototypeRejected("literal nonnegative integers only")
        else -> throw PrototypeRejected("literal arguments only")
    }
}

/** Only JavacTask.parse is used here: no attribution, analysis, class loading or execution. */
private object PrototypeJavaParser {
    fun parse(source: String, packageName: String, name: String): List<List<PrototypeCall>> {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: throw PrototypeRejected("JDK parser unavailable")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val input = object : SimpleJavaFileObject(URI.create("string:///$name.java"), JavaFileObject.Kind.SOURCE) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
        }
        compiler.getStandardFileManager(diagnostics, null, null).use { manager ->
            // javac normally folds even literal string concatenation during parse. Keep the BinaryTree:
            // LiteralConcat is a baseline-compiled mutation proving this JDK-specific option matters.
            val task = compiler.getTask(null, manager, diagnostics, listOf("-proc:none", "-XDallowStringFolding=false"), null, listOf(input)) as JavacTask
            val unit = task.parse().single()
            requirePrototype(diagnostics.diagnostics.none { it.kind == Diagnostic.Kind.ERROR }, "malformed source")
            requirePrototype(unit.packageName.toString() == packageName, "source identity mismatch")
            val declaration = unit.typeDecls.filterIsInstance<ClassTree>().singleOrNull { it.simpleName.toString() == name }
                ?: throw PrototypeRejected("source identity mismatch")
            requirePrototype(declaration.kind == Tree.Kind.CLASS && declaration.typeParameters.isEmpty() && declaration.implementsClause.isEmpty() &&
                declaration.modifiers.flags == setOf(javax.lang.model.element.Modifier.PUBLIC, javax.lang.model.element.Modifier.FINAL), "plain final class required")
            val constructor = declaration.members.singleOrNull() as? MethodTree
            requirePrototype(constructor != null && constructor.name.toString() == "<init>" && constructor.parameters.isEmpty() &&
                constructor.typeParameters.isEmpty() && constructor.throws.isEmpty() && constructor.receiverParameter == null &&
                constructor.modifiers.annotations.isEmpty() && constructor.modifiers.flags == setOf(javax.lang.model.element.Modifier.PUBLIC),
                "only one public zero-arg constructor and no other members allowed")
            return constructor!!.body.statements.map {
                val statement = it as? ExpressionStatementTree ?: throw PrototypeRejected("only expression statements allowed")
                chain(statement.expression)
            }
        }
    }

    private fun chain(tree: Tree): List<PrototypeCall> {
        val invocation = tree as? MethodInvocationTree ?: throw PrototypeRejected("unsupported receiver")
        requirePrototype(invocation.typeArguments.isEmpty(), "no type arguments")
        val (prefix, name) = when (val select = invocation.methodSelect) {
            is IdentifierTree -> emptyList<PrototypeCall>() to select.name.toString()
            is MemberSelectTree -> chain(select.expression) to select.identifier.toString()
            else -> throw PrototypeRejected("unsupported receiver")
        }
        return prefix + PrototypeCall(name, invocation.arguments.map {
            val literal = it as? LiteralTree ?: throw PrototypeRejected("literal arguments only")
            val value = literal.value
            requirePrototype(value is String || value is Int, "literal string or integer required")
            value
        })
    }
}
