// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.DefaultCommandValidationFilter
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.metadata.ValidationRuleDescriptor
import io.cratis.arc.queries.DefaultQueryValidationFilter
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.results.ValidationResult
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IgnoreValidationTest {
    @Test
    fun `all Kotlin use sites are resolved without reading ignored throwing getters`() : Unit = runBlocking {
        val input = UseSites()
        val rules = UseSiteRules()
        assertTrue(ValidationMemberPolicy.isIgnored(UseSites::class.java, "property"))
        assertTrue(ValidationMemberPolicy.isIgnored(UseSites::class.java, "field"))
        assertTrue(ValidationMemberPolicy.isIgnored(UseSites::class.java, "getter"))
        assertFalse(ValidationMemberPolicy.isIgnored(UseSites::class.java, "sibling"))
        assertFalse(ValidationMemberPolicy.isIgnored(UseSites::class.java, "absent"))
        assertEquals(listOf("sibling"), rules.validate(input).flatMap { it.members })
        assertEquals(listOf("sibling"), DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(rules))
            .execute(command(input)).validationResults.flatMap { it.members })
        assertEquals(0, input.reads)
    }

    @Test
    fun `synthetic Kotlin getter annotation gates metadata direct fluent command and supplied query reads`() : Unit = runBlocking {
        val input = SyntheticInput()
        assertTrue(SyntheticInput::class.java.getDeclaredMethod("getIgnored").isSynthetic)
        assertTrue(ValidationMemberPolicy.isIgnored(SyntheticInput::class.java, "ignored"))
        assertFalse(ValidationMemberPolicy.isIgnored(SyntheticInput::class.java, "sibling"))
        val rules = SyntheticRules()
        val declaration = rules.rules
        assertEquals(listOf("sibling"), rules.validate(input).flatMap { it.members })
        assertEquals(listOf("sibling"), DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(rules))
            .execute(command(input)).validationResults.flatMap { it.members })
        assertEquals(listOf("arg.sibling"), DefaultQueryValidationFilter(emptyList(), emptyList(), listOf(rules))
            .execute(query(mapOf("arg" to input))).validationResults.flatMap { it.members })
        assertEquals(0, input.reads)
        assertSame(declaration, rules.rules)
        assertEquals(listOf("ignored", "sibling"), declaration.map { it.member })
    }

    @Test
    fun `concurrent metadata lookup and frozen validator reuse retain snapshots and skip reads`() {
        val rules = SyntheticRules()
        val frozen = rules.rules
        val input = SyntheticInput()
        val executor = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        try {
            val futures = (1..16).map {
                executor.submit(Callable {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    repeat(20) {
                        // This class has not been inspected by ruleFor: exercise concurrent cache creation too.
                        assertTrue(ValidationMemberPolicy.isIgnored(ConcurrentMetadataOwner::class.java, "ignored"))
                        assertTrue(ValidationMemberPolicy.isIgnored(SyntheticInput::class.java, "ignored"))
                        assertEquals(listOf("sibling"), rules.validate(input).flatMap { it.members })
                        assertSame(frozen, rules.rules)
                        FluentValidatorRegistration(rules, SyntheticInput::class.java, frozen)
                    }
                    Unit
                })
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(0, input.reads)
            assertSame(frozen, rules.rules)
            assertThrows(IllegalStateException::class.java) { rules.escaped.withMessage("mutated") }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `ignored declarations retain compiler fingerprint canonical checks and freeze`() {
        val rules = UseSiteRules()
        val expected = listOf("field", "getter", "property", "sibling").map {
            FluentValidationMember(it, String::class.java, listOf(ValidationRuleDescriptor("notEmpty")))
        }
        assertEquals(expected, rules.rules)
        val registration = FluentValidatorRegistration(rules, UseSites::class.java, expected)
        assertEquals(listOf("sibling"), rules.validate(UseSites()).flatMap { it.members })
        assertSame(rules.rules, rules.rules)
        assertEquals(expected, registration.expectedRules)
        assertEquals(expected, rules.rules)
        assertThrows(IllegalArgumentException::class.java) {
            FluentValidatorRegistration(rules, UseSites::class.java, expected.filter { it.member == "sibling" })
        }
        assertThrows(IllegalStateException::class.java) { rules.more() }
        assertThrows(IllegalStateException::class.java) { rules.escaped.maxLength(5) }
        val contradictory = object : FluentModelValidator<UseSites>(UseSites::class.java) {
            init { ruleFor("getter").minLength(3).maxLength(1) }
        }
        assertThrows(IllegalArgumentException::class.java) { contradictory.rules }
    }

    @Test
    fun `ignored container edges skip iteration and child validators while active aliases remain`() : Unit = runBlocking {
        val paths = mutableListOf<String>()
        val child = Child()
        val root = Containers(child)
        val rule = rule(Child::class.java) { _, context -> paths.add(context.memberPath); listOf(ValidationResult.error("child")) }
        val commandFilter = DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(rule))
        assertEquals(listOf("zActive[0]"), commandFilter.execute(command(root)).validationResults.flatMap { it.members })
        assertEquals(listOf("zActive[0]"), paths)
        paths.clear()
        val queryFilter = DefaultQueryValidationFilter(emptyList(), emptyList(), listOf(rule))
        assertEquals(listOf("arg.zActive[0]"), queryFilter.execute(query(mapOf("arg" to root))).validationResults.flatMap { it.members })
        assertEquals(listOf("arg.zActive[0]"), paths)
        paths.clear()
        assertEquals(listOf(""), commandFilter.execute(command(child)).validationResults.map { it.members.joinToString() })
        assertEquals(listOf(""), paths)
    }

    @Test
    fun `ignored fluent container rules do not inspect size or iterate`() : Unit = runBlocking {
        val rules = object : FluentModelValidator<IgnoredSize>(IgnoredSize::class.java) {
            init { ruleFor("items").notEmpty().minLength(2); ruleFor("missing").notNull(); ruleFor("sibling").notEmpty() }
        }
        val input = IgnoredSize()
        assertEquals(listOf("sibling"), rules.validate(input).flatMap { it.members })
        assertEquals(listOf("sibling"), DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(rules))
            .execute(command(input)).validationResults.flatMap { it.members })
        assertEquals(3, rules.rules.size)
    }

    @Test
    fun `cycles and shared references still terminate without visiting an ignored edge`() : Unit = runBlocking {
        val paths = mutableListOf<String>()
        val root = Cycle()
        val shared = Cycle()
        root.aIgnored = shared
        root.bActive = shared
        root.cActive = shared
        shared.bActive = root
        val filter = DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(
            rule(Cycle::class.java) { _, context -> paths.add(context.memberPath); emptyList() }
        ))
        assertTrue(filter.execute(command(root)).isSuccess)
        assertEquals(listOf("", "bActive"), paths)
    }

    @Test
    fun `owner imperative validation remains active and its feedback is not filtered`() : Unit = runBlocking {
        val owner = UseSites()
        val ownerRule = rule(UseSites::class.java) { model, _ ->
            assertSame(owner, model)
            listOf(ValidationResult.error("whole owner", listOf("getter")))
        }
        val feedback = DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(ownerRule, UseSiteRules()))
            .execute(command(owner)).validationResults
        assertEquals(listOf("whole owner", "'sibling' must not be empty."), feedback.map { it.message })
        assertEquals(listOf("getter", "sibling"), feedback.flatMap { it.members })
        assertEquals(0, owner.reads)
    }

    @Test
    fun `concept ignores are preaccess and existing concept only exclusions stay independent`() : Unit = runBlocking {
        var concepts = 0
        var models = 0
        val concept = object : ConceptValidator<Code> {
            override val conceptType = Code::class.java
            override fun validate(concept: Code): List<ValidationResult> {
                concepts++
                return listOf(ValidationResult.error("concept"))
            }
        }
        val model = rule(Code::class.java) { _, _ -> models++; listOf(ValidationResult.error("model")) }
        val code = Code("x")
        val input = Concepts(code)
        val filter = DefaultCommandValidationFilter(emptyList(), listOf(concept), listOf(model),
            listOf(ConceptValidationExclusion(Concepts::class.java, "bConceptOnly")))
        val feedback = filter.execute(command(input)).validationResults
        assertEquals(listOf("bConceptOnly", "cActive"), feedback.flatMap { it.members })
        assertEquals(listOf("model", "concept"), feedback.map { it.message })
        assertEquals(1, concepts)
        assertEquals(1, models)
        assertEquals(0, input.reads)
        assertEquals(listOf("bConceptOnly"), ConceptValidation(listOf(concept)).validate(input).flatMap { it.members })
        assertEquals(2, concepts)
        assertEquals(0, input.reads)
        assertEquals(listOf("concept"), ConceptValidation(listOf(concept)).validate(code).map { it.message })
    }

    @Test
    fun `Kotlin overrides and interface annotations inherit monotonically`() : Unit = runBlocking {
        for (type in listOf(Overridden::class.java, Implemented::class.java, Inherited::class.java)) {
            assertTrue(ValidationMemberPolicy.isIgnored(type, "child"), type.name)
        }
        val calls = mutableListOf<String>()
        val filter = DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(
            rule(Child::class.java) { _, context -> calls.add(context.memberPath); emptyList() }
        ))
        for (input in listOf(Overridden(), Implemented(), Inherited())) {
            assertTrue(filter.execute(command(input)).isSuccess)
        }
        assertTrue(calls.isEmpty())
        val rules = object : FluentModelValidator<Overridden>(Overridden::class.java) {
            init { ruleFor("child").notNull() }
        }
        assertTrue(rules.validate(Overridden()).isEmpty())
        assertEquals(1, rules.rules.size)
    }

    @Test
    fun `hidden Kotlin backing fields reject before reading rather than merging ignore metadata`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ValidationMemberPolicy.isIgnored(HiddenChild::class.java, "child")
        }
        assertTrue(failure.message!!.contains("${HiddenChild::class.java.name}.child"))
        assertTrue(failure.message!!.contains("hidden fields"))
        val filter = DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(
            rule(Child::class.java) { _, _ -> listOf(ValidationResult.error("child")) }
        ))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { filter.execute(command(HiddenChild())) } }
    }

    @Test
    fun `Kotlin boolean property and Jakarta bean name share the same metadata`() {
        assertTrue(ValidationMemberPolicy.isIgnored(BooleanOwner::class.java, "isReady"))
        assertTrue(ValidationMemberPolicy.isIgnored(BooleanOwner::class.java, "ready"))
        for (type in listOf(KotlinReady::class.java, KotlinReadyBase::class.java)) {
            assertTrue(ValidationMemberPolicy.isIgnored(type, "isReady"))
            assertTrue(ValidationMemberPolicy.isIgnored(type, "ready"))
        }
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ValidationMemberPolicy.isIgnored(AmbiguousBooleanOwner::class.java, "ready")
        }
        assertTrue(failure.message!!.contains("multiple logical members"))
    }

    class SyntheticInput {
        var reads = 0
            private set
        @get:JvmSynthetic @get:IgnoreValidation
        val ignored: String get() { reads++; throw AssertionError("ignored synthetic getter read") }
        val sibling = ""
    }
    class SyntheticRules : FluentModelValidator<SyntheticInput>(SyntheticInput::class.java) {
        val escaped = ruleFor("ignored").notEmpty()
        init { ruleFor("sibling").notEmpty() }
    }
    class ConcurrentMetadataOwner {
        @get:JvmSynthetic @get:IgnoreValidation
        val ignored: String get() = throw AssertionError("metadata invoked a synthetic getter")
    }
    interface KotlinReady { @get:IgnoreValidation val isReady: Boolean }
    open class KotlinReadyBase {
        @get:IgnoreValidation open val isReady: Boolean get() = throw AssertionError("ignored base getter")
    }
    class AmbiguousBooleanOwner {
        @get:IgnoreValidation val isReady: Boolean get() = throw AssertionError("ambiguous getter read")
        val ready = false
    }
    class UseSites {
        var reads = 0
            private set
        @IgnoreValidation val property: String get() { reads++; error("ignored property read") }
        @field:IgnoreValidation val field: String = ""
            get() { reads++; error("ignored field read: $field") }
        @get:IgnoreValidation val getter: String get() { reads++; error("ignored getter read") }
        val sibling = ""
    }
    class UseSiteRules : FluentModelValidator<UseSites>(UseSites::class.java) {
        val escaped = ruleFor("field").notEmpty()
        init { ruleFor("property").notEmpty(); ruleFor("getter").notEmpty(); ruleFor("sibling").notEmpty() }
        fun more() { ruleFor("getter").maxLength(2) }
    }
    class Child
    class Containers(child: Child) {
        @IgnoreValidation val aIgnoredAlias = child
        @IgnoreValidation val bArray = arrayOf(child)
        @IgnoreValidation val cList: Iterable<Child> = object : Iterable<Child> {
            override fun iterator(): Iterator<Child> = error("ignored iterable traversed")
        }
        @IgnoreValidation val dMap: Map<String, Child> = object : AbstractMap<String, Child>() {
            override val entries: Set<Map.Entry<String, Child>> get() = error("ignored map traversed")
        }
        @IgnoreValidation val eNested = listOf(mapOf("child" to arrayOf(child)))
        val zActive = listOf(child)
    }
    class IgnoredSize {
        @IgnoreValidation val items: List<String> = object : AbstractList<String>() {
            override val size: Int get() = error("ignored collection size inspected")
            override fun get(index: Int): String = error("ignored collection element inspected: $index")
        }
        @IgnoreValidation val missing: String? = null
        val sibling = ""
    }
    class Cycle {
        @IgnoreValidation var aIgnored: Cycle? = null
        var bActive: Cycle? = null
        var cActive: Cycle? = null
    }
    class Code(private val raw: String) : ConceptAs<String> { override fun value(): String = raw }
    class Concepts(private val code: Code) {
        var reads = 0
            private set
        @get:IgnoreValidation val aIgnored: Code get() { reads++; error("ignored concept getter") }
        val bConceptOnly: Code get() = code
        @get:IgnoreValidation val bIgnoredAlias: Code get() = code
        val cActive: Code get() = code
    }
    open class Base { @get:IgnoreValidation open val child: Child? = null }
    class Overridden : Base() { override val child: Child? = Child() }
    class Inherited : Base()
    interface Contract { @IgnoreValidation val child: Child }
    class Implemented : Contract { override val child: Child get() = error("ignored interface edge") }
    class BooleanOwner { @get:IgnoreValidation val isReady: Boolean get() = error("ignored boolean") }
    open class HiddenBase { @field:IgnoreValidation private val child = Child() }
    class HiddenChild : HiddenBase() {
        val child: Child = Child()
            get() = error("ambiguous member read: $field")
    }

    private fun <T : Any> rule(type: Class<T>, validate: (T, ModelValidationContext) -> List<ValidationResult>): ModelValidator<T> =
        object : ModelValidator<T> {
            override val modelType = type
            override suspend fun validate(model: T, context: ModelValidationContext): List<ValidationResult> = validate(model, context)
        }
    private fun command(value: Any) = CommandContext(UUID.randomUUID(), value, value.javaClass, ArcPrincipal.anonymous(), serviceResolver = Services)
    private fun query(arguments: Map<String, Any?>): QueryContext {
        val request = QueryRequest(FullyQualifiedQueryName("ignored.query"), arguments)
        return QueryContext(UUID.randomUUID(), request, request.queryName, ArcPrincipal.anonymous(), null, null, Services, null, false)
    }
    private object Services : ServiceResolver { override fun <T : Any> resolve(type: Class<T>): T? = null }
}
