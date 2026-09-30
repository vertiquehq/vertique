// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.OutputUnit;
import io.vertx.json.schema.SchemaRepository;
import io.vertx.json.schema.Validator;
import io.vertx.openapi.contract.OpenAPIContract;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Test-only OpenAPI 3.1 validator for documents produced by this module.
 *
 * <p>Two steps, both run on deep copies so the caller's document is never mutated (the contract
 * loader writes {@code __absolute_uri__} into the object it is given):
 *
 * <ol>
 *   <li>the document loads as an OpenAPI 3.1 contract (structure, required fields, security schemes);
 *   <li>every Schema Object (under {@code components.schemas}, and every {@code schema} of a
 *       parameter or media type) validates against the JSON Schema 2020-12 meta-schema, which the
 *       contract loader does not check.
 * </ol>
 *
 * <p>Use {@link #assertValid(JsonObject)} to fail a test with every problem found, or {@link
 * #validate(JsonObject)} to inspect the {@link Verdict}.
 */
public final class OpenApi31Toolchain {

    private static final String META_SCHEMA_URI = "https://json-schema.org/draft/2020-12/schema";
    private static final long TIMEOUT_SECONDS = 60;

    private OpenApi31Toolchain() {}

    /**
     * The outcome of validating one document.
     *
     * @param valid whether both validation steps accepted the document
     * @param problems one entry per problem; Schema Object problems start with the JSON pointer of
     *     the offending schema (for example {@code /components/schemas/Bad: ...}); empty when valid
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
     * @param vertx the instance used to load the contract and the meta-schema
     * @param document the document to check; not modified
     * @return the verdict
     */
    public static Verdict validate(Vertx vertx, JsonObject document) {
        JsonObject forContract = document.copy();
        JsonObject forSchemas = document.copy();
        List<String> problems = new ArrayList<>();

        try {
            Future<OpenAPIContract> loaded = OpenAPIContract.from(vertx, forContract);
            loaded.toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            problems.add("contract: " + failed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            problems.add("contract: interrupted");
        } catch (TimeoutException timedOut) {
            problems.add("contract: timed out");
        }

        SchemaRepository repository = SchemaRepository.create(new JsonSchemaOptions()
                        .setDraft(Draft.DRAFT202012)
                        .setBaseUri("https://vertique.local/")
                        .setOutputFormat(OutputFormat.Basic))
                .preloadMetaSchema(vertx.fileSystem(), Draft.DRAFT202012);
        Validator meta = repository.validator(META_SCHEMA_URI);
        Map<String, Object> schemas = new java.util.LinkedHashMap<>();
        collectSchemas(forSchemas, "", schemas);
        schemas.forEach((pointer, schema) -> {
            OutputUnit out = meta.validate(schema);
            if (!Boolean.TRUE.equals(out.getValid())) {
                problems.add(pointer + ": " + describe(out));
            }
        });
        return new Verdict(problems.isEmpty(), problems);
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
}
