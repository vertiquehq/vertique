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
import java.util.regex.Pattern;

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
 * <p>The contract loader (vertx-openapi) is stricter than OpenAPI 3.1.1 in two places, and only the
 * copy handed to it is adjusted before loading:
 *
 * <ul>
 *   <li>it rejects a relative server URL such as {@code /api}, which OpenAPI 3.1.1 allows (and which
 *       this module publishes as the mount path): every {@code servers[].url} without a URI scheme,
 *       at the root, on a path item or on an operation, is prefixed with the placeholder origin
 *       {@code https://toolchain.invalid};
 *   <li>it rejects an operation without {@code responses}, which OpenAPI 3.1.1 allows: such an
 *       operation receives a placeholder {@code default} response.
 * </ul>
 *
 * <p>The placeholders never hide a malformed value: an absolute server URL and a present {@code
 * responses} member are loaded exactly as given, so a malformed one is still rejected. The Schema
 * Object step validates its own untouched copy.
 *
 * <p>Use {@link #assertValid(JsonObject)} to fail a test with every problem found, or {@link
 * #validate(JsonObject)} to inspect the {@link Verdict}.
 */
public final class OpenApi31Toolchain {

    private static final String META_SCHEMA_URI = "https://json-schema.org/draft/2020-12/schema";
    private static final long TIMEOUT_SECONDS = 60;

    /** Origin prefixed to scheme-less server URLs in the contract-load copy only. */
    static final String PLACEHOLDER_ORIGIN = "https://toolchain.invalid";

    private static final Pattern URI_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");
    private static final List<String> OPERATION_METHODS =
            List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

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
        adaptForContractLoader(forContract);

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

    /**
     * Adjusts the contract-load copy for the two places where the loader is stricter than OpenAPI
     * 3.1.1: scheme-less server URLs get the placeholder origin, and operations without {@code
     * responses} get a placeholder default response. Present values are never replaced.
     */
    private static void adaptForContractLoader(JsonObject document) {
        anchorServerUrls(document.getValue("servers"));
        if (!(document.getValue("paths") instanceof JsonObject paths)) {
            return;
        }
        for (String path : paths.fieldNames()) {
            if (!(paths.getValue(path) instanceof JsonObject pathItem)) {
                continue;
            }
            anchorServerUrls(pathItem.getValue("servers"));
            for (String method : OPERATION_METHODS) {
                if (!(pathItem.getValue(method) instanceof JsonObject operation)) {
                    continue;
                }
                anchorServerUrls(operation.getValue("servers"));
                if (!operation.containsKey("responses")) {
                    operation.put(
                            "responses",
                            new JsonObject().put("default", new JsonObject().put("description", "placeholder")));
                }
            }
        }
    }

    private static void anchorServerUrls(Object servers) {
        if (!(servers instanceof JsonArray array)) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            if (array.getValue(i) instanceof JsonObject server
                    && server.getValue("url") instanceof String url
                    && !URI_SCHEME.matcher(url).lookingAt()) {
                server.put("url", url.startsWith("/") ? PLACEHOLDER_ORIGIN + url : PLACEHOLDER_ORIGIN + "/" + url);
            }
        }
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
