// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.artifacts.ArcArtifactModuleRegistry
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.DefaultCommandValidationFilter
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.ValidationRuleDescriptor
import io.cratis.arc.queries.DefaultQueryValidationFilter
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FluentModelValidatorTest {
    @Test
    fun `all thirteen rules match shared client vectors including messages and UTF16`() {
        val vectors = ArcObjectMapper.create().readTree(javaClass.getResourceAsStream("/validation/fluent-vectors.json"))
        for (vector in vectors) {
            val name = vector["rule"].asString()
            val args = vector["args"].toList().map { if (it.isString) it.asString() else it.numberValue() }
            val value = vector["value"].let { when { it.isNull -> null; it.isString -> it.asString(); else -> it.numberValue() } }
            val descriptor = ValidationRuleDescriptor(name, args, vector["message"]?.asString())
            val member = vector["member"]?.asString() ?: "value"
            val type = if (name in listOf("greaterThan", "greaterThanOrEqual", "lessThan", "lessThanOrEqual")) Double::class.java else String::class.java
            val normalized = FluentValidationMember(member, type, listOf(descriptor)).rules.single()
            assertEquals(vector["valid"].asBoolean(), FluentValidationRules.valid(normalized, value), vector.toString())
            if (!vector["valid"].asBoolean()) assertEquals(vector["error"].asString(), FluentValidationRules.message(normalized, member), vector.toString())
        }
        assertEquals(13, vectors.toList().map { it["rule"].asString() }.distinct().size)
    }

    @Test
    fun `constructor rules run with Java compatible synchronous entry and error severity`() {
        val validator = NameRules()
        val result = validator.validate(Input(""))
        assertEquals(listOf("name", "name"), result.map { it.members.single() })
        assertEquals(listOf("'name' must not be empty.", "name needs length"), result.map { it.message })
        assertTrue(result.all { it.severity == ValidationResultSeverity.Error })
        assertTrue(validator.validate(Input("good")).isEmpty())
    }

    @Test
    fun `every builder entry executes its own descriptors and null accepts only optional rules`() {
        val rules = object : FluentModelValidator<AllValues>(AllValues::class.java) {
            init {
                ruleFor("text").notNull().notEmpty().minLength(1).maxLength(40).length(1, 40).emailAddress()
                ruleFor("phone").phone()
                ruleFor("url").url()
                ruleFor("code").matches("^[A-Z]+$")
                ruleFor("number").greaterThan(0).greaterThanOrEqual(1).lessThan(10).lessThanOrEqual(9)
            }
        }
        assertEquals(13, rules.rules.sumOf { it.rules.size })
        assertTrue(rules.validate(AllValues("a@b.c", "+47 123", "https://a", "ABC", 5)).isEmpty())
        assertEquals(2, rules.validate(AllValues(null, null, null, null, null)).size)
        assertEquals(listOf("code", "number", "number", "phone", "text", "url"),
            rules.validate(AllValues("not-email", "bad", "ftp://a", "bad", 10)).map { it.members.single() })
    }

    @Test
    fun `erased inline value members reject instead of treating boxed getter values as scalars`() {
        assertThrows(IllegalArgumentException::class.java) {
            object : FluentModelValidator<InlineOwner>(InlineOwner::class.java) { init { ruleFor("name").notEmpty() } }
        }
    }

    @Test
    fun `exact declared model class is enforced and inherited public member access is supported`() {
        val baseRules = object : FluentModelValidator<Base>(Base::class.java) { init { ruleFor("name").notEmpty() } }
        assertThrows(IllegalArgumentException::class.java) { baseRules.validate(Derived("")) }
        val derivedRules = object : FluentModelValidator<Derived>(Derived::class.java) { init { ruleFor("name").notEmpty() } }
        assertEquals(1, derivedRules.validate(Derived("")).size)
        assertEquals(String::class.java, derivedRules.rules.single().memberType)
    }

    @Test
    fun `descriptors are snapshots and deeply immutable for the supported argument vocabulary`() {
        val args = mutableListOf<Any>(3)
        val rules = mutableListOf(ValidationRuleDescriptor("maxLength", args))
        val member = FluentValidationMember("name", String::class.java, rules)
        args.clear(); rules.clear()
        assertEquals(listOf(3), member.rules.single().arguments)
        assertThrows(UnsupportedOperationException::class.java) { (member.rules as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (member.rules.single().arguments as MutableList).clear() }
        assertThrows(IllegalArgumentException::class.java) {
            FluentValidationMember("name", String::class.java, listOf(ValidationRuleDescriptor("maxLength", listOf(mutableListOf(3)))))
        }
    }

    @Test
    fun `reading or evaluating or registering freezes all escaped builders`() {
        for (freeze in listOf<(MutableRules) -> Unit>({ it.rules }, { it.validate(Input("ok")) }, {
            DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(it))
        })) {
            val validator = MutableRules()
            freeze(validator)
            assertThrows(IllegalStateException::class.java) { validator.builder.maxLength(8) }
            assertThrows(IllegalStateException::class.java) { validator.builder.withMessage("changed") }
            assertThrows(IllegalStateException::class.java) { validator.more() }
            assertThrows(UnsupportedOperationException::class.java) { (validator.rules as MutableList).clear() }
        }
    }

    @Test
    fun `message belongs to preceding rule and exact duplicates alone collapse`() {
        val validator = object : FluentModelValidator<Input>(Input::class.java) {
            init {
                ruleFor("name").notNull().maxLength(2).withMessage("one")
                ruleFor("name").maxLength(2).withMessage("two")
                ruleFor("name").maxLength(2).withMessage("one")
            }
        }
        assertEquals(listOf(null, "one", "two"), validator.rules.single().rules.map { it.message })
        assertEquals(listOf("one", "two"), validator.validate(Input("long")).map { it.message })
    }

    @Test
    fun `invalid members types bounds regex and credit card fail rather than becoming invisible`() {
        for (member in listOf("missing", "name.child", "name[0]", "", "hidden")) {
            assertThrows(IllegalArgumentException::class.java) { object : FluentModelValidator<Input>(Input::class.java) { init { ruleFor(member).notNull() } } }
        }
        assertThrows(IllegalArgumentException::class.java) { object : FluentModelValidator<Input>(Input::class.java) { init { ruleFor("name").greaterThan(0) } } }
        assertThrows(IllegalArgumentException::class.java) { object : FluentModelValidator<Count>(Count::class.java) { init { ruleFor("value").emailAddress() } } }
        assertThrows(IllegalArgumentException::class.java) { object : FluentModelValidator<Input>(Input::class.java) { init { ruleFor("name").minLength(-1) } } }
        assertThrows(IllegalStateException::class.java) { object : FluentModelValidator<Input>(Input::class.java) { init { ruleFor("name").withMessage("no rule") } } }
        assertThrows(IllegalArgumentException::class.java) { object : FluentModelValidator<Input>(Input::class.java) { init { ruleFor("name").creditCard() } } }
        for (pattern in listOf("(?i)abc", "a++", "\\Qabc\\E", "[a-z&&b]", ".", "[^a]", "\\p{L}", "(a)\\1", "(?=a)", "[")) {
            assertThrows(IllegalArgumentException::class.java, { object : FluentModelValidator<Input>(Input::class.java) { init { ruleFor("name").matches(pattern) } } }, pattern)
        }
    }

    @Test
    fun `ambiguous closing bracket classes reject and escaped bracket is portable`() {
        assertTrue(java.util.regex.Pattern.compile("[]]").matcher("]").find(), "Java treats the leading closing bracket as a literal")
        for (pattern in listOf("[]]", "[]", "]", "[a]]")) {
            assertThrows(IllegalArgumentException::class.java, {
                FluentValidationMember("name", String::class.java, listOf(ValidationRuleDescriptor("matches", listOf(pattern))))
            }, pattern)
        }
        val rule = FluentValidationMember("name", String::class.java,
            listOf(ValidationRuleDescriptor("matches", listOf("^[\\]]+$")))).rules.single()
        for ((value, expected) in listOf("]" to true, "]]" to true, "a" to false, "]a" to false, "]\n" to false, "" to true)) {
            assertEquals(expected, FluentValidationRules.valid(rule, value), value)
        }
    }

    @Test
    fun `contradictions across chains fail on snapshot and numbers use safe canonical domain`() {
        val bad = object : FluentModelValidator<Input>(Input::class.java) { init { ruleFor("name").minLength(3); ruleFor("name").maxLength(2) } }
        assertThrows(IllegalArgumentException::class.java) { bad.rules }
        val badNumbers = object : FluentModelValidator<Count>(Count::class.java) { init { ruleFor("value").greaterThan(2); ruleFor("value").lessThanOrEqual(2) } }
        assertThrows(IllegalArgumentException::class.java) { badNumbers.rules }
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.MIN_VALUE, 9007199254740992L, BigDecimal("0.100000000000000001"))) {
            assertThrows(IllegalArgumentException::class.java) { FluentValidationRules.number(value) }
        }
        val rules = FluentValidationMember("value", Double::class.java, listOf(
            ValidationRuleDescriptor("greaterThan", listOf(1)), ValidationRuleDescriptor("greaterThan", listOf(1.0))))
        assertEquals(1, rules.rules.size)
        assertEquals(listOf(1L), rules.rules.single().arguments)
        assertEquals(0.1, FluentValidationRules.number(0.1f))
    }

    @Test
    fun `float members reject rather than silently rounding a shared comparison at binding`() {
        assertThrows(IllegalArgumentException::class.java) {
            FluentValidationMember("value", Float::class.java, listOf(ValidationRuleDescriptor("greaterThan", listOf(1))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FluentValidationMember("value", Float::class.javaObjectType, listOf(ValidationRuleDescriptor("greaterThan", listOf(1))))
        }
    }

    @Test
    fun `collection and array length rules use element count not string conversion`() {
        val validator = object : FluentModelValidator<Containers>(Containers::class.java) {
            init { ruleFor("items").notEmpty().maxLength(2); ruleFor("array").length(1, 2) }
        }
        assertEquals(listOf("array", "items"), validator.validate(Containers(emptyList(), intArrayOf())).map { it.members.single() })
        assertTrue(validator.validate(Containers(listOf("a"), intArrayOf(1, 2))).isEmpty())
    }

    @Test
    fun `existing command and supplied query graphs qualify only relative paths`() : Unit = runBlocking {
        val rules = NameRules()
        val command = DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(rules))
        assertEquals(listOf("name", "name"), command.execute(command(Input(""))).validationResults.map { it.members.single() })
        assertEquals(listOf("input.name", "input.name"), command.execute(command(Envelope(Input("")))).validationResults.map { it.members.single() })
        val query = DefaultQueryValidationFilter(emptyList(), emptyList(), listOf(rules))
        assertTrue(query.execute(query(emptyMap())).isSuccess)
        assertTrue(query.execute(query(mapOf("arg" to null))).isSuccess)
        assertEquals(listOf("arg.name", "arg.name"), query.execute(query(mapOf("arg" to Input("")))).validationResults.map { it.members.single() })
    }

    @Test
    fun `cancellation and wrapped fatal getter errors escape existing pipeline`() : Unit = runBlocking {
        for (failure in listOf(CancellationException("cancel"), AssertionError("fatal"))) {
            val rule = object : FluentModelValidator<Throwing>(Throwing::class.java) { init { ruleFor("name").notNull() } }
            val filter = DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(rule))
            assertSame(failure, assertThrows(failure.javaClass) { runBlocking { filter.execute(command(Throwing(failure))) } })
        }
        val cancelled = Job().apply { cancel() }
        assertThrows(CancellationException::class.java) { runBlocking { withContext(cancelled) { NameRules().validate(Input("good"), ModelValidationContext(command(Input("good")), "")) } } }
    }

    @Test
    fun `fluent reflected interruption retains Phase5 facade policy and legacy failure semantics`() {
        val interrupted = InterruptedException("getter interrupted")
        val rule = object : FluentModelValidator<Throwing>(Throwing::class.java) { init { ruleFor("name").notNull() } }
        val filter = DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(rule))
        val legacy = runBlocking { filter.execute(command(Throwing(interrupted))) }
        assertEquals(ValidationResultReasons.VALIDATOR_FAILED, legacy.validationResults.single().reason)
        assertFalse(Thread.currentThread().isInterrupted)
        try {
            val failure = assertThrows(CancellationException::class.java) {
                io.cratis.arc.java.BlockingPipelineGuard.run { filter.execute(command(Throwing(interrupted))) }
            }
            assertSame(interrupted, failure.cause)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test
    fun `suspending evaluator observes cancellation triggered by a member getter`() : Unit = runBlocking {
        val job = Job()
        val rule = object : FluentModelValidator<Cancelling>(Cancelling::class.java) { init { ruleFor("name").notNull() } }
        assertThrows(CancellationException::class.java) {
            runBlocking(job) { rule.validate(Cancelling(job), ModelValidationContext(command(Input("good")), "")) }
        }
    }

    @Test
    fun `ordinary getter failure produces existing sanitized validator failed feedback`() : Unit = runBlocking {
        val rule = object : FluentModelValidator<Throwing>(Throwing::class.java) { init { ruleFor("name").notNull() } }
        val feedback = DefaultCommandValidationFilter(emptyList(), emptyList(), listOf(rule)).execute(command(Throwing(Exception("secret")))).validationResults
        assertEquals(ValidationResultReasons.VALIDATOR_FAILED, feedback.single().reason)
        assertFalse(feedback.single().message.contains("secret"))
    }

    @Test
    fun `generated contributions require matching metadata and dedup matching beans by class`() {
        val generated = NameRules()
        val bean = NameRules()
        val expected = generated.rules
        val mutable = expected.toMutableList()
        val registration = FluentValidatorRegistration(generated, Input::class.java, mutable)
        mutable.clear()
        assertEquals(expected, registration.expectedRules)
        assertThrows(IllegalArgumentException::class.java) { FluentValidatorRegistration(NameRules(), Count::class.java, expected) }
        assertThrows(IllegalArgumentException::class.java) { FluentValidatorRegistration(NameRules(), Input::class.java, emptyList()) }
        val module = module(registration)
        assertSame(bean, ArcArtifactModuleRegistry.modelValidators(listOf(module, module), listOf(bean, bean)).single())
        assertSame(generated, ArcArtifactModuleRegistry.modelValidators(listOf(module)).single())
        assertThrows(IllegalArgumentException::class.java) { ArcArtifactModuleRegistry.modelValidators(emptyList(), listOf(bean)) }
        assertTrue(object : ArcArtifactModule(emptyList(), emptyList()) {}.fluentValidators.isEmpty())
    }

    @Test
    fun `conflicting same class contributions and bean bodies fail registration`() {
        val first = MutableRules()
        val second = MutableRules().apply { builder.maxLength(5) }
        val firstModule = module(FluentValidatorRegistration(first, Input::class.java, first.rules))
        val secondModule = module(FluentValidatorRegistration(second, Input::class.java, second.rules))
        assertThrows(IllegalArgumentException::class.java) { ArcArtifactModuleRegistry.modelValidators(listOf(firstModule, secondModule)) }
        assertThrows(IllegalArgumentException::class.java) { ArcArtifactModuleRegistry.modelValidators(listOf(firstModule), listOf(second)) }
    }

    @Test
    fun `imperative validators retain order multiplicity and remain independent of fluent rules`() {
        val imperative = object : ModelValidator<Input> {
            override val modelType = Input::class.java
            override suspend fun validate(model: Input, context: ModelValidationContext): List<ValidationResult> = emptyList()
        }
        val rules = NameRules()
        val module = module(FluentValidatorRegistration(rules, Input::class.java, rules.rules))
        assertEquals(listOf(imperative, imperative, rules), ArcArtifactModuleRegistry.modelValidators(listOf(module), listOf(imperative, imperative)))
    }

    class Input(val name: String?) { private val hidden = "hidden" }
    @JvmInline value class InlineName(val value: String)
    class InlineOwner(val name: InlineName)
    class Count(val value: Double?)
    class AllValues(val text: String?, val phone: String?, val url: String?, val code: String?, val number: Int?)
    open class Base(val name: String)
    class Derived(name: String) : Base(name)
    class Containers(val items: List<String>, val array: IntArray)
    class Envelope(val input: Input)
    class Throwing(private val failure: Throwable) { val name: String get() = throw failure }
    class Cancelling(private val job: Job) { val name: String get() { job.cancel(); return "value" } }
    class NameRules : FluentModelValidator<Input>(Input::class.java) {
        init { ruleFor("name").notEmpty().minLength(2).withMessage("{PropertyName} needs length") }
    }
    class MutableRules : FluentModelValidator<Input>(Input::class.java) {
        val builder = ruleFor("name").notNull()
        fun more() { ruleFor("name").maxLength(3) }
    }
    private fun module(registration: FluentValidatorRegistration) = object : ArcArtifactModule(emptyList(), emptyList()) {
        override val fluentValidators = listOf(registration)
    }
    private fun command(value: Any) = CommandContext(UUID.randomUUID(), value, value.javaClass, ArcPrincipal.anonymous(), serviceResolver = Services)
    private object Services : io.cratis.arc.commands.ServiceResolver { override fun <T : Any> resolve(type: Class<T>): T? = null }
    private fun query(arguments: Map<String, Any?>): QueryContext {
        val request = QueryRequest(FullyQualifiedQueryName("fluent.query"), arguments)
        return QueryContext(UUID.randomUUID(), request, request.queryName, ArcPrincipal.anonymous(), null, null, Services, null, false)
    }
}
