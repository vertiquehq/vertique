// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import dev.vertique.rest.openapi.docs.ConformanceCorpusTestComponents.Served;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.conformance.corpus.CorpusDocuments;
import dev.vertique.rest.openapi.docs.fixture.conformance.support.Deployments;
import dev.vertique.rest.openapi.docs.fixture.conformance.support.DocumentRequests;
import dev.vertique.rest.openapi.docs.fixture.conformance.support.DocumentRequests.Answer;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching.CachingModules;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.client.WebClient;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.Validator;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every document assembled from the conformance corpus is a valid OpenAPI 3.1 document, and every
 * regular expression it publishes compiles in the OpenAPI 3.1 test toolchain.
 *
 * <p>The corpus is four applications, each declared once with a public document and once with a
 * document protected by {@code bearerAuth}: {@code corpus} (the request-body corpus of creator,
 * setter, and builder bound types, aliases, profiles, recursive and mutually referencing graphs, map
 * values, a self-referencing map, a member-level closure, and {@code Optional}-valued extras), {@code
 * patterns} (a case-insensitively bound body, an authored pattern with the {@code DOTALL} and {@code
 * COMMENTS} flags, and authored path and query parameter patterns), {@code responses} (output bodies
 * under a non-default profile, dynamic and declared responses, honored hiding markers, a hidden
 * operation), and {@code schemes} (one operation per described security scheme kind). Four
 * components run them: the public and the protected declarations of one application never share a
 * composition, and only the {@code patterns} components bind a real Bean Validation {@code
 * Validator}, which renders the authored flags. All four deploy once, on 127.0.0.1 with port 0, for
 * the class; the client is closed and every deployment undeployed after it.
 *
 * <p>Both tests are parameterized over the {@code (document, mode)} rows. A failure names the row
 * and the JSON Pointer of each failing position; the test's own messages never echo a published
 * pattern or schema (the toolchain's problem list is reported as the toolchain words it).
 */
@ExtendWith(VertxExtension.class)
// 60 s rather than the 20 s default: four component graphs, each with its JWT provider, deploy before
// the tests, and every row validates its document against the official OpenAPI 3.1 schema.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class OpenApiConformanceCorpusIT {

    private static final Logger LOG = LoggerFactory.getLogger(OpenApiConformanceCorpusIT.class);

    /** The {@code openapi} value every generated document declares. */
    private static final String OPENAPI_VERSION = "3.1.1";

    /** The portable end anchor that ends every framework-generated case-fold pattern. */
    private static final String PORTABLE_END_ANCHOR = "(?![\\s\\S])";

    /** How a framework-generated case-fold {@code patternProperties} key starts. */
    private static final String FOLD_KEY_START = "^(?!";

    /** The end anchor no published pattern may carry: it is not portable across dialects. */
    private static final String JAVA_END_ANCHOR = "\\z";

    /** The case-fold key the generator publishes for the case-insensitively bound member {@code title}. */
    private static final String TITLE_FOLD_KEY = "^(?!title(?![\\s\\S]))[tT][iI][tT][lL][eE](?![\\s\\S])";

    /**
     * The published text of the authored pattern {@code ^a.b # c$} with the flags {@code DOTALL} and
     * {@code COMMENTS}: the flags as an inline modifier group, with a line break before the closing
     * parenthesis, because in comments mode {@code #} comments out the rest of the line.
     */
    private static final String FLAGGED_PATTERN = "(?sx:^a.b # c$\n)";

    /** Where the authored flagged pattern sits, relative to its body schema. */
    private static final String FLAGGED_POINTER_SUFFIX = "/properties/expression/pattern";

    /** The authored pattern of the {@code id} path parameter of the patterns application. */
    private static final String PARAMETER_PATTERN = "^[0-9]+$";

    /** The authored pattern of a nested member of the request-body corpus (a five-digit code). */
    private static final String CORPUS_MEMBER_PATTERN = "^[0-9]{5}$";

    /** The keywords whose value is one subschema. */
    private static final Set<String> SINGLE_SCHEMA_KEYWORDS = Set.of(
            "additionalProperties",
            "items",
            "not",
            "propertyNames",
            "if",
            "then",
            "else",
            "contains",
            "unevaluatedItems",
            "unevaluatedProperties",
            "contentSchema");

    /** The keywords whose value is an array of subschemas. */
    private static final Set<String> SCHEMA_ARRAY_KEYWORDS = Set.of("prefixItems", "allOf", "anyOf", "oneOf");

    /** The keywords whose value is an object of subschemas, each member a schema whatever its name. */
    private static final Set<String> SCHEMA_MAP_KEYWORDS =
            Set.of("properties", "patternProperties", "$defs", "dependentSchemas");

    /** The vertx-json-schema options every pattern is compiled under. */
    private static final JsonSchemaOptions COMPILE_OPTIONS = new JsonSchemaOptions()
            .setDraft(Draft.DRAFT202012)
            .setBaseUri("https://vertique.local/")
            .setOutputFormat(OutputFormat.Basic);

    /** How a document is served. */
    enum Mode {
        /** Served to anyone. */
        PUBLIC,
        /** Served to callers authenticated by {@code bearerAuth}. */
        PROTECTED
    }

    /** Which pair of components runs a document's application. */
    enum Composition {
        /** {@code corpus}, {@code responses}, and {@code schemes}. */
        MAIN,
        /** {@code patterns}, with a real Bean Validation {@code Validator}. */
        PATTERNS
    }

    /**
     * Which positive controls a document's published patterns must hold.
     *
     * <ul>
     *   <li>{@code PATTERNS}: the case-fold key of {@code title}, the flagged pattern's published
     *       text, and the authored {@code id} path parameter pattern in a Parameter Object's schema;
     *   <li>{@code CORPUS}: the authored five-digit pattern of a nested member;
     *   <li>{@code NONE}: no control; the count is recorded only.
     * </ul>
     */
    enum Controls {
        PATTERNS,
        CORPUS,
        NONE
    }

    /**
     * One document in one mode.
     *
     * @param document    the document (application) name
     * @param mode        how it is served
     * @param composition which components run it
     * @param controls    the positive controls its patterns must hold
     */
    record Row(String document, Mode mode, Composition composition, Controls controls) {

        @Override
        public String toString() {
            return document + " (" + mode.name().toLowerCase(Locale.ROOT) + ")";
        }
    }

    /**
     * One regular expression in a schema position of a document: a {@code pattern} value, or a
     * {@code patternProperties} key.
     *
     * @param pointer the JSON Pointer of the {@code pattern} member, or of the subschema under the key
     * @param text    the regular expression's text
     * @param key     whether it is a {@code patternProperties} key
     */
    record PatternPair(String pointer, String text, boolean key) {}

    private static Vertx vertx;
    private static WebClient client;
    private static String bearerToken;
    private static final List<String> DEPLOYMENT_IDS = new ArrayList<>();
    private static final Map<Composition, Map<Mode, Integer>> PORTS = new EnumMap<>(Composition.class);

    static Stream<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            rows.add(new Row(CorpusDocuments.CORPUS, mode, Composition.MAIN, Controls.CORPUS));
            rows.add(new Row(CorpusDocuments.PATTERNS, mode, Composition.PATTERNS, Controls.PATTERNS));
            rows.add(new Row(CorpusDocuments.RESPONSES, mode, Composition.MAIN, Controls.NONE));
            rows.add(new Row(CorpusDocuments.SCHEMES, mode, Composition.MAIN, Controls.NONE));
        }
        return rows.stream();
    }

    @BeforeAll
    static void deployTheCorpus(Vertx sharedVertx) throws Exception {
        vertx = sharedVertx;
        JWTAuth tokens = JwtAuthFactory.fromSymmetricKey(vertx, "HS256", CachingModules.SIGNING_KEY);
        bearerToken = tokens.generateToken(new JsonObject().put("sub", "conformance-reader"));
        deploy(
                Composition.MAIN,
                Mode.PUBLIC,
                DaggerConformanceCorpusTestComponents_PublicMainComponent.factory()
                        .create(vertx, config()));
        deploy(
                Composition.MAIN,
                Mode.PROTECTED,
                DaggerConformanceCorpusTestComponents_ProtectedMainComponent.factory()
                        .create(vertx, config()));
        deploy(
                Composition.PATTERNS,
                Mode.PUBLIC,
                DaggerConformanceCorpusTestComponents_PublicPatternsComponent.factory()
                        .create(vertx, config()));
        deploy(
                Composition.PATTERNS,
                Mode.PROTECTED,
                DaggerConformanceCorpusTestComponents_ProtectedPatternsComponent.factory()
                        .create(vertx, config()));
        client = DocumentRequests.separateConnectionsClient(vertx);
    }

    @AfterAll
    static void closeTheClientAndUndeploy() throws Exception {
        try {
            if (client != null) {
                client.close();
            }
        } finally {
            Deployments.undeployAll(vertx, DEPLOYMENT_IDS);
            DEPLOYMENT_IDS.clear();
            PORTS.clear();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName(
            "Every corpus document, public or protected, declares OpenAPI 3.1.1, validates as OpenAPI 3.1 with every Schema Object a valid 2020-12 schema, has no nullable member, parses from YAML to its JSON tree, ends every case-fold key portably, and never publishes the Java end anchor")
    void everyCorpusDocumentValidatesAsOpenApi31(Row row) throws Exception {
        // Given: the row's application running with its document in the row's mode; a protected
        // document refuses an anonymous caller, so the protected row really reads the guarded route.
        if (row.mode() == Mode.PROTECTED) {
            Answer anonymous = DocumentRequests.get(client, port(row), documentPath(row, "openapi.json"), null);
            assertEquals(401, anonymous.status(), () -> row + ": an anonymous read of a protected document");
        }

        // When: both forms of the document are fetched (a protected one with a bearer token).
        JsonObject json = fetchJson(row);
        byte[] yamlBytes = fetch(row, "openapi.yaml");

        // Then: the JSON form declares OpenAPI 3.1.1 and the toolchain accepts it.
        assertEquals(OPENAPI_VERSION, json.getValue("openapi"), () -> row + ": the openapi member");
        OpenApi31Toolchain.Verdict verdict = OpenApi31Toolchain.validate(vertx, json);
        assertTrue(verdict.valid(), () -> row + ": OpenAPI 3.1 toolchain problems: " + verdict.problems());

        // And: no Schema Object carries the OpenAPI 3.0 nullable member (the 2020-12 meta-schema allows
        // unknown keywords, so the toolchain alone would accept one).
        List<String> nullableMembers = collectNullableMembers(json);
        assertEquals(List.of(), nullableMembers, () -> row + ": Schema Objects with a nullable member");

        // And: the YAML form parses to the JSON form's tree.
        String difference = firstDifference(json, yamlAsJson(yamlBytes), "");
        assertNull(difference, () -> row + ": the YAML form differs from the JSON form at " + difference);

        // And: every framework-generated case-fold key ends with the portable anchor, and no published
        // pattern or key carries the Java end anchor.
        List<PatternPair> patterns = collectPatterns(json);
        List<String> unanchoredFoldKeys = patterns.stream()
                .filter(pair -> pair.key() && pair.text().startsWith(FOLD_KEY_START))
                .filter(pair -> !pair.text().endsWith(PORTABLE_END_ANCHOR))
                .map(PatternPair::pointer)
                .toList();
        assertEquals(List.of(), unanchoredFoldKeys, () -> row + ": case-fold keys without the portable end anchor");
        List<String> javaAnchored = patterns.stream()
                .filter(pair -> pair.text().contains(JAVA_END_ANCHOR))
                .map(PatternPair::pointer)
                .toList();
        assertEquals(List.of(), javaAnchored, () -> row + ": patterns carrying the Java end anchor");

        // And: in the patterns document, the case-fold key is there (so the anchor check is not
        // vacuous), and the authored flagged pattern is published as the gate's text.
        if (row.controls() == Controls.PATTERNS) {
            assertTrue(
                    patterns.stream().anyMatch(pair -> pair.key() && pair.text().equals(TITLE_FOLD_KEY)),
                    () -> row + ": the case-fold key of title must be published");
            List<PatternPair> flagged = patterns.stream()
                    .filter(pair -> !pair.key() && pair.pointer().endsWith(FLAGGED_POINTER_SUFFIX))
                    .toList();
            assertFalse(flagged.isEmpty(), () -> row + ": the flagged member pattern must be published");
            List<String> altered = flagged.stream()
                    .filter(pair -> !FLAGGED_PATTERN.equals(pair.text()))
                    .map(PatternPair::pointer)
                    .toList();
            assertEquals(List.of(), altered, () -> row + ": flagged member patterns differing from the gate's text");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName(
            "Every pattern value and patternProperties key in a schema position of every corpus document, public or protected, compiles and evaluates in vertx-json-schema under Draft 2020-12, the document validates in the OpenAPI 3.1 toolchain, and the positive controls are collected")
    void everyPublishedPatternCompilesInTheToolchain(Row row) throws Exception {
        // Given: the row's application running with its document in the row's mode.
        // When: the JSON form is fetched and every pattern in a schema position is collected.
        JsonObject json = fetchJson(row);
        List<PatternPair> patterns = collectPatterns(json);
        LOG.info("{}: {} published patterns collected", row, patterns.size());

        // Then: the whole document loads in the toolchain and every Schema Object is a valid 2020-12
        // schema.
        OpenApi31Toolchain.Verdict verdict = OpenApi31Toolchain.validate(vertx, json);
        assertTrue(verdict.valid(), () -> row + ": OpenAPI 3.1 toolchain problems: " + verdict.problems());

        // And: every collected pattern compiles and evaluates a sample without an exception.
        List<String> failures = new ArrayList<>();
        for (PatternPair pair : patterns) {
            String failure = compileFailure(pair);
            if (failure != null) {
                failures.add(failure);
            }
        }
        assertEquals(
                List.of(),
                failures,
                () -> row + ": patterns that do not compile or evaluate, of " + patterns.size() + " collected");

        // And: the collection holds the row's positive controls, so the check is not vacuous.
        switch (row.controls()) {
            case PATTERNS -> {
                assertTrue(
                        patterns.stream()
                                .anyMatch(pair -> pair.key()
                                        && pair.text().equals(TITLE_FOLD_KEY)
                                        && pair.text().endsWith(PORTABLE_END_ANCHOR)),
                        () -> row + ": the generator-built case-fold key of title must be collected, of "
                                + patterns.size());
                assertTrue(
                        patterns.stream()
                                .anyMatch(pair -> !pair.key()
                                        && pair.pointer().endsWith(FLAGGED_POINTER_SUFFIX)
                                        && pair.text().equals(FLAGGED_PATTERN)),
                        () -> row + ": the flagged member pattern must be collected, of " + patterns.size());
                assertTrue(
                        patterns.stream()
                                .anyMatch(pair -> !pair.key()
                                        && pair.pointer().contains("/parameters/")
                                        && pair.pointer().endsWith("/schema/pattern")
                                        && pair.text().equals(PARAMETER_PATTERN)),
                        () -> row + ": the authored path parameter pattern must be collected from a Parameter"
                                + " Object's schema, of " + patterns.size());
            }
            case CORPUS ->
                assertTrue(
                        patterns.stream()
                                .anyMatch(pair -> !pair.key() && pair.text().equals(CORPUS_MEMBER_PATTERN)),
                        () -> row + ": the nested member's authored pattern must be collected, of " + patterns.size());
            case NONE -> {
                // Recorded only: this document is checked for whatever patterns it publishes.
            }
        }
    }

    // --- Collecting schema positions ---

    /**
     * Returns every {@code pattern} value and every {@code patternProperties} key in a schema position
     * of every published schema: the members of {@code components.schemas} and every {@code schema}
     * member elsewhere (parameter, header, and media-type schemas), walked through every subschema
     * position. The walk never enters {@code const}, {@code enum}, {@code default}, {@code examples},
     * {@code example}, or an extension member, which hold data, not schemas.
     *
     * @param document the published document
     * @return the pairs, in document order
     */
    static List<PatternPair> collectPatterns(JsonObject document) {
        List<PatternPair> pairs = new ArrayList<>();
        schemaRoots(document)
                .forEach((pointer, root) -> walkSchemaObjects(root, pointer, (at, schema) -> {
                    if (schema.getValue("pattern") instanceof String text) {
                        pairs.add(new PatternPair(at + "/pattern", text, false));
                    }
                    if (schema.getValue("patternProperties") instanceof JsonObject byPattern) {
                        for (String key : byPattern.fieldNames()) {
                            pairs.add(new PatternPair(at + "/patternProperties/" + escape(key), key, true));
                        }
                    }
                }));
        return pairs;
    }

    /**
     * Returns the JSON Pointer of every {@code nullable} member of a Schema Object, at any depth.
     *
     * @param document the published document
     * @return the pointers, in document order
     */
    static List<String> collectNullableMembers(JsonObject document) {
        List<String> pointers = new ArrayList<>();
        schemaRoots(document)
                .forEach((pointer, root) -> walkSchemaObjects(root, pointer, (at, schema) -> {
                    if (schema.containsKey("nullable")) {
                        pointers.add(at + "/nullable");
                    }
                }));
        return pointers;
    }

    /**
     * Returns every published schema root by its JSON Pointer: each member of {@code
     * components.schemas}, and every {@code schema} member found elsewhere, outside extension members
     * and examples. A root's own subtree is not searched for further roots.
     */
    private static Map<String, Object> schemaRoots(JsonObject document) {
        Map<String, Object> roots = new LinkedHashMap<>();
        findSchemaRoots(document, "", roots);
        return roots;
    }

    private static void findSchemaRoots(Object node, String pointer, Map<String, Object> roots) {
        if (node instanceof JsonObject object) {
            for (String field : object.fieldNames()) {
                if (field.startsWith("x-") || field.equals("example") || field.equals("examples")) {
                    continue;
                }
                Object child = object.getValue(field);
                String childPointer = pointer + "/" + escape(field);
                if (pointer.equals("/components") && field.equals("schemas") && child instanceof JsonObject all) {
                    for (String name : all.fieldNames()) {
                        roots.put(childPointer + "/" + escape(name), all.getValue(name));
                    }
                } else if (field.equals("schema") && (child instanceof JsonObject || child instanceof Boolean)) {
                    roots.put(childPointer, child);
                } else {
                    findSchemaRoots(child, childPointer, roots);
                }
            }
        } else if (node instanceof JsonArray array) {
            for (int index = 0; index < array.size(); index++) {
                findSchemaRoots(array.getValue(index), pointer + "/" + index, roots);
            }
        }
    }

    /**
     * Visits a schema and every subschema in a schema position below it: {@code properties}, {@code
     * patternProperties}, {@code $defs}, and {@code dependentSchemas} members; {@code
     * additionalProperties}, {@code items}, {@code not}, {@code propertyNames}, {@code if}, {@code
     * then}, {@code else}, {@code contains}, {@code unevaluatedItems}, {@code unevaluatedProperties},
     * and {@code contentSchema}; and the elements of {@code prefixItems}, {@code allOf}, {@code
     * anyOf}, and {@code oneOf}. Boolean schemas hold nothing to visit.
     *
     * @param node    the schema
     * @param pointer its JSON Pointer
     * @param visitor receives each Schema Object with its pointer
     */
    private static void walkSchemaObjects(Object node, String pointer, BiConsumer<String, JsonObject> visitor) {
        if (!(node instanceof JsonObject schema)) {
            return;
        }
        visitor.accept(pointer, schema);
        for (String field : schema.fieldNames()) {
            Object member = schema.getValue(field);
            String memberPointer = pointer + "/" + escape(field);
            if (SINGLE_SCHEMA_KEYWORDS.contains(field)) {
                walkSchemaObjects(member, memberPointer, visitor);
            } else if (SCHEMA_ARRAY_KEYWORDS.contains(field) && member instanceof JsonArray elements) {
                for (int index = 0; index < elements.size(); index++) {
                    walkSchemaObjects(elements.getValue(index), memberPointer + "/" + index, visitor);
                }
            } else if (SCHEMA_MAP_KEYWORDS.contains(field) && member instanceof JsonObject byName) {
                for (String name : byName.fieldNames()) {
                    walkSchemaObjects(byName.getValue(name), memberPointer + "/" + escape(name), visitor);
                }
            }
        }
    }

    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    // --- Compiling one pattern ---

    /**
     * Compiles one pattern with vertx-json-schema under Draft 2020-12, on a fresh schema: a {@code
     * pattern} value as {@code {"type":"string","pattern":p}}, evaluated against a string; a {@code
     * patternProperties} key as {@code {"type":"object","patternProperties":{p:true}}}, evaluated
     * against an object with one member. Whether the sample matches does not matter; an exception
     * does.
     *
     * @param pair the pattern
     * @return {@code null} when the validator is built and evaluates the sample; otherwise the
     *     pattern's pointer and the exception's type, never the pattern's text
     */
    @Nullable
    static String compileFailure(PatternPair pair) {
        JsonObject schema = pair.key()
                ? new JsonObject()
                        .put("type", "object")
                        .put("patternProperties", new JsonObject().put(pair.text(), true))
                : new JsonObject().put("type", "string").put("pattern", pair.text());
        Object sample = pair.key() ? new JsonObject().put("sample", 1) : "sample";
        try {
            Validator.create(JsonSchema.of(schema), COMPILE_OPTIONS).validate(sample);
            return null;
        } catch (RuntimeException | StackOverflowError failure) {
            return pair.pointer() + ": " + failure.getClass().getName();
        }
    }

    // --- Documents, forms, and trees ---

    private static JsonObject config() {
        JsonObject config = DocsConfigs.loopback();
        config.getJsonObject("jaxrs").put("validationStrategy", "web-validation");
        config.put("jwt", new JsonObject().put("schemeName", CorpusDocuments.BEARER_AUTH));
        return config;
    }

    private static void deploy(Composition composition, Mode mode, Served component) throws Exception {
        int port =
                Deployments.deployAndReadPort(vertx, component::httpVerticle, new DeploymentOptions(), DEPLOYMENT_IDS);
        PORTS.computeIfAbsent(composition, unused -> new EnumMap<>(Mode.class)).put(mode, port);
    }

    private static int port(Row row) {
        return PORTS.get(row.composition()).get(row.mode());
    }

    private static String documentPath(Row row, String form) {
        return DocsConfigs.DEFAULT_APIDOCS_PATH + "/" + row.document() + "/" + form;
    }

    private static byte[] fetch(Row row, String form) throws Exception {
        String token = row.mode() == Mode.PROTECTED ? bearerToken : null;
        Answer answer = DocumentRequests.get(client, port(row), documentPath(row, form), token);
        assertEquals(200, answer.status(), () -> row + ": GET " + form);
        return answer.body();
    }

    private static JsonObject fetchJson(Row row) throws Exception {
        return new JsonObject(Buffer.buffer(fetch(row, "openapi.json")));
    }

    /** Parses a YAML form into a JSON object, through the JSON text of its tree. */
    private static JsonObject yamlAsJson(byte[] yamlBytes) throws Exception {
        JsonNode tree = new ObjectMapper(new YAMLFactory()).readTree(yamlBytes);
        return new JsonObject(new ObjectMapper().writeValueAsString(tree));
    }

    /**
     * Returns the JSON Pointer of the first position where two trees differ, or {@code null} when they
     * are equal. Objects must have the same member names, arrays the same length; numbers are
     * compared by value.
     */
    @Nullable
    private static String firstDifference(@Nullable Object expected, @Nullable Object actual, String pointer) {
        if (expected instanceof JsonObject left && actual instanceof JsonObject right) {
            if (!left.fieldNames().equals(right.fieldNames())) {
                return pointer.isEmpty() ? "/" : pointer;
            }
            for (String field : left.fieldNames()) {
                String found =
                        firstDifference(left.getValue(field), right.getValue(field), pointer + "/" + escape(field));
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (expected instanceof JsonArray left && actual instanceof JsonArray right) {
            if (left.size() != right.size()) {
                return pointer;
            }
            for (int index = 0; index < left.size(); index++) {
                String found = firstDifference(left.getValue(index), right.getValue(index), pointer + "/" + index);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (expected instanceof Number left && actual instanceof Number right) {
            Function<Number, BigDecimal> decimal = number -> new BigDecimal(number.toString());
            return decimal.apply(left).compareTo(decimal.apply(right)) == 0 ? null : pointer;
        }
        return Objects.equals(expected, actual) ? null : pointer;
    }
}
