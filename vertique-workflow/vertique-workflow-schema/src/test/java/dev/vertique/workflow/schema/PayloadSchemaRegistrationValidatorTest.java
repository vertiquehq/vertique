// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.schema.validation.PatternInputGuard;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.RegisteredWorkflowDefinition;
import dev.vertique.workflow.schema.fixtures.Payloads;
import io.vertx.core.json.JsonObject;
import jakarta.validation.Validation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the registration validator: which profile generates, what is cached under which key, what counts as no
 * payload, and every way registration fails.
 */
class PayloadSchemaRegistrationValidatorTest {

    private static final String PLAIN = Payloads.PlainRecord.class.getName();
    private static final String COMMENT = Payloads.Comment.class.getName();

    private final PayloadSchemaCache cache = new PayloadSchemaCache();

    // --- Contract proofs ---

    @Test
    @DisplayName("generation runs under the process profile and entries are cached per type name and profile")
    void generatesUnderTheProcessProfileAndCachesPerTypeNameAndProfile() {
        // Given: a registry with a custom profile p1 that names properties in snake case, a configuration whose
        // system profile is p1, and a validator over a generator that counts the types it describes
        var registry = Profiles.with(Profiles.snakeCase("p1"));
        CountingGenerator generator = new CountingGenerator(registry, Profiles.systemProfile("p1"));
        PayloadSchemaRegistrationValidator validator = validatorOver(generator);

        // When: two definitions that share Comment as a decision type are validated
        validator.validate(Definitions.candidate(Definitions.withDecision("first", 1L, Payloads.Comment.class)));
        validator.validate(Definitions.candidate(Definitions.withDecision("second", 1L, Payloads.Comment.class)));

        // Then: one entry exists for (Comment, p1), described once, whose property is the profile's wire name
        JsonProfileId p1 = JsonProfileId.of("p1");
        assertThat(cache.snapshot()).containsOnlyKeys(new SchemaKey(COMMENT, p1));
        JsonObject document = cache.snapshot().get(new SchemaKey(COMMENT, p1)).document();
        assertThat(document.getJsonObject("properties").fieldNames()).containsExactly("display_name");
        assertThat(generator.described).containsExactly(Payloads.Comment.class);

        // And: no entry exists for the same type under another profile id
        assertThat(cache.find(new SchemaKey(COMMENT, JsonProfileId.of("vertique"))))
                .isEmpty();
        assertThat(cache.find(new SchemaKey(COMMENT, JsonProfileId.SYSTEM))).isEmpty();
    }

    @Test
    @DisplayName("void types are no payload and unresolvable names fail registration")
    void voidTypesAreNoPayloadAndUnresolvableNamesFailRegistration() {
        // Given: a generator that counts descriptions and a validator over it
        CountingGenerator generator = new CountingGenerator(Profiles.builtIn(), Profiles.VERTIQUE);
        PayloadSchemaRegistrationValidator validator = validatorOver(generator);
        Map<String, Class<?>> voids = new LinkedHashMap<>();
        voids.put("ok", void.class);
        voids.put("ok2", Void.class);

        // When: void decisions and a void start are validated
        assertThatCode(() -> validator.validate(
                        Definitions.candidate(Definitions.withStartAndDecisions("d", 1L, Void.class, voids))))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(
                        Definitions.candidate(Definitions.withStartAndDecisions("d", 2L, void.class, voids))))
                .doesNotThrowAnyException();

        // Then: nothing was generated or cached, so a missing short-circuit cannot hide behind the generator
        assertThat(generator.described).isEmpty();
        assertThat(cache.snapshot()).isEmpty();

        // When: a plan names a class that cannot be loaded
        RegisteredWorkflowDefinition missing =
                Definitions.handBuilt("d", 1L, Void.class, Definitions.task("review", "bad", "com.example.Missing"));

        // Then: registration fails with the frozen wording and nothing is cached
        assertThatThrownBy(() -> validator.validate(missing))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessage("definition 'd' v1 step 'review' decision 'bad' payload type 'com.example.Missing'"
                        + " cannot be loaded");
        assertThat(cache.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("an uncompilable pattern fails registration naming the pointer")
    void uncompilablePatternFailsRegistrationNamingThePointer() {
        // Given: a validator under the reserved profile
        PayloadSchemaRegistrationValidator validator = validator();
        String bad = Payloads.BadPattern.class.getName();

        // When: a decision typed with an uncompilable pattern is validated
        // Then: registration fails naming the pointer and echoing neither the pattern nor the type's wire value
        assertThatThrownBy(() -> validator.validate(
                        Definitions.candidate(Definitions.withDecision("d", 1L, Payloads.BadPattern.class))))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageMatching("definition 'd' v1 step 'review' decision 'note' payload type '"
                        + java.util.regex.Pattern.quote(bad)
                        + "' has a pattern at /properties/code/pattern that does not compile: .+ near index \\d+")
                .message()
                .doesNotContain("(");
        assertThat(cache.snapshot()).isEmpty();

        // When: a decision typed with a compilable pattern is validated
        validator.validate(Definitions.candidate(Definitions.withDecision("d", 2L, Payloads.GoodPattern.class)));

        // Then: its entry exists and the compiled validator enforces the pattern
        CachedPayloadSchema entry = entryOf(Payloads.GoodPattern.class.getName(), "vertique");
        assertThat(Entries.accepts(entry, new JsonObject().put("code", "abc"))).isTrue();
        assertThat(Entries.accepts(entry, new JsonObject().put("code", "ABC"))).isFalse();
    }

    // --- Cache sharing and reuse ---

    @Test
    @DisplayName("the start type and a decision type of the same name share one description")
    void startAndDecisionTypesOfTheSameNameShareOneDescription() {
        // Given: a counting generator
        CountingGenerator generator = new CountingGenerator(Profiles.builtIn(), Profiles.VERTIQUE);
        PayloadSchemaRegistrationValidator validator = validatorOver(generator);

        // When: a definition starts with PlainRecord and also decides with PlainRecord
        validator.validate(Definitions.candidate(
                Definitions.withStartAndDecision("d", 1L, Payloads.PlainRecord.class, Payloads.PlainRecord.class)));

        // Then: PlainRecord was described once and cached once
        assertThat(generator.described).containsExactly(Payloads.PlainRecord.class);
        assertThat(cache.snapshot()).hasSize(1);
    }

    @Test
    @DisplayName("the inject constructor generates under the effective system profile with the module's own loader")
    void theInjectConstructorGeneratesUnderTheEffectiveSystemProfile() {
        // Given: the public constructor over a registry with the snake case profile
        PayloadSchemaRegistrationValidator validator = new PayloadSchemaRegistrationValidator(
                Profiles.with(Profiles.snakeCase("p1")), Profiles.systemProfile("p1"), Optional.empty(), cache);

        // When: a definition is validated
        validator.validate(Definitions.candidate(Definitions.withDecision(Payloads.Comment.class)));

        // Then: the entry carries the profile's wire name
        assertThat(entryOf(COMMENT, "p1").document().getJsonObject("properties").fieldNames())
                .containsExactly("display_name");
    }

    @Test
    @DisplayName("a cached validator is guarded at the shared default bounds")
    void aCachedValidatorIsGuardedAtTheSharedDefaultBounds() {
        // Given: an entry for a type with a pattern
        validator().validate(Definitions.candidate(Definitions.withDecision(Payloads.GoodPattern.class)));
        CachedPayloadSchema entry = entryOf(Payloads.GoodPattern.class.getName(), "vertique");

        // When: a value longer than the default per-string bound reaches the pattern
        JsonObject overlong = new JsonObject().put("code", "a".repeat(PatternInputGuard.DEFAULT_MAX_CHARS + 1));

        // Then: the guard refuses it with the default limit, and a value at the limit is accepted
        assertThatThrownBy(() -> Entries.accepts(entry, overlong))
                .isInstanceOfSatisfying(
                        PatternInputGuard.PatternInputTooLong.class,
                        rejection -> assertThat(rejection.maxChars()).isEqualTo(PatternInputGuard.DEFAULT_MAX_CHARS));
        assertThat(Entries.accepts(
                        entry, new JsonObject().put("code", "a".repeat(PatternInputGuard.DEFAULT_MAX_CHARS))))
                .isTrue();
    }

    // --- Document objects ---

    @Test
    @DisplayName("document objects skip generation, are cached with the fixed document and still compile")
    void documentObjectsSkipGenerationAndAreCachedWithTheFixedDocument() {
        // Given: a counting generator
        CountingGenerator generator = new CountingGenerator(Profiles.builtIn(), Profiles.VERTIQUE);
        PayloadSchemaRegistrationValidator validator = validatorOver(generator);
        Map<String, Class<?>> decisions = new LinkedHashMap<>();
        decisions.put("doc", JsonObject.class);
        decisions.put("node", ObjectNode.class);

        // When: a definition decides with the two document-object types
        validator.validate(Definitions.candidate(Definitions.withDecisions("d", 1L, decisions)));

        // Then: nothing was described, and both entries hold the bare object document
        assertThat(generator.described).isEmpty();
        assertThat(cache.snapshot().keySet())
                .containsExactlyInAnyOrder(
                        new SchemaKey(JsonObject.class.getName(), JsonProfileId.of("vertique")),
                        new SchemaKey(ObjectNode.class.getName(), JsonProfileId.of("vertique")));
        for (CachedPayloadSchema entry : cache.snapshot().values()) {
            assertThat(entry.document()).isEqualTo(new JsonObject().put("type", "object"));
            assertThat(Entries.accepts(entry, new JsonObject().put("anything", 1)))
                    .isTrue();
            assertThat(Entries.accepts(entry, "text")).isFalse();
        }
    }

    // --- Rejections ---

    @Test
    @DisplayName("a scalar start payload fails with the start payload wording")
    void scalarStartPayloadFailsWithTheStartPayloadWording() {
        // Given: a definition that starts with a String
        RegisteredWorkflowDefinition candidate =
                Definitions.candidate(Definitions.withStart("starter", 3L, String.class));

        // When / Then: registration fails naming the start payload and the type
        assertThatThrownBy(() -> validator().validate(candidate))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageStartingWith("definition 'starter' v3 start payload")
                .hasMessageContaining("payload type 'java.lang.String' is not object-shaped (root type string);"
                        + " wrap the value in a record");
        assertThat(cache.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("a scalar decision fails with the step and decision wording")
    void scalarDecisionFailsWithTheStepAndDecisionWording() {
        // Given: a definition that decides with an Integer
        RegisteredWorkflowDefinition candidate =
                Definitions.candidate(Definitions.withDecision("deciding", 2L, Integer.class));

        // When / Then: registration fails naming the step, the decision and the root type
        assertThatThrownBy(() -> validator().validate(candidate))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessage("definition 'deciding' v2 step 'review' decision 'note' payload type 'java.lang.Integer'"
                        + " is not object-shaped (root type integer); wrap the value in a record");
        assertThat(cache.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("validation fails fast in plan order: start, then tasks in node order, decisions in declaration order")
    void failsFastInPlanOrder() {
        // Given: a plan with unresolvable names in two tasks, and a resolvable type declared before the first
        RegisteredWorkflowDefinition scalarStart =
                Definitions.handBuilt("d", 1L, String.class, Definitions.task("first", "a", "com.example.Missing1"));
        RegisteredWorkflowDefinition voidStart = Definitions.handBuilt(
                "d",
                1L,
                Void.class,
                Definitions.task("first", "ok", PLAIN, "a", "com.example.Missing1", "b", "com.example.Missing2"),
                Definitions.task("second", "c", "com.example.Missing3"));

        // When / Then: a scalar start is reported before any task
        assertThatThrownBy(() -> validator().validate(scalarStart))
                .hasMessageContaining("start payload")
                .hasMessageContaining("java.lang.String");

        // And: otherwise the first task's first failing decision is reported
        assertThatThrownBy(() -> validator().validate(voidStart))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessage("definition 'd' v1 step 'first' decision 'a' payload type 'com.example.Missing1'"
                        + " cannot be loaded");

        // And: the type resolved before the failure stays cached
        assertThat(cache.find(new SchemaKey(PLAIN, JsonProfileId.of("vertique"))))
                .isPresent();
        assertThat(cache.snapshot()).hasSize(1);
    }

    @Test
    @DisplayName("a primitive type name cannot be loaded")
    void aPrimitiveTypeNameCannotBeLoaded() {
        // Given: a plan that names the primitive int as a decision type
        RegisteredWorkflowDefinition candidate =
                Definitions.handBuilt("d", 1L, Void.class, Definitions.task("review", "count", "int"));

        // When / Then: it is reported like any other unresolvable name
        assertThatThrownBy(() -> validator().validate(candidate))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessage("definition 'd' v1 step 'review' decision 'count' payload type 'int' cannot be loaded");
    }

    // --- Class resolution ---

    @Test
    @DisplayName("payload classes are resolved only through the class loader the validator was given")
    void payloadClassesAreResolvedThroughTheSuppliedClassLoader() {
        // Given: a loader that records the names it is asked for
        RecordingLoader loader = new RecordingLoader(getClass().getClassLoader());
        PayloadSchemaRegistrationValidator validator = new PayloadSchemaRegistrationValidator(
                cache, new PayloadSchemaGenerator(Profiles.builtIn(), Profiles.VERTIQUE, Optional.empty()), loader);

        // When: a plan with a decision type is validated
        validator.validate(Definitions.candidate(Definitions.withDecision(Payloads.PlainRecord.class)));

        // Then: the type was requested from that loader, and nothing was loaded elsewhere to find it
        assertThat(loader.requested).contains(PLAIN);
        assertThat(cache.find(new SchemaKey(PLAIN, JsonProfileId.of("vertique"))))
                .isPresent();
    }

    @Test
    @DisplayName("a linkage error while loading a payload class counts as not loadable")
    void aLinkageErrorWhileLoadingCountsAsNotLoadable() {
        // Given: a loader that fails with a linkage error
        RecordingLoader loader = new RecordingLoader(getClass().getClassLoader());
        loader.failure = new NoClassDefFoundError("broken");
        PayloadSchemaRegistrationValidator validator = new PayloadSchemaRegistrationValidator(
                cache, new PayloadSchemaGenerator(Profiles.builtIn(), Profiles.VERTIQUE, Optional.empty()), loader);

        // When / Then: the registration fails as an unresolvable name
        assertThatThrownBy(() -> validator.validate(
                        Definitions.candidate(Definitions.withDecision("d", 1L, Payloads.PlainRecord.class))))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessage("definition 'd' v1 step 'review' decision 'note' payload type '" + PLAIN
                        + "' cannot be loaded");
    }

    // --- Description failures ---

    @Test
    @DisplayName("a generator that cannot be built is reported as cannot be described with the cause chained")
    void generatorConstructionFailureIsReportedAsCannotBeDescribed() {
        // Given: a system profile that declares a null type override, which fails generator construction
        PayloadSchemaRegistrationValidator validator = new PayloadSchemaRegistrationValidator(
                Profiles.with(Profiles.withNullOverride("bad")),
                Profiles.systemProfile("bad"),
                Optional.empty(),
                cache);

        // When / Then: the first use fails as cannot be described and keeps the generator's own message
        assertThatThrownBy(() -> validator.validate(
                        Definitions.candidate(Definitions.withDecision("d", 1L, Payloads.PlainRecord.class))))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageStartingWith("definition 'd' v1 step 'review' decision 'note' payload type '" + PLAIN
                        + "' cannot be described: ")
                .hasMessageContaining("profile 'bad'")
                .hasCauseInstanceOf(RuntimeException.class);
        assertThat(cache.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("a generation failure is reported as cannot be described with the generator's message")
    void generationFailureIsReportedAsCannotBeDescribed() {
        // Given: a generator that fails its next description
        CountingGenerator generator = new CountingGenerator(Profiles.builtIn(), Profiles.VERTIQUE);
        generator.failNext(new IllegalStateException("boom"));

        // When / Then: the failure carries the generator's message and the original cause
        assertThatThrownBy(() -> validatorOver(generator)
                        .validate(Definitions.candidate(Definitions.withDecision("d", 1L, Payloads.PlainRecord.class))))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessage("definition 'd' v1 step 'review' decision 'note' payload type '" + PLAIN
                        + "' cannot be described: boom")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a schema the validation engine cannot compile is reported as cannot be described with constant text")
    void guardedCompileFailureIsReportedAsCannotBeDescribedWithConstantText() {
        // Given: a system profile whose UUID schema carries a keyword value the engine cannot compile
        PayloadSchemaRegistrationValidator validator = new PayloadSchemaRegistrationValidator(
                Profiles.with(Profiles.withUncompilableUuidSchema("ov")),
                Profiles.systemProfile("ov"),
                Optional.empty(),
                cache);
        String holder = Payloads.UuidHolder.class.getName();

        // When / Then: registration fails as cannot be described without the engine's own message
        assertThatThrownBy(() -> validator.validate(
                        Definitions.candidate(Definitions.withDecision("d", 1L, Payloads.UuidHolder.class))))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageStartingWith("definition 'd' v1 step 'review' decision 'note' payload type '" + holder
                        + "' cannot be described")
                .message()
                .doesNotContain("cast")
                .doesNotContain("JsonObject");
        assertThat(cache.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("a failed description is never cached")
    void failuresAreNeverCached() {
        // Given: a generator that fails its first description only
        CountingGenerator generator = new CountingGenerator(Profiles.builtIn(), Profiles.VERTIQUE);
        generator.failNext(new IllegalStateException("transient"));
        PayloadSchemaRegistrationValidator validator = validatorOver(generator);
        RegisteredWorkflowDefinition candidate =
                Definitions.candidate(Definitions.withDecision(Payloads.PlainRecord.class));

        // When: the same definition is validated twice
        assertThatThrownBy(() -> validator.validate(candidate)).isInstanceOf(WorkflowDefinitionException.class);
        assertThat(cache.snapshot()).isEmpty();
        validator.validate(candidate);

        // Then: the second attempt described the type again and cached it
        assertThat(generator.described).hasSize(2);
        assertThat(cache.snapshot()).hasSize(1);
    }

    @Test
    @DisplayName("an unknown system profile is a configuration fault and is not wrapped away")
    void anUnknownProfilePropagatesAsAConfigurationFault() {
        // Given: a configuration naming a profile the registry does not hold
        assertThatCode(() -> new PayloadSchemaRegistrationValidator(
                        Profiles.builtIn(), Profiles.systemProfile("nope"), Optional.empty(), cache))
                .doesNotThrowAnyException();
        PayloadSchemaRegistrationValidator validator = new PayloadSchemaRegistrationValidator(
                Profiles.builtIn(), Profiles.systemProfile("nope"), Optional.empty(), cache);

        // When / Then: a definition without payload types never needs the profile
        assertThatCode(() -> validator.validate(Definitions.candidate(Definitions.withStart("d", 1L, Void.class))))
                .doesNotThrowAnyException();

        // And: the first type that needs generation surfaces the unknown profile
        Throwable thrown = catchThrowable(
                () -> validator.validate(Definitions.candidate(Definitions.withDecision(Payloads.PlainRecord.class))));
        assertThat(causeChain(thrown)).anyMatch(JsonProfileConfigurationException.class::isInstance);
        assertThat(cache.snapshot()).isEmpty();
    }

    // --- Constraint supplement log line ---

    @Test
    @DisplayName("whether the constraint supplement is enabled is logged once at INFO per generator")
    void constraintSupplementStateIsLoggedOnceAtInfo() {
        try (CapturedLogs logs = CapturedLogs.attach(Level.INFO)) {
            // Given / When: a validator without a bean validator describes two types
            PayloadSchemaRegistrationValidator without = validator();
            without.validate(Definitions.candidate(Definitions.withDecision("a", 1L, Payloads.PlainRecord.class)));
            without.validate(Definitions.candidate(Definitions.withDecision("b", 1L, Payloads.Comment.class)));

            // Then: exactly one line says disabled and none says enabled
            assertThat(logs.supplementLines()).hasSize(1).allMatch(line -> line.contains("disabled"));
        }

        try (CapturedLogs logs = CapturedLogs.attach(Level.INFO);
                var factory = Validation.buildDefaultValidatorFactory()) {
            // Given / When: a validator with a bean validator describes two types
            PayloadSchemaRegistrationValidator with = new PayloadSchemaRegistrationValidator(
                    Profiles.builtIn(),
                    Profiles.VERTIQUE,
                    Optional.of(factory.getValidator()),
                    new PayloadSchemaCache());
            with.validate(Definitions.candidate(Definitions.withDecision("a", 1L, Payloads.PlainRecord.class)));
            with.validate(Definitions.candidate(Definitions.withDecision("b", 1L, Payloads.Comment.class)));

            // Then: exactly one line says enabled and none says disabled
            assertThat(logs.supplementLines()).hasSize(1).allMatch(line -> line.contains("enabled"));
        }
    }

    // --- Helpers ---

    private PayloadSchemaRegistrationValidator validator() {
        return new PayloadSchemaRegistrationValidator(Profiles.builtIn(), Profiles.VERTIQUE, Optional.empty(), cache);
    }

    private PayloadSchemaRegistrationValidator validatorOver(PayloadSchemaGenerator generator) {
        return new PayloadSchemaRegistrationValidator(
                cache, generator, getClass().getClassLoader());
    }

    private CachedPayloadSchema entryOf(String typeName, String profile) {
        return cache.find(new SchemaKey(typeName, JsonProfileId.of(profile)))
                .orElseThrow(() -> new AssertionError("no cache entry for " + typeName + " under " + profile));
    }

    private static List<Throwable> causeChain(Throwable thrown) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = thrown; current != null && !chain.contains(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    /** A loader that delegates to its parent, records the requested names and can be told to fail. */
    private static final class RecordingLoader extends ClassLoader {

        final List<String> requested = new ArrayList<>();
        Error failure;

        RecordingLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            requested.add(name);
            if (failure != null) {
                throw failure;
            }
            return super.loadClass(name);
        }
    }
}
