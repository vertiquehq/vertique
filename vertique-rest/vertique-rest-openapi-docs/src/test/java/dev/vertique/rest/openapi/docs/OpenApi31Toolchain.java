// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.OutputUnit;
import io.vertx.json.schema.SchemaRepository;
import io.vertx.json.schema.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only OpenAPI 3.1 validator for documents produced by this module.
 *
 * <p>Two steps, both run on deep copies so the caller's document is never mutated (the JSON Schema
 * validator writes {@code __absolute_uri__} into the objects it compiles):
 *
 * <ol>
 *   <li>the document validates against the official OpenAPI 3.1 JSON Schema ({@code
 *       https://spec.openapis.org/oas/3.1/schema/2022-10-07}), which covers the document structure:
 *       required members, Reference Object form, Paths keys, server URLs, parameters, responses and
 *       specification extensions. Problems from this step start with {@code contract:};
 *   <li>every Schema Object (under {@code components.schemas}, and every {@code schema} of a
 *       parameter or media type) validates against the JSON Schema 2020-12 meta-schema. The official
 *       document schema only requires a Schema Object to be an object or a boolean, so this step
 *       checks its content. Problems from this step start with the JSON pointer of the schema.
 * </ol>
 *
 * <p>The official schema is read from the test classpath, where the vertx-openapi test dependency
 * ships it under {@code spec.openapis.org/oas/3.1/schema/2022-10-07}. Its sibling {@code
 * schema-base} variant is deliberately not used: it pins {@code jsonSchemaDialect} and every
 * Schema Object's {@code $schema} to the OpenAPI base dialect, while this module publishes the
 * JSON Schema 2020-12 dialect.
 *
 * <p>Use {@link #assertValid(JsonObject)} to fail a test with every problem found, or {@link
 * #validate(JsonObject)} to inspect the {@link Verdict}.
 */
public final class OpenApi31Toolchain {

    private static final String META_SCHEMA_URI = "https://json-schema.org/draft/2020-12/schema";
    private static final String OPENAPI_SCHEMA_URI = "https://spec.openapis.org/oas/3.1/schema/2022-10-07";
    private static final String OPENAPI_SCHEMA_RESOURCE = "spec.openapis.org/oas/3.1/schema/2022-10-07";

    /** The official schema, parsed once; only copies are handed to the validator. */
    private static final JsonObject OPENAPI_SCHEMA = loadResource(OPENAPI_SCHEMA_RESOURCE);

    private OpenApi31Toolchain() {}

    /**
     * The outcome of validating one document.
     *
     * @param valid whether both validation steps accepted the document
     * @param problems one entry per problem; document structure problems start with {@code
     *     contract:}, Schema Object problems start with the JSON pointer of the offending schema
     *     (for example {@code /components/schemas/Bad: ...}); empty when valid
     */
    public record Verdict(boolean valid, List<String> problems) {
        public Verdict {
            problems = List.copyOf(problems);
        }
    }

    /**
     * Fails with every problem found when the document is not a valid OpenAPI 3.1 document.
     *
     * @param document the document to check; not modified
     * @throws AssertionError when the document is invalid
     */
    public static void assertValid(JsonObject document) {
        Verdict verdict = validate(document);
        if (!verdict.valid()) {
            throw new AssertionError("Invalid OpenAPI 3.1 document: " + verdict.problems());
        }
    }

    /**
     * Validates a copy of the document, on a private Vert.x instance that is closed before returning.
     *
     * @param document the document to check; not modified
     * @return the verdict
     */
    public static Verdict validate(JsonObject document) {
        Vertx vertx = Vertx.vertx();
        try {
            return validate(vertx, document);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().join();
        }
    }

    /**
     * Validates a copy of the document on the given Vert.x instance.
     *
     * @param vertx the instance whose file system loads the JSON Schema 2020-12 meta-schema
     * @param document the document to check; not modified
     * @return the verdict
     */
    public static Verdict validate(Vertx vertx, JsonObject document) {
        List<String> problems = new ArrayList<>();
        SchemaRepository repository = SchemaRepository.create(new JsonSchemaOptions()
                        .setDraft(Draft.DRAFT202012)
                        .setBaseUri("https://vertique.local/")
                        .setOutputFormat(OutputFormat.Basic))
                .preloadMetaSchema(vertx.fileSystem(), Draft.DRAFT202012);
        repository.dereference(OPENAPI_SCHEMA_URI, JsonSchema.of(OPENAPI_SCHEMA.copy()));

        OutputUnit structure = repository.validator(OPENAPI_SCHEMA_URI).validate(document.copy());
        if (!Boolean.TRUE.equals(structure.getValid())) {
            problems.addAll(contractProblems(structure));
        }

        Validator meta = repository.validator(META_SCHEMA_URI);
        Map<String, Object> schemas = new LinkedHashMap<>();
        collectSchemas(document.copy(), "", schemas);
        schemas.forEach((pointer, schema) -> {
            OutputUnit out = meta.validate(schema);
            if (!Boolean.TRUE.equals(out.getValid())) {
                problems.add(pointer + ": " + describe(out));
            }
        });
        return new Verdict(problems.isEmpty(), problems);
    }

    private static List<String> contractProblems(OutputUnit out) {
        List<String> found = new ArrayList<>();
        if (out.getErrors() != null) {
            for (OutputUnit error : out.getErrors()) {
                found.add("contract: " + error.getInstanceLocation() + " " + error.getError());
            }
        }
        if (found.isEmpty()) {
            found.add("contract: " + out.getInstanceLocation() + " " + out.getError());
        }
        return found;
    }

    private static String describe(OutputUnit out) {
        if (out.getErrors() == null || out.getErrors().isEmpty()) {
            return "not a valid 2020-12 schema";
        }
        List<String> parts = new ArrayList<>();
        for (OutputUnit error : out.getErrors()) {
            parts.add(error.getInstanceLocation() + " " + error.getError());
        }
        return String.join("; ", parts);
    }

    /** Finds Schema Objects: {@code components.schemas.*} and every {@code schema} member. */
    private static void collectSchemas(Object node, String pointer, Map<String, Object> found) {
        if (node instanceof JsonObject object) {
            for (String key : object.fieldNames()) {
                if (key.startsWith("x-") || key.equals("example") || key.equals("examples")) {
                    continue;
                }
                Object child = object.getValue(key);
                String childPointer = pointer + "/" + key.replace("~", "~0").replace("/", "~1");
                if (key.equals("schema") && isSchemaShaped(child)) {
                    found.put(childPointer, child);
                } else if (pointer.equals("/components") && key.equals("schemas") && child instanceof JsonObject all) {
                    for (String name : all.fieldNames()) {
                        found.put(childPointer + "/" + name.replace("~", "~0").replace("/", "~1"), all.getValue(name));
                    }
                } else {
                    collectSchemas(child, childPointer, found);
                }
            }
        } else if (node instanceof JsonArray array) {
            for (int i = 0; i < array.size(); i++) {
                collectSchemas(array.getValue(i), pointer + "/" + i, found);
            }
        }
    }

    private static boolean isSchemaShaped(Object value) {
        return value instanceof JsonObject || value instanceof Boolean;
    }

    private static JsonObject loadResource(String name) {
        ClassLoader loader = OpenApi31Toolchain.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Missing test classpath resource " + name + " (shipped by the vertx-openapi test dependency)");
            }
            return new JsonObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failed) {
            throw new UncheckedIOException("Cannot read test classpath resource " + name, failed);
        }
    }
}
