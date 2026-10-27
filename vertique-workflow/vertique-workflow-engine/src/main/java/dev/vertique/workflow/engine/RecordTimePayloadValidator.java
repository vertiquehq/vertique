// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.json.schema.validation.JsonSchemaValidationOptions;
import dev.vertique.json.schema.validation.PathForm;
import dev.vertique.json.schema.validation.PatternInputGuard;
import dev.vertique.json.schema.validation.ViolationDetails;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.json.schema.OutputUnit;
import io.vertx.json.schema.Validator;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Record-time checks over encoded JSON that need no lock and no plan.
 *
 * <p>The bounds are pure: an iterative container walk before encoding, then a streaming encode that counts bytes and
 * container depth as they are produced and stops as soon as a bound is crossed, so an oversized value is never fully
 * encoded in memory.
 *
 * <p>The schema check validates a payload against a JSON Schema the caller read from a stored row. A schema with a
 * non-local {@code $ref} is refused before it is compiled, so no reference is ever resolved from a file or the network.
 * Compiled schemas are kept in a cache bounded by {@code workflow.recording.schemaCacheMaxEntries} and keyed by the
 * SHA-256 of the schema's canonical (key-sorted) JSON, so equal schemas share an entry whatever their key order; the
 * oldest-used entry is evicted first. The validator is safe for concurrent use.
 *
 * <p>INTERNAL: framework wiring between the recording paths of the engine and the executor package. It is not
 * application API.
 */
public final class RecordTimePayloadValidator {

    private static final Logger log = LoggerFactory.getLogger(RecordTimePayloadValidator.class);

    private static final int MAX_DEPTH = 64;

    /** Keywords whose value is data, not a schema. */
    private static final Set<String> LITERAL_KEYWORDS = Set.of("const", "enum", "default", "examples", "example");

    /** Keywords whose members are schemas, whatever the member is named. */
    private static final Set<String> SCHEMA_CONTAINERS =
            Set.of("properties", "patternProperties", "$defs", "definitions", "dependentSchemas", "dependencies");

    /** Keywords that refer to a schema by address. */
    private static final Set<String> REFERENCE_KEYWORDS = Set.of("$ref", "$dynamicRef");

    /** Compiles a JSON Schema into something that can be asked for the violations of a payload. */
    @FunctionalInterface
    public interface SchemaCompiler {

        /**
         * Compiles a schema.
         *
         * @param schema a schema without a non-local reference
         * @return the compiled schema
         */
        CompiledSchema compile(JsonObject schema);
    }

    /** A compiled schema. */
    @FunctionalInterface
    public interface CompiledSchema {

        /**
         * Validates a payload.
         *
         * @param payload the payload as a JSON value
         * @return the violations, value-free; empty when the payload satisfies the schema
         */
        List<ViolationDetail> violations(Object payload);
    }

    private final int maxPayloadBytes;
    private final SchemaCompiler compiler;
    private final Map<String, CompiledSchema> compiled;

    /**
     * Creates a validator using the recording payload-size limit and the schema cache bound, compiling schemas with
     * the framework's JSON Schema validation.
     *
     * @param config validated recording configuration
     */
    public RecordTimePayloadValidator(WorkflowRecordingConfig config) {
        this(config, RecordTimePayloadValidator::compileGuarded);
    }

    /**
     * Creates a validator that compiles schemas with the given compiler.
     *
     * @param config   validated recording configuration
     * @param compiler compiles a schema that has passed the reference check
     */
    public RecordTimePayloadValidator(WorkflowRecordingConfig config, SchemaCompiler compiler) {
        this.maxPayloadBytes = config.maxPayloadBytes();
        this.compiler = compiler;
        int maxEntries = config.schemaCacheMaxEntries();
        this.compiled = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CompiledSchema> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /**
     * The number of compiled schemas the cache holds now.
     *
     * @return the cache size
     */
    int schemaCacheSize() {
        synchronized (compiled) {
            return compiled.size();
        }
    }

    /**
     * Checks the bounds of a payload and then its conformance to a schema, without I/O, locks or transactions.
     *
     * @param payload the JSON value to check, including {@code null}
     * @param schema  the JSON Schema it must satisfy
     * @param subject what the payload is for, named in a refusal (for example {@code decision 'approve'}); never a
     *                value taken from the payload
     * @throws WorkflowInvalidPayloadException if a bound is exceeded, the schema has a non-local {@code $ref}, or the
     *     payload violates the schema
     */
    public void validate(@Nullable Object payload, JsonObject schema, String subject) {
        validate(payload);
        if (hasNonLocalReference(schema)) {
            throw invalid(subject + " is refused: its schema has a non-local $ref");
        }
        CompiledSchema schemaToCheck = compiledFor(schema, subject);
        Object json = payload == null ? null : Json.decodeValue(Json.encode(payload));
        List<ViolationDetail> violations = schemaToCheck.violations(json);
        if (!violations.isEmpty()) {
            throw new WorkflowInvalidPayloadException(subject + " payload does not satisfy its schema", violations);
        }
    }

    private CompiledSchema compiledFor(JsonObject schema, String subject) {
        String key = hashOf(schema);
        synchronized (compiled) {
            CompiledSchema cached = compiled.get(key);
            if (cached != null) {
                return cached;
            }
        }
        CompiledSchema fresh;
        try {
            fresh = compiler.compile(schema);
        } catch (RuntimeException failure) {
            // A stored schema that cannot be compiled is not the caller's fault: the executor validates against the
            // plan when it applies the completion, so the early refusal is skipped for this schema.
            log.warn(
                    "The schema of {} cannot be compiled; record-time schema validation is skipped ({})",
                    subject,
                    failure.getClass().getName());
            fresh = ignored -> List.of();
        }
        synchronized (compiled) {
            compiled.put(key, fresh);
        }
        return fresh;
    }

    /** The SHA-256 of the schema's canonical JSON: object keys sorted, arrays in order. */
    private static String hashOf(JsonObject schema) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Json.encode(canonical(schema)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static Object canonical(Object value) {
        return switch (value) {
            case JsonObject object -> {
                Map<String, Object> sorted = new TreeMap<>();
                object.getMap().forEach((name, member) -> sorted.put(name, canonical(member)));
                yield sorted;
            }
            case Map<?, ?> map -> {
                Map<String, Object> sorted = new TreeMap<>();
                map.forEach((name, member) -> sorted.put(String.valueOf(name), canonical(member)));
                yield sorted;
            }
            case JsonArray array -> {
                List<Object> members = new ArrayList<>(array.size());
                array.getList().forEach(member -> members.add(canonical(member)));
                yield members;
            }
            case List<?> list -> {
                List<Object> members = new ArrayList<>(list.size());
                list.forEach(member -> members.add(canonical(member)));
                yield members;
            }
            case null, default -> value;
        };
    }

    /**
     * Whether any schema position of the document carries a {@code $ref} or {@code $dynamicRef} that does not address
     * the document itself. The walk visits every schema position, does not enter literal keywords and reads the members
     * of the schema containers as schemas whatever they are named.
     */
    private static boolean hasNonLocalReference(JsonObject schema) {
        Deque<Object> pending = new ArrayDeque<>();
        pending.push(schema);
        while (!pending.isEmpty()) {
            Object node = pending.pop();
            if (node instanceof JsonArray array) {
                array.forEach(member -> {
                    if (member instanceof JsonObject || member instanceof JsonArray) {
                        pending.push(member);
                    }
                });
            } else if (node instanceof JsonObject object) {
                for (Map.Entry<String, Object> entry : object) {
                    String keyword = entry.getKey();
                    Object value = entry.getValue();
                    if (REFERENCE_KEYWORDS.contains(keyword)) {
                        if (value instanceof String reference && !reference.startsWith("#")) {
                            return true;
                        }
                    } else if (LITERAL_KEYWORDS.contains(keyword)) {
                        continue;
                    } else if (SCHEMA_CONTAINERS.contains(keyword) && value instanceof JsonObject members) {
                        for (Map.Entry<String, Object> member : members) {
                            if (member.getValue() instanceof JsonObject || member.getValue() instanceof JsonArray) {
                                pending.push(member.getValue());
                            }
                        }
                    } else if (value instanceof JsonObject || value instanceof JsonArray) {
                        pending.push(value);
                    }
                }
            }
        }
        return false;
    }

    /** Compiles with the pattern-input guard, so a pattern keyword cannot run on unbounded input. */
    private static CompiledSchema compileGuarded(JsonObject schema) {
        PatternInputGuard guard =
                new PatternInputGuard(PatternInputGuard.DEFAULT_MAX_CHARS, PatternInputGuard.DEFAULT_MAX_TOTAL_CHARS);
        Validator validator = guard.compile(schema, JsonSchemaValidationOptions.draft202012());
        return payload -> {
            OutputUnit result;
            try (var window = guard.openWindow()) {
                result = validator.validate(payload);
            } catch (RuntimeException thrown) {
                return ViolationDetails.patternInputBound(thrown).map(List::of).orElseThrow(() -> thrown);
            }
            return ViolationDetails.collect(result, schema, false, PathForm.RFC6901);
        };
    }

    /**
     * Checks encoded UTF-8 JSON size and container depth without I/O, locks or transactions.
     *
     * <p>Objects and arrays each count one level; scalars, including {@code null}, count none.
     * Refusals carry only framework messages, never submitted values or encoder causes.
     *
     * @param payload the JSON value to check, including {@code null}
     * @throws WorkflowInvalidPayloadException if the encoded value exceeds the configured byte limit,
     *     exceeds 64 container levels or cannot be encoded as JSON
     */
    public void validate(@Nullable Object payload) {
        validateDepth(payload);
        BoundedSink sink = new BoundedSink(maxPayloadBytes);
        try {
            DatabindCodec.mapper().writeValue(sink, payload);
        } catch (IOException | RuntimeException failure) {
            if (sink.tooLarge) {
                throw invalid("payload exceeds maxPayloadBytes " + maxPayloadBytes);
            }
            if (sink.tooDeep) {
                throw invalid("payload exceeds depth " + MAX_DEPTH);
            }
            throw invalid("payload cannot be encoded as JSON");
        }
    }

    /** Rejects deep JSON containers before a recursive codec can visit them; retains only the ancestor iterators. */
    private static void validateDepth(@Nullable Object payload) {
        Deque<Iterator<?>> ancestors = new ArrayDeque<>();
        Object value = payload;
        while (true) {
            Iterator<?> children = children(value);
            if (children != null) {
                if (ancestors.size() >= MAX_DEPTH) {
                    throw invalid("payload exceeds depth " + MAX_DEPTH);
                }
                ancestors.push(children);
            }
            while (!ancestors.isEmpty() && !ancestors.peek().hasNext()) {
                ancestors.pop();
            }
            if (ancestors.isEmpty()) {
                return;
            }
            value = ancestors.peek().next();
        }
    }

    /** Returns children without copying containers or normalizing their nested values. */
    @Nullable
    private static Iterator<?> children(@Nullable Object value) {
        return switch (value) {
            case JsonObject object -> object.getMap().values().iterator();
            case JsonArray array -> array.getList().iterator();
            case Map<?, ?> map -> map.values().iterator();
            case Iterable<?> iterable -> iterable.iterator();
            case Object[] array -> Arrays.asList(array).iterator();
            case null, default -> null;
        };
    }

    /** Counts encoded bytes and the codec's actual containers as they are written, and refuses once a bound is crossed. */
    private static final class BoundedSink extends OutputStream {

        private final int maxBytes;
        private long written;
        private int depth;
        private boolean inString;
        private boolean escaped;
        boolean tooLarge;
        boolean tooDeep;

        BoundedSink(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int value) throws IOException {
            if (++written > maxBytes) {
                tooLarge = true;
                throw new IOException("payload exceeds the size bound");
            }
            observe((byte) value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            for (int index = 0; index < length; index++) {
                write(bytes[offset + index]);
            }
        }

        private void observe(byte value) throws IOException {
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    inString = false;
                }
            } else if (value == '"') {
                inString = true;
            } else if (value == '{' || value == '[') {
                if (++depth > MAX_DEPTH) {
                    tooDeep = true;
                    throw new IOException("payload exceeds the depth bound");
                }
            } else if (value == '}' || value == ']') {
                depth--;
            }
        }
    }

    /** Builds an unlocated, value-free refusal without retaining a submitted value in a cause. */
    private static WorkflowInvalidPayloadException invalid(String message) {
        return new WorkflowInvalidPayloadException(message, List.of(ViolationDetail.unlocated(message, "payload")));
    }
}
