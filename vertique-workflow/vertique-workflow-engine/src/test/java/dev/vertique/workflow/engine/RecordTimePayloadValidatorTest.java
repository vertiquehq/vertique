// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies encoded byte and container depth bounds without exposing values or overflowing the stack. */
class RecordTimePayloadValidatorTest {

    @Test
    @DisplayName("record-time payloads respect the encoded byte and container depth bounds")
    void payloadOverTheSizeOrDepthBoundIsRefused() {
        // Given a 1024-byte limit and payloads measured in encoded JSON bytes, including UTF-8 and escapes
        var validator = new RecordTimePayloadValidator(new WorkflowRecordingConfig(5000L, 1024));
        JsonObject atSizeLimit = Payloads.ofSize(1024);
        JsonObject overSizeLimit = Payloads.ofSize(1025);
        assertThat(Json.encodeToBuffer(atSizeLimit).length()).isEqualTo(1024);
        assertThat(Json.encodeToBuffer(overSizeLimit).length()).isEqualTo(1025);

        // When each size boundary and each kind of nested container is checked
        // Then the boundaries pass and every excess is a typed, value-free refusal
        assertAll(
                "record-time payload bounds",
                () -> assertDoesNotThrow(() -> validator.validate(atSizeLimit), "1024 encoded bytes are accepted"),
                () -> assertRefused(validator, overSizeLimit, "1025 encoded bytes", "maxPayloadBytes"),
                () -> assertDepthBounds(validator, Kind.OBJECT),
                () -> assertDepthBounds(validator, Kind.ARRAY));
    }

    @Test
    @DisplayName("a schema with a non-local $ref is refused before it is compiled or any reference is resolved")
    void schemaWithNonLocalRefIsRefusedBeforeCompilation() {
        // Given schemas whose $ref is local, remote, a relative file, or nested remote inside properties, and a
        // compiler that stands for any resolver and fails the test when it is reached
        JsonObject payload = new JsonObject().put("a", 1);
        JsonObject local = new JsonObject(
                "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/$defs/x\"}},\"$defs\":{\"x\":{\"type\":\"integer\"}}}");
        JsonObject remote = new JsonObject("{\"$ref\":\"https://example.test/s.json\"}");
        JsonObject relative = new JsonObject("{\"$ref\":\"other.json#/a\"}");
        JsonObject nested = new JsonObject(
                "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"https://example.test/s.json\"}}}");
        AtomicInteger resolverCalls = new AtomicInteger();
        var validator = new RecordTimePayloadValidator(new WorkflowRecordingConfig(5000L, 1024), schema -> {
            resolverCalls.incrementAndGet();
            throw new AssertionError("a non-local $ref must be refused before compilation");
        });
        var realCompiler = new RecordTimePayloadValidator(new WorkflowRecordingConfig(5000L, 1024));

        // When a valid payload is validated against each
        // Then the local schema validates it and the others are refused naming the subject, without any resolution
        assertAll(
                "non-local $ref",
                () -> assertDoesNotThrow(
                        () -> realCompiler.validate(payload, local, "decision 'approve'"),
                        "a local $ref is resolved within the schema"),
                () -> assertNonLocalRef(validator, payload, remote, "remote $ref"),
                () -> assertNonLocalRef(validator, payload, relative, "relative $ref"),
                () -> assertNonLocalRef(validator, payload, nested, "nested remote $ref"),
                () -> assertThat(resolverCalls).as("resolver calls").hasValue(0));
    }

    private static void assertNonLocalRef(
            RecordTimePayloadValidator validator, Object payload, JsonObject schema, String description) {
        WorkflowInvalidPayloadException refusal = assertThrows(
                WorkflowInvalidPayloadException.class,
                () -> validator.validate(payload, schema, "decision 'approve'"),
                description + " is refused");
        assertThat(refusal.getMessage())
                .as(description + " names the subject and the reason")
                .contains("decision 'approve'", "non-local $ref");
    }

    @Test
    @DisplayName("compiled schemas are cached by the hash of their canonical JSON within the configured bound")
    void compiledSchemasAreCachedBySchemaHashWithinTheBound() {
        // Given a cache of two entries and schemas A, A' (A with its keys in another order), B and C
        CountingCompiler compiler = new CountingCompiler();
        var validator = new RecordTimePayloadValidator(new WorkflowRecordingConfig(5000L, 1024, 100, 2), compiler);
        JsonObject a = new JsonObject()
                .put("type", "object")
                .put(
                        "properties",
                        new JsonObject()
                                .put("x", new JsonObject().put("type", "integer"))
                                .put("y", new JsonObject().put("type", "string")));
        JsonObject aReordered = new JsonObject()
                .put(
                        "properties",
                        new JsonObject()
                                .put("y", new JsonObject().put("type", "string"))
                                .put("x", new JsonObject().put("type", "integer")))
                .put("type", "object");
        JsonObject b = new JsonObject().put("type", "object").put("required", new JsonArray().add("b"));
        JsonObject c = new JsonObject().put("type", "object").put("required", new JsonArray().add("c"));
        JsonObject payload = new JsonObject().put("x", 1);

        // When payloads are validated against A, A', B, C and then A again
        List<Integer> compilations = new ArrayList<>();
        List<Integer> cached = new ArrayList<>();
        for (JsonObject schema : List.of(a, aReordered, b, c, a)) {
            validator.validate(payload, schema, "decision 'approve'");
            compilations.add(compiler.compilations());
            cached.add(validator.schemaCacheSize());
        }

        // Then A' hits A's entry, C evicts the oldest entry, A compiles again and the cache never exceeds two entries
        assertThat(compilations).containsExactly(1, 1, 2, 3, 4);
        assertThat(cached).allSatisfy(size -> assertThat(size).isLessThanOrEqualTo(2));

        // And the cache bound is configuration: at least one entry, 256 by default
        ConfigurationException refused =
                assertThrows(ConfigurationException.class, () -> new WorkflowRecordingConfig(5000L, 1024, 100, 0));
        assertThat(refused.getMessage()).contains("workflow.recording.schemaCacheMaxEntries");
        assertThat(WorkflowRecordingConfig.defaults().schemaCacheMaxEntries()).isEqualTo(256);
        assertThat(new WorkflowRecordingConfig(5000L, 1024).schemaCacheMaxEntries())
                .isEqualTo(256);
    }

    /** A schema compiler that accepts every payload and counts how often it was asked to compile. */
    private static final class CountingCompiler implements RecordTimePayloadValidator.SchemaCompiler {

        private final AtomicInteger compilations = new AtomicInteger();

        @Override
        public RecordTimePayloadValidator.CompiledSchema compile(JsonObject schema) {
            compilations.incrementAndGet();
            return payload -> List.of();
        }

        int compilations() {
            return compilations.get();
        }
    }

    /** Checks both sides of the depth boundary and a chain too deep for a recursive walk. */
    private static void assertDepthBounds(RecordTimePayloadValidator validator, Kind kind) {
        Object atDepthLimit = Payloads.nested(64, kind);
        Object overDepthLimit = Payloads.nested(65, kind);
        Object deeplyNested = Payloads.nested(100_000, kind);

        assertAll(
                kind + " nesting",
                () -> assertDoesNotThrow(() -> validator.validate(atDepthLimit), kind + " depth 64 is accepted"),
                () -> assertRefused(validator, overDepthLimit, kind + " depth 65", "depth", "64"),
                () -> assertRefused(validator, deeplyNested, kind + " depth 100000", "depth", "64"));
    }

    /** Requires the named bound and checks the whole cause chain for values and stack overflow. */
    private static void assertRefused(
            RecordTimePayloadValidator validator, Object payload, String description, String... boundFragments) {
        WorkflowInvalidPayloadException refusal = assertThrows(
                WorkflowInvalidPayloadException.class, () -> validator.validate(payload), description + " is refused");
        assertThat(refusal.getMessage()).as(description + " names its bound").contains(boundFragments);
        for (Throwable failure = refusal; failure != null; failure = failure.getCause()) {
            assertThat(failure)
                    .as(description + " never overflows the stack")
                    .isNotInstanceOf(StackOverflowError.class);
            if (failure.getMessage() != null) {
                assertThat(failure.getMessage())
                        .as(description + " never exposes the submitted value")
                        .doesNotContain(Payloads.VALUE);
            }
        }
        assertThat(refusal.violations().toString()).doesNotContain(Payloads.VALUE);
    }

    private enum Kind {
        OBJECT,
        ARRAY
    }

    private static final class Payloads {

        private static final String VALUE = "private-payload-marker";

        /** Builds a shallow value of exactly the requested encoded JSON byte length. */
        private static JsonObject ofSize(int encodedBytes) {
            String value = VALUE + "\u00e9\"\\\n";
            JsonObject payload = new JsonObject().put("value", value);
            int paddingBytes = encodedBytes - Json.encodeToBuffer(payload).length();
            return payload.put("value", value + "x".repeat(paddingBytes));
        }

        /** Wraps a scalar iteratively; every object or array contributes exactly one depth level. */
        private static Object nested(int depth, Kind kind) {
            Object payload = VALUE;
            for (int level = 0; level < depth; level++) {
                payload = switch (kind) {
                    case OBJECT -> new JsonObject().put("child", payload);
                    case ARRAY -> new JsonArray().add(payload);
                };
            }
            return payload;
        }
    }
}
