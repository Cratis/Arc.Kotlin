// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.CommandExecutionOptions;
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry;
import io.cratis.arc.commands.DefaultCommandPipeline;
import io.cratis.arc.commands.DefaultCommandValidationFilter;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.java.BlockingCommandHandler;
import io.cratis.arc.java.BlockingCommandHandlerAdapter;
import io.cratis.arc.java.BlockingCommandPipeline;
import io.cratis.arc.java.BlockingModelValidator;
import io.cratis.arc.java.BlockingModelValidatorAdapter;
import io.cratis.arc.metadata.CommandDescriptor;
import io.cratis.arc.metadata.ValidationRuleDescriptor;
import io.cratis.arc.results.CommandResult;
import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.validation.FluentModelValidator;
import io.cratis.arc.validation.FluentValidationMember;
import io.cratis.arc.validation.FluentValidatorRegistration;
import io.cratis.arc.validation.IgnoreValidation;
import io.cratis.arc.validation.IgnoreValidationTest;
import io.cratis.arc.validation.ModelValidationContext;
import io.cratis.arc.validation.ModelValidator;
import io.cratis.arc.validation.ValidationMemberPolicy;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IgnoreValidationJavaConformanceTest {
    private static final ServiceResolver SERVICES = new ServiceResolver() {
        @Override public <T> T resolve(Class<T> type) { return null; }
    };
    private static final CommandExecutionOptions OPTIONS = new CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), SERVICES);

    @Test
    void JavaBeanFieldAndGetterAnnotationsGateBeforeReadsAndRetainDeclaredRules() {
        var input = new Bean();
        var rules = new BeanRules();
        var expected = List.of("field", "getter", "sibling").stream()
            .map(name -> new FluentValidationMember(name, String.class, List.of(new ValidationRuleDescriptor("notEmpty")))).toList();
        assertEquals(expected, rules.getRules());
        assertEquals(expected, new FluentValidatorRegistration(rules, Bean.class, expected).getExpectedRules());
        assertEquals(List.of("sibling"), rules.validate(input).stream().flatMap(result -> result.getMembers().stream()).toList());
        assertEquals(List.of("sibling"), validate(input, List.of(rules)).stream().flatMap(result -> result.getMembers().stream()).toList());
        assertEquals(0, input.reads);
        assertEquals(expected, rules.getRules());
        assertThrows(IllegalArgumentException.class,
            () -> new FluentValidatorRegistration(rules, Bean.class, List.of(expected.get(2))));
    }

    @Test
    void publicFieldsAndDefaultRecordComponentsIgnoreOnlyTheirOwnEdge() {
        assertEquals(List.of("sibling"), new TextFieldRules().validate(new TextFields())
            .stream().flatMap(result -> result.getMembers().stream()).toList());
        var child = new Child();
        var paths = new ArrayList<String>();
        var rule = childRule(paths);
        for (var owner : List.of(new Fields(child), new DefaultRecord(child, child))) {
            paths.clear();
            assertEquals(List.of("sibling"), validate(owner, List.of(rule)).stream().flatMap(result -> result.getMembers().stream()).toList());
            assertEquals(List.of("sibling"), paths);
        }
        paths.clear();
        assertEquals(List.of("child"), validate(child, List.of(rule)).stream().map(ValidationResult::getMessage).toList());
        assertEquals(List.of(""), paths);
    }

    @Test
    void recordHeaderAnnotationStillAppliesWithExplicitAccessorAndAccessorAnnotationWorks() {
        var child = new Child();
        for (var owner : List.of(new ExplicitRecord(child, child), new AccessorRecord(child, child))) {
            var paths = new ArrayList<String>();
            assertTrue(ValidationMemberPolicy.isIgnored(owner.getClass(), "ignored"));
            assertEquals(List.of("sibling"), validate(owner, List.of(childRule(paths))).stream().flatMap(result -> result.getMembers().stream()).toList());
            assertEquals(List.of("sibling"), paths);
        }
        var rules = new RecordRules();
        assertTrue(rules.validate(new TextRecord("")).isEmpty());
        assertEquals(List.of("ignored"), rules.getRules().stream().map(FluentValidationMember::getMember).toList());
    }

    @Test
    void inheritedFieldsGenuineGetterOverridesAndInterfaceAnnotationsRemainIgnored() {
        var child = new Child();
        for (var owner : List.of(new InheritedFields(child), new DerivedBean(), new InterfaceBean(), new CovariantBean())) {
            assertTrue(ValidationMemberPolicy.isIgnored(owner.getClass(), "ignored"));
            var paths = new ArrayList<String>();
            assertTrue(validate(owner, List.of(childRule(paths))).isEmpty());
            assertTrue(paths.isEmpty());
        }
        assertTrue(new DerivedRules().validate(new DerivedText()).isEmpty());
    }

    @Test
    void hiddenFieldsFailClearlyInsteadOfMergingByName() {
        var failure = assertThrows(IllegalArgumentException.class,
            () -> ValidationMemberPolicy.isIgnored(HiddenFields.class, "ignored"));
        assertTrue(failure.getMessage().contains(HiddenFields.class.getName() + ".ignored"));
        assertTrue(failure.getMessage().contains("hidden fields"));
        assertThrows(IllegalArgumentException.class, HiddenRules::new);
        var getterCollision = assertThrows(IllegalArgumentException.class,
            () -> ValidationMemberPolicy.isIgnored(HiddenGetterField.class, "ignored"));
        assertTrue(getterCollision.getMessage().contains("a hidden field and an unrelated getter"));
    }

    @Test
    void KotlinBooleanInterfaceAndBaseGetterFamiliesResolveBeforeJavaBeanAliases() {
        var bean = new ReadyBean();
        var derived = new ReadySubclass();
        assertReady(ReadyBean.class, bean);
        assertReady(ReadySubclass.class, derived);
        assertReady(InheritedReadyImplementation.class, new InheritedReadyImplementation());
        assertEquals(0, bean.reads);
        assertEquals(0, derived.reads);
        var collision = assertThrows(IllegalArgumentException.class,
            () -> ValidationMemberPolicy.isIgnored(UnrelatedReadyField.class, "ready"));
        assertTrue(collision.getMessage().contains("multiple logical members"));
        assertThrows(IllegalArgumentException.class,
            () -> ValidationMemberPolicy.isIgnored(TwoReadyFields.class, "ready"));
    }

    @Test
    void noIgnoreJavaFieldsKeepPrecedenceAndPreviouslyUnreadableBeanEdgesAreAddedOnce() {
        var bean = new MixedBean();
        var paths = new ArrayList<String>();
        var invocations = new AtomicInteger();
        var result = pipeline(bean, List.of(childRule(paths)), invocations).execute(bean);
        assertFalse(result.isSuccess());
        assertEquals(List.of("child", "other"), paths);
        assertEquals(List.of("child", "other"), result.getValidationResults().stream().flatMap(item -> item.getMembers().stream()).toList());
        assertEquals(0, bean.fieldGetterReads);
        assertEquals(1, bean.otherReads);
        assertEquals(0, invocations.get());
        var ignored = new Bean();
        var ignoredInvocations = new AtomicInteger();
        assertFalse(pipeline(ignored, List.of(new BeanRules()), ignoredInvocations).execute(ignored).isSuccess());
        assertEquals(0, ignored.reads);
        assertEquals(0, ignoredInvocations.get());
    }

    @Test
    void newJavaBeanWalkUnwrapsCancellationAndFatalErrorsBeforeAnyHandlerInvocation() {
        for (boolean preflight : List.of(false, true)) {
            var cancelled = new CancellationException("bean cancelled");
            var bean = new FailingBean(cancelled);
            var invocations = new AtomicInteger();
            var commands = pipeline(bean, List.of(childRule(new ArrayList<>())), invocations);
            var failure = assertThrows(CancellationException.class, () -> run(commands, bean, preflight));
            while (failure.getCause() instanceof CancellationException cause) failure = cause;
            assertSame(cancelled, failure);
            assertEquals(0, invocations.get());

            var fatal = new AssertionError("bean fatal");
            var fatalBean = new FailingBean(fatal);
            var fatalCommands = pipeline(fatalBean, List.of(childRule(new ArrayList<>())), invocations);
            var observed = assertThrows(AssertionError.class, () -> run(fatalCommands, fatalBean, preflight));
            assertEquals(fatal.getMessage(), observed.getMessage());
            // Coroutine stack-trace recovery may copy this Error at the execute withContext boundary,
            // just as it copies cancellation. Its error-only cause chain must reach the exact original.
            while (observed.getCause() instanceof AssertionError cause) observed = cause;
            assertSame(fatal, observed);
            assertEquals(0, invocations.get());
        }
    }

    @Test
    void newJavaBeanWalkPreservesBlockingInterruptionAndStopsOrdinaryFailuresBeforeHandlers() {
        for (boolean preflight : List.of(false, true)) {
            var interrupted = new InterruptedException("bean interrupted");
            var bean = new FailingBean(interrupted);
            var invocations = new AtomicInteger();
            var commands = pipeline(bean, List.of(childRule(new ArrayList<>())), invocations);
            assertFalse(Thread.currentThread().isInterrupted());
            try {
                var failure = assertThrows(CancellationException.class, () -> run(commands, bean, preflight));
                assertSame(interrupted, failure.getCause());
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(0, invocations.get());
            } finally {
                Thread.interrupted();
            }
            var ordinary = new FailingBean(new IllegalStateException("unreadable bean"));
            var result = run(pipeline(ordinary, List.of(childRule(new ArrayList<>())), invocations), ordinary, preflight);
            assertFalse(result.isSuccess());
            assertTrue(result.getHasExceptions());
            assertFalse(Thread.currentThread().isInterrupted());
            assertEquals(0, invocations.get());
        }
    }

    @Test
    void JavaSetterAndStaticMethodAnnotationsAreNotMemberOptOuts() {
        assertFalse(ValidationMemberPolicy.isIgnored(UnsupportedSites.class, "name"));
        assertFalse(ValidationMemberPolicy.isIgnored(UnsupportedSites.class, "staticName"));
        assertEquals(1, new UnsupportedRules().validate(new UnsupportedSites()).size());
    }

    public static final class ReadyBean implements IgnoreValidationTest.KotlinReady {
        private int reads;
        private final boolean ready = false;
        @Override public boolean isReady() { reads++; throw new AssertionError("ignored interface getter: " + ready); }
        public String getSibling() { return ""; }
    }
    public static final class ReadySubclass extends IgnoreValidationTest.KotlinReadyBase {
        private int reads;
        @Override public boolean isReady() { reads++; throw new AssertionError("ignored Kotlin base override"); }
        public String getSibling() { return ""; }
    }
    public static class ReadyMethodBase {
        public boolean isReady() { throw new AssertionError("ignored inherited interface implementation"); }
    }
    public static final class InheritedReadyImplementation extends ReadyMethodBase implements IgnoreValidationTest.KotlinReady {
        public String getSibling() { return ""; }
    }
    public static final class UnrelatedReadyField extends IgnoreValidationTest.KotlinReadyBase {
        public boolean ready;
    }
    public static final class TwoReadyFields implements IgnoreValidationTest.KotlinReady {
        public boolean ready;
        public boolean isReady;
        @Override public boolean isReady() { throw new AssertionError("ambiguous fields read"); }
    }
    public static final class ReadyRules<T> extends FluentModelValidator<T> {
        public ReadyRules(Class<T> type) {
            super(type);
            ruleFor("ready").notNull();
            ruleFor("isReady").notNull();
            ruleFor("sibling").notEmpty();
        }
    }
    public static final class MixedBean {
        public final Child child = new Child();
        private int fieldGetterReads;
        private int otherReads;
        public Child getChild() { fieldGetterReads++; throw new AssertionError("public field must retain precedence"); }
        public Child getOther() { otherReads++; return new Child(); }
    }
    public static final class FailingBean {
        private final Throwable failure;
        public FailingBean(Throwable failure) { this.failure = failure; }
        public Child getChild() throws InvocationTargetException {
            throw new InvocationTargetException(new CompletionException(failure));
        }
    }
    public static final class Bean {
        private int reads;
        @IgnoreValidation private final String field = "";
        public String getField() { reads++; throw new AssertionError("ignored field getter: " + field); }
        @IgnoreValidation public String getGetter() { reads++; throw new AssertionError("ignored bean getter"); }
        public String getSibling() { return ""; }
    }
    public static final class BeanRules extends FluentModelValidator<Bean> {
        public BeanRules() { super(Bean.class); ruleFor("field").notEmpty(); ruleFor("getter").notEmpty(); ruleFor("sibling").notEmpty(); }
    }
    public static final class Child { }
    public static class Fields {
        @IgnoreValidation public final Child ignored;
        public final Child sibling;
        public Fields(Child child) { ignored = child; sibling = child; }
    }
    public static final class TextFields {
        @IgnoreValidation public String ignored;
        public String sibling = "";
    }
    public static final class TextFieldRules extends FluentModelValidator<TextFields> {
        public TextFieldRules() { super(TextFields.class); ruleFor("ignored").notNull(); ruleFor("sibling").notEmpty(); }
    }
    public record DefaultRecord(@IgnoreValidation Child ignored, Child sibling) { }
    public record ExplicitRecord(@IgnoreValidation Child ignored, Child sibling) {
        @Override public Child ignored() { throw new AssertionError("ignored explicit record accessor"); }
    }
    public record AccessorRecord(Child ignored, Child sibling) {
        @IgnoreValidation @Override public Child ignored() { throw new AssertionError("ignored record accessor annotation"); }
    }
    public record TextRecord(@IgnoreValidation String ignored) {
        @Override public String ignored() { throw new AssertionError("ignored direct fluent record accessor"); }
    }
    public static final class RecordRules extends FluentModelValidator<TextRecord> {
        public RecordRules() { super(TextRecord.class); ruleFor("ignored").notEmpty(); }
    }
    public static class BaseFields {
        @IgnoreValidation public final Child ignored;
        public BaseFields(Child child) { ignored = child; }
    }
    public static final class InheritedFields extends BaseFields {
        public InheritedFields(Child child) { super(child); }
    }
    public static class BaseBean {
        @IgnoreValidation public Child getIgnored() { throw new AssertionError("ignored base getter"); }
    }
    public static final class DerivedBean extends BaseBean {
        @Override public Child getIgnored() { throw new AssertionError("ignored overriding getter"); }
    }
    public interface Contract {
        @IgnoreValidation Child getIgnored();
    }
    public static final class InterfaceBean implements Contract {
        @Override public Child getIgnored() { throw new AssertionError("ignored interface getter"); }
    }
    public interface CovariantContract {
        @IgnoreValidation Object getIgnored();
    }
    public static final class CovariantBean implements CovariantContract {
        @Override public Child getIgnored() { throw new AssertionError("ignored covariant getter"); }
    }
    public static class BaseText {
        @IgnoreValidation public String getIgnored() { throw new AssertionError("ignored base text"); }
    }
    public static final class DerivedText extends BaseText {
        @Override public String getIgnored() { throw new AssertionError("ignored derived text"); }
    }
    public static final class DerivedRules extends FluentModelValidator<DerivedText> {
        public DerivedRules() { super(DerivedText.class); ruleFor("ignored").notEmpty(); }
    }
    public static class HiddenBase { @IgnoreValidation public String ignored = ""; }
    public static final class HiddenFields extends HiddenBase { public String ignored = ""; }
    public static final class HiddenGetterField extends BaseText { public String ignored = ""; }
    public static final class HiddenRules extends FluentModelValidator<HiddenFields> {
        public HiddenRules() { super(HiddenFields.class); ruleFor("ignored").notEmpty(); }
    }
    public static final class UnsupportedSites {
        private String name = "";
        public String getName() { return name; }
        @IgnoreValidation public void setName(String value) { name = value; }
        @IgnoreValidation public static String getStaticName() { return ""; }
    }
    public static final class UnsupportedRules extends FluentModelValidator<UnsupportedSites> {
        public UnsupportedRules() { super(UnsupportedSites.class); ruleFor("name").notEmpty(); }
    }

    private static ModelValidator<Child> childRule(List<String> paths) {
        return new BlockingModelValidatorAdapter<>(new BlockingModelValidator<Child>() {
            @Override public Class<Child> getModelType() { return Child.class; }
            @Override public List<ValidationResult> validate(Child value, ModelValidationContext context) {
                paths.add(context.getMemberPath());
                return List.of(ValidationResult.error("child"));
            }
        });
    }
    private static <T> void assertReady(Class<T> type, T owner) {
        assertTrue(ValidationMemberPolicy.isIgnored(type, "isReady"));
        assertTrue(ValidationMemberPolicy.isIgnored(type, "ready"));
        var rules = new ReadyRules<>(type);
        var snapshot = rules.getRules();
        assertEquals(List.of("sibling"), rules.validate(owner).stream().flatMap(item -> item.getMembers().stream()).toList());
        assertEquals(List.of("sibling"), validate(owner, List.of(rules)).stream().flatMap(item -> item.getMembers().stream()).toList());
        assertSame(snapshot, rules.getRules());
    }
    private static CommandResult<?> run(BlockingCommandPipeline commands, Object owner, boolean preflight) {
        return preflight ? commands.validate(owner) : commands.execute(owner);
    }
    private static List<ValidationResult> validate(Object owner, List<ModelValidator<?>> validators) {
        return pipeline(owner, validators, new AtomicInteger()).validate(owner).getValidationResults();
    }
    private static BlockingCommandPipeline pipeline(Object owner, List<ModelValidator<?>> validators, AtomicInteger invocations) {
        var handlers = new ConcurrentCommandHandlerRegistry();
        handlers.register(new BlockingCommandHandlerAdapter(new BlockingCommandHandler() {
            @Override public Class<?> getCommandType() { return owner.getClass(); }
            @Override public CommandDescriptor getMetadata() { return new CommandDescriptor("Ignored", owner.getClass().getName()); }
            @Override public Object invoke(CommandContext context) { invocations.incrementAndGet(); return "handled"; }
        }));
        var filter = new DefaultCommandValidationFilter(List.of(), List.of(), validators);
        return new BlockingCommandPipeline(new DefaultCommandPipeline(handlers, List.of(filter)), OPTIONS);
    }
}
