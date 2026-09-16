// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.sun.source.tree.ClassTree
import com.sun.source.tree.ExpressionStatementTree
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.LiteralTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.MethodInvocationTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.ParameterizedTypeTree
import com.sun.source.tree.Tree
import com.sun.source.tree.UnaryTree
import com.sun.source.util.JavacTask
import java.net.URI
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtUserType

internal class FluentSourceRejected(message: String) : IllegalArgumentException(message)
internal data class FluentCall(val name: String, val arguments: List<Any>)

/** Whole-source syntax inspection only. Semantic identities and member types are resolved by KSP separately. */
internal object FluentSourceParser {
    fun toolchain(): String {
        val loader = javaClass.classLoader
        val parser = Class.forName("org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment", false, loader)
        val version = Class.forName("org.jetbrains.kotlin.config.KotlinCompilerVersion", true, loader).getField("VERSION").get(null)
        require(version == "2.4.20" && Runtime.version().feature() == 17) {
            "shared fluent extraction requires kotlin-compiler-embeddable 2.4.20 and JDK17 (found $version / ${Runtime.version()})"
        }
        val parentParser = try { Class.forName(parser.name, false, loader.parent); true } catch (_: ClassNotFoundException) { false }
        return "parser=${parser.protectionDomain.codeSource.location}; version=$version; sameLoader=${parser.classLoader === loader}; parentParser=$parentParser; javac=${JavacTask::class.java.module.name}"
    }

    @OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class, CompilerConfiguration.Internals::class)
    private fun parserConfiguration(): CompilerConfiguration = CompilerConfiguration().apply {
        // Kotlin 2.4.20 requires storage even for syntax-only environments. No application plugins run.
        extensionsStorage = CompilerPluginRegistrar.ExtensionStorage()
    }

    private fun demand(condition: Boolean, message: String) {
        if (!condition) throw FluentSourceRejected(message)
    }

    @OptIn(org.jetbrains.kotlin.CoreEnvironmentDeprecation::class, CompilerConfiguration.Internals::class)
    fun kotlin(source: String, packageName: String, name: String, model: String,
        hasJavaDeclaration: (String) -> Boolean = { false }): List<List<FluentCall>> {
        val disposable = Disposer.newDisposable()
        try {
            val environment = KotlinCoreEnvironment.createForProduction(disposable, parserConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES)
            val file = KtPsiFactory(environment.project).createFile(source)
            demand(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) == null, "malformed Kotlin source")
            demand(file.packageFqName.asString() == packageName, "source package does not match KSP identity")
            val declaration = file.declarations.filterIsInstance<KtClass>().singleOrNull { it.name == name }
                ?: throw FluentSourceRejected("source declaration does not match KSP identity")
            demand(declaration.typeParameters.isEmpty() && !declaration.isInterface() && !declaration.isEnum() &&
                !declaration.isAnnotation() && listOf(KtTokens.DATA_KEYWORD, KtTokens.INNER_KEYWORD, KtTokens.VALUE_KEYWORD,
                    KtTokens.EXPECT_KEYWORD, KtTokens.ACTUAL_KEYWORD).none(declaration::hasModifier), "use a plain final class")
            val constructor = declaration.primaryConstructor
            demand(constructor == null || constructor.valueParameters.isEmpty() && constructor.annotationEntries.isEmpty() &&
                !constructor.hasModifier(KtTokens.PRIVATE_KEYWORD) && !constructor.hasModifier(KtTokens.PROTECTED_KEYWORD) &&
                !constructor.hasModifier(KtTokens.INTERNAL_KEYWORD), "use a public no-argument constructor")
            val base = declaration.superTypeListEntries.singleOrNull() as? KtSuperTypeCallEntry
            val userType = base?.typeReference?.typeElement as? KtUserType
            demand(userType?.referencedName == "FluentModelValidator" && base.valueArguments.size == 1,
                "call FluentModelValidator with exactly the literal model class")
            val argument = base!!.valueArguments.single()
            demand(!argument.isNamed() && argument.getSpreadElement() == null, "use a positional literal model class")
            val java = argument.getArgumentExpression() as? KtDotQualifiedExpression
            val literal = java?.receiverExpression as? KtClassLiteralExpression
            demand((java?.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() == "java" && literal != null,
                "base constructor requires Model::class.java")
            demand(!hasJavaDeclaration(packageName) && file.importDirectives.none { directive ->
                val imported = directive.importedFqName?.asString().orEmpty()
                if (directive.isAllUnder) imported != "kotlin.jvm" && hasJavaDeclaration(imported)
                else (directive.aliasName ?: imported.substringAfterLast('.')) == "java" && imported != "kotlin.jvm.java"
            }, "class mapping may be shadowed; remove or rename the nonstandard java extension/import and use the standard Model::class.java mapping")
            val modelText = literal!!.receiverExpression?.text.orEmpty()
            val imports = file.importDirectives.filter { it.aliasName == null && !it.isAllUnder }.mapNotNull { it.importedFqName?.asString() }
            demand(modelText == model || modelText == model.substringAfterLast('.') &&
                (model.substringBeforeLast('.', "") == packageName || model in imports), "base class literal must match '$model' without an alias")
            val initializer = declaration.declarations.singleOrNull() as? KtAnonymousInitializer
                ?: throw FluentSourceRejected("allow only one init block and no other members")
            val body = initializer.body as? KtBlockExpression ?: throw FluentSourceRejected("use an init block")
            return body.statements.map(::kotlinChain)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    /** Source proof only: a backing field alone says nothing about its accessor's value. */
    @OptIn(org.jetbrains.kotlin.CoreEnvironmentDeprecation::class, CompilerConfiguration.Internals::class)
    fun kotlinWireMembers(source: String, packageName: String, name: String): Set<String> {
        val disposable = Disposer.newDisposable()
        try {
            val environment = KotlinCoreEnvironment.createForProduction(disposable, parserConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES)
            val file = KtPsiFactory(environment.project).createFile(source)
            demand(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) == null && file.packageFqName.asString() == packageName,
                "model source identity or syntax is unavailable")
            val declaration = file.declarations.filterIsInstance<KtClass>().singleOrNull { it.name == name }
                ?: throw FluentSourceRejected("model source declaration is unavailable; use a top-level wire model")
            val constructor = declaration.primaryConstructorParameters.filter { it.hasValOrVar() }.mapNotNull { it.name }
            val properties = declaration.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtProperty>().filter { property ->
                !property.hasDelegate() && property.initializer != null &&
                    (property.getter == null || property.getter?.bodyExpression == null ||
                        property.getter?.bodyExpression?.let { body ->
                            if (body is KtBlockExpression) body.statements.singleOrNull()?.let {
                                (it as? org.jetbrains.kotlin.psi.KtReturnExpression)?.returnedExpression?.text == "field"
                            } == true else body.text == "field"
                        } == true) &&
                    (property.setter == null || property.setter?.bodyExpression == null)
            }.mapNotNull { it.name }
            return (constructor + properties).toSet()
        } finally { Disposer.dispose(disposable) }
    }

    private fun kotlinChain(expression: KtExpression): List<FluentCall> = when (expression) {
        is KtDotQualifiedExpression -> kotlinChain(expression.receiverExpression) + kotlinCall(
            expression.selectorExpression as? KtCallExpression ?: throw FluentSourceRejected("use a direct fluent method call"))
        is KtCallExpression -> listOf(kotlinCall(expression))
        else -> throw FluentSourceRejected("allow only direct fluent chains; no conditions, helpers, aliases or side effects")
    }

    private fun kotlinCall(expression: KtCallExpression): FluentCall {
        demand(expression.lambdaArguments.isEmpty() && expression.typeArguments.isEmpty(), "fluent calls cannot use lambdas or type arguments")
        val name = (expression.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            ?: throw FluentSourceRejected("use an unqualified fluent method name")
        return FluentCall(name, expression.valueArguments.map {
            demand(!it.isNamed() && it.getSpreadElement() == null, "'$name' requires positional literal arguments")
            kotlinLiteral(it.getArgumentExpression())
        })
    }

    private fun kotlinLiteral(expression: KtExpression?): Any = when (expression) {
        is KtStringTemplateExpression -> {
            demand(expression.entries.all { it is KtLiteralStringTemplateEntry || it is KtEscapeStringTemplateEntry }, "use literal strings without interpolation")
            expression.entries.joinToString("") { if (it is KtEscapeStringTemplateEntry) it.unescapedValue else it.text }
        }
        is KtConstantExpression -> number(expression.text)
        is KtPrefixExpression -> {
            demand(expression.operationToken == KtTokens.MINUS && expression.baseExpression is KtConstantExpression, "use a literal numeric bound")
            number("-" + expression.baseExpression!!.text)
        }
        else -> throw FluentSourceRejected("use literal arguments, not computed expressions or references")
    }

    private fun number(text: String): Number {
        demand(text.matches(Regex("-?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?[fFL]?")), "use a decimal numeric literal")
        return when {
            text.endsWith('L') -> text.dropLast(1).toLongOrNull()
            text.endsWith('f', true) -> text.dropLast(1).toFloatOrNull()
            text.contains('.') || text.contains('e', true) -> text.toDoubleOrNull()
            else -> text.toIntOrNull() ?: text.toLongOrNull()
        } ?: throw FluentSourceRejected("numeric literal is outside the supported range")
    }

    /** JDK17 parse only, never attribution, compilation, class loading or application execution. */
    fun java(source: String, packageName: String, name: String, model: String): List<List<FluentCall>> {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: throw FluentSourceRejected("use JDK17 with the jdk.compiler parser")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val input = object : SimpleJavaFileObject(URI.create("string:///$name.java"), JavaFileObject.Kind.SOURCE) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
        }
        compiler.getStandardFileManager(diagnostics, null, null).use { manager ->
            val task = compiler.getTask(null, manager, diagnostics, listOf("-proc:none", "-XDallowStringFolding=false"), null, listOf(input)) as JavacTask
            val unit = task.parse().single()
            demand(diagnostics.diagnostics.none { it.kind == Diagnostic.Kind.ERROR }, "malformed Java source")
            demand(unit.packageName?.toString().orEmpty() == packageName, "source package does not match KSP identity")
            val declaration = unit.typeDecls.filterIsInstance<ClassTree>().singleOrNull { it.simpleName.toString() == name }
                ?: throw FluentSourceRejected("source declaration does not match KSP identity")
            demand(declaration.kind == Tree.Kind.CLASS && declaration.typeParameters.isEmpty() && declaration.implementsClause.isEmpty() &&
                declaration.modifiers.flags == setOf(javax.lang.model.element.Modifier.PUBLIC, javax.lang.model.element.Modifier.FINAL),
                "use a public final top-level directly typed class")
            val base = declaration.extendsClause as? ParameterizedTypeTree
            demand(base?.type?.toString()?.substringAfterLast('.') == "FluentModelValidator" && base.typeArguments.size == 1,
                "extend FluentModelValidator<Model> directly")
            val constructor = declaration.members.singleOrNull() as? MethodTree
            demand(constructor != null && constructor.name.toString() == "<init>" && constructor.parameters.isEmpty() &&
                constructor.typeParameters.isEmpty() && constructor.throws.isEmpty() && constructor.receiverParameter == null &&
                constructor.modifiers.annotations.isEmpty() && constructor.modifiers.flags == setOf(javax.lang.model.element.Modifier.PUBLIC),
                "allow only one public no-argument constructor and no other members")
            val statements = constructor!!.body?.statements ?: throw FluentSourceRejected("use a constructor body")
            val call = (statements.firstOrNull() as? ExpressionStatementTree)?.expression as? MethodInvocationTree
            val literal = call?.arguments?.singleOrNull() as? MemberSelectTree
            demand((call?.methodSelect as? IdentifierTree)?.name?.toString() == "super" && literal?.identifier?.toString() == "class",
                "first statement must be super(Model.class)")
            val modelText = literal!!.expression.toString()
            val imports = unit.imports.filterNot { it.isStatic }.map { it.qualifiedIdentifier.toString() }
            demand(modelText == model || modelText == model.substringAfterLast('.') &&
                (model.substringBeforeLast('.', "") == packageName || model in imports), "base class literal must match '$model'")
            return statements.drop(1).map {
                javaChain((it as? ExpressionStatementTree)?.expression ?: throw FluentSourceRejected(
                    "allow only direct fluent expression statements; no conditions, helpers, aliases or side effects"))
            }
        }
    }

    /** KSP omits source record components on some toolchains. Parse their declarations, never infer a getter body. */
    fun javaRecordTypes(source: String, packageName: String, name: String, resolve: (String, List<String>) -> String): Map<String, String>? {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: throw FluentSourceRejected("use JDK17 with jdk.compiler")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val input = object : SimpleJavaFileObject(URI.create("string:///$name.java"), JavaFileObject.Kind.SOURCE) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
        }
        compiler.getStandardFileManager(diagnostics, null, null).use { manager ->
            val task = compiler.getTask(null, manager, diagnostics, listOf("-proc:none", "-XDallowStringFolding=false"), null, listOf(input)) as JavacTask
            val unit = task.parse().single()
            demand(diagnostics.diagnostics.none { it.kind == Diagnostic.Kind.ERROR }, "malformed Java model source")
            demand(unit.packageName?.toString().orEmpty() == packageName, "model source identity mismatch")
            val record = unit.typeDecls.filterIsInstance<ClassTree>().singleOrNull { it.simpleName.toString() == name && it.kind == Tree.Kind.RECORD } ?: return null
            val imports = unit.imports.filterNot { it.isStatic }.map { it.qualifiedIdentifier.toString() }
            fun type(tree: Tree): String = when (tree) {
                is com.sun.source.tree.PrimitiveTypeTree -> tree.primitiveTypeKind.name.lowercase()
                is com.sun.source.tree.ArrayTypeTree -> {
                    val element = type(tree.type)
                    "[" + (mapOf("boolean" to "Z", "byte" to "B", "char" to "C", "short" to "S", "int" to "I", "long" to "J", "float" to "F", "double" to "D")[element]
                        ?: if (element.startsWith('[')) element else "L$element;")
                }
                is com.sun.source.tree.AnnotatedTypeTree -> type(tree.underlyingType)
                is ParameterizedTypeTree -> type(tree.type)
                is IdentifierTree, is MemberSelectTree -> resolve(tree.toString(), imports)
                else -> throw FluentSourceRejected("record component has unsupported type syntax")
            }
            return record.members.filterIsInstance<com.sun.source.tree.VariableTree>()
                .filter { javax.lang.model.element.Modifier.STATIC !in it.modifiers.flags }
                .filterNot { field -> field.modifiers.annotations.any { it.annotationType.toString().substringAfterLast('.') == "JsonIgnore" } }
                .associate { field -> field.name.toString() to type(field.type) }
        }
    }

    /** Default record components/public fields, or explicit identity accessors only; never infer from names. */
    fun javaWireMembers(source: String, packageName: String, name: String): Set<String> {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: throw FluentSourceRejected("use JDK17 with jdk.compiler")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val input = object : SimpleJavaFileObject(URI.create("string:///$name.java"), JavaFileObject.Kind.SOURCE) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
        }
        compiler.getStandardFileManager(diagnostics, null, null).use { manager ->
            val task = compiler.getTask(null, manager, diagnostics, listOf("-proc:none"), null, listOf(input)) as JavacTask
            val unit = task.parse().single()
            demand(diagnostics.diagnostics.none { it.kind == Diagnostic.Kind.ERROR } && unit.packageName?.toString().orEmpty() == packageName,
                "model source identity or syntax is unavailable")
            val declaration = unit.typeDecls.filterIsInstance<ClassTree>().singleOrNull { it.simpleName.toString() == name }
                ?: throw FluentSourceRejected("model source declaration is unavailable; use a top-level wire model")
            val methods = declaration.members.filterIsInstance<MethodTree>()
            return declaration.members.filterIsInstance<com.sun.source.tree.VariableTree>().filter { field ->
                val modifiers = field.modifiers.flags
                javax.lang.model.element.Modifier.STATIC !in modifiers &&
                    (declaration.kind == Tree.Kind.RECORD || javax.lang.model.element.Modifier.PUBLIC in modifiers) &&
                    methods.filter { method -> method.parameters.isEmpty() && method.name.toString() in setOf(
                        field.name.toString(), "get" + field.name.toString().replaceFirstChar(Char::uppercaseChar),
                        "is" + field.name.toString().replaceFirstChar(Char::uppercaseChar)) }.all { method ->
                        val returned = (method.body?.statements?.singleOrNull() as? com.sun.source.tree.ReturnTree)?.expression
                        returned is IdentifierTree && returned.name.toString() == field.name.toString() ||
                            returned is MemberSelectTree && returned.expression.toString() == "this" && returned.identifier.toString() == field.name.toString()
                    }
            }.map { it.name.toString() }.toSet()
        }
    }

    private fun javaChain(tree: Tree): List<FluentCall> {
        val call = tree as? MethodInvocationTree ?: throw FluentSourceRejected("use direct fluent method calls")
        demand(call.typeArguments.isEmpty(), "fluent calls cannot use type arguments")
        val (prefix, name) = when (val select = call.methodSelect) {
            is IdentifierTree -> emptyList<FluentCall>() to select.name.toString()
            is MemberSelectTree -> javaChain(select.expression) to select.identifier.toString()
            else -> throw FluentSourceRejected("use an unqualified fluent method name")
        }
        return prefix + FluentCall(name, call.arguments.map(::javaLiteral))
    }

    private fun javaLiteral(tree: Tree): Any {
        if (tree is UnaryTree && tree.kind == Tree.Kind.UNARY_MINUS) {
            val value = (tree.expression as? LiteralTree)?.value as? Number ?: throw FluentSourceRejected("use a literal numeric bound")
            return when (value) { is Int -> -value; is Long -> -value; is Float -> -value; is Double -> -value; else -> throw FluentSourceRejected("unsupported number") }
        }
        val value = (tree as? LiteralTree)?.value
        demand(value is String || value is Int || value is Long || value is Float || value is Double, "use literal strings or numeric bounds, not expressions or references")
        return value!!
    }
}
