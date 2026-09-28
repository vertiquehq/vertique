// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.convert.ConversionContexts;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.FilePartDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import dev.vertique.rest.jaxrs.validation.FileVerificationResult;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.jaxrs.validation.SchemaErrorKeywords;
import dev.vertique.rest.validation.corpus.CorpusFixture;
import dev.vertique.rest.validation.corpus.SchemaCorpus;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonFormatValidator;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.OutputUnit;
import io.vertx.json.schema.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.invocation.Invocation;

/**
 * Proofs for the {@code web-validation} gate's pattern-input guard (rest-022 T004, FR-020): the bound
 * the gate places ahead of every {@code pattern}, {@code patternProperties}, pattern-bearing
 * {@code propertyNames} position and every bounded format, and the vertx-json-schema 5.1.6 behaviors
 * that bound relies on.
 *
 * <p>Each proof is one top-level method named after its contract entry, its cases being the rows of
 * a {@code @ParameterizedTest} labelled with the contract's case letters.
 *
 * <p>TP-002 to TP-007 and TP-014 drive the guard's rewrite, compilation, and format validator directly,
 * and the gate through {@link WebValidationStrategy#gateFor} with mocked routing contexts, as {@code
 * WebValidationGateTest} does. A row whose input reaches {@link #CATASTROPHIC} or {@link #SLOW_IDN}
 * completes only when the gate answers without evaluating it, inside a preemptive timeout; timing is
 * never asserted. Every rejection is checked field by field and for the absence of the value, the key,
 * and the pattern, never by status alone.
 *
 * <p>{@link #engineBehaviorsTheGuardReliesOnHold} (TP-015) characterizes the engine itself. Its engine
 * rows (i) to (viii) compile the schemas with the gate's own options through {@link
 * Validator#create(JsonSchema, JsonSchemaOptions, JsonFormatValidator)} and a {@link
 * RecordingFormatValidator}, and use no code of the guard, so they hold at the baseline: rows (i) to (v)
 * pin the five "upgrade invariants" the guard's ordering and escape rely on, and rows (vi) to (viii) two
 * further engine facts its rewrite relies on, so an engine upgrade that breaks one fails here rather than
 * silently letting a pattern or a bounded format run on unbounded input.
 */
class PatternInputGuardTest {

    /**
     * {@code CATASTROPHIC}: exponential backtracking on {@code "a".repeat(n - 1) + "!"}, about 3 s at 29
     * characters and effectively forever at the lengths used here, so a row using it completes only
     * when the engine never evaluates it.
     */
    static final String CATASTROPHIC = "^(a{1,30}){1,30}$";

    /** The 4,097-character input {@link #CATASTROPHIC} never finishes judging. */
    static final String OVER_LONG_CATASTROPHIC_INPUT = "a".repeat(4096) + "!";

    /**
     * The gate's compile options, copied literally from {@code WebValidationStrategy.SCHEMA_OPTIONS}
     * (coordinator ruling D8), so the engine rows characterize the engine exactly as the gate
     * configures it.
     */
    private static final JsonSchemaOptions GATE_OPTIONS = new JsonSchemaOptions()
            .setDraft(Draft.DRAFT202012)
            .setBaseUri("https://vertique.local/")
            .setOutputFormat(OutputFormat.Basic);

    /** Bounds every row that would otherwise run a catastrophic evaluation. */
    private static final Duration ROW_TIMEOUT = Duration.ofSeconds(30);

    /** The recorder's sentinel format: a call for it throws the recorder's sentinel exception. */
    private static final String PROBE = "probe";

    /** A format name the engine does not know, shaped as T021's renamed formats. */
    private static final String RENAMED_URI = "x-vertique-format-uri";

    /**
     * A 5,000-character URI, a length at which the engine's own {@code uri} expression overflows a
     * 1 MB stack (security design review S-001).
     */
    private static final String LONG_URI = "https://example.com/" + "a".repeat(4980);

    /** The stack size of the thread rows (iv) and (v) validate on. */
    private static final long ONE_MEGABYTE_STACK = 1 << 20;

    // --- The bound's fixtures (TP-002 to TP-007, TP-014) ---

    /** The bound's default per-string limit, in UTF-16 code units. */
    private static final int MAX_CHARS = 4096;

    /** The bound's default per-request limit, in UTF-16 code units. */
    private static final int MAX_TOTAL_CHARS = 262_144;

    /** The bound entry's format name, spelled as the contract fixes it. */
    private static final String BOUND = "x-vertique-pattern-input-bound";

    /** The bound entry, as JSON text. */
    private static final String BOUND_ENTRY_JSON = "{\"format\":\"" + BOUND + "\"}";

    /** The entry a non-empty {@code patternProperties} gains, as JSON text. */
    private static final String PROPERTY_NAMES_ENTRY_JSON = "{\"propertyNames\":" + BOUND_ENTRY_JSON + "}";

    /** The detail {@code type} of a per-string rejection. */
    private static final String PER_STRING_TYPE = "patternInputLength";

    /** The detail {@code type} of a per-request rejection. */
    private static final String PER_REQUEST_TYPE = "patternInputTotalLength";

    /** Sixteen characters of an {@code a} run: a value fragment no detail may contain. */
    private static final String A_RUN = "a".repeat(16);

    /** Sixteen characters of a {@code b} run: a value fragment no detail may contain. */
    private static final String B_RUN = "b".repeat(16);

    /** Text only {@link #CATASTROPHIC} carries: no detail may contain the pattern. */
    private static final String CATASTROPHIC_TEXT = "{1,30}";

    /** A linear pattern every all-{@code a} string matches. */
    private static final String ALL_A = "^a*$";

    /**
     * {@code SLOW_IDN}: 131,072 {@code a} characters, which the baseline engine's own {@code idn-hostname}
     * check takes about 32 seconds to judge (invalid) on a default-stack thread (step 3's probe, recorded
     * in the task evidence): far beyond TP-014 (h)'s timeout.
     */
    private static final String SLOW_IDN = "a".repeat(131_072);

    /** TP-014 (h)'s timeout: only the engine's own evaluation of {@link #SLOW_IDN} can exhaust it. */
    private static final Duration SLOW_IDN_TIMEOUT = Duration.ofSeconds(10);

    /** The operation id every gate row builds its descriptor with. */
    private static final String OPERATION_ID = "patternInputBound";

    // --- TP-002: the rewrite ---

    /**
     * TP-002. The guard's rewrite returns a copy in which every node with a string {@code pattern} and every
     * node whose {@code format} is {@code idn-hostname}, {@code idn-email}, or {@code regex} gains one bound
     * entry per position, appended to its {@code allOf} (created when absent), and every node with a
     * non-empty {@code patternProperties} gains the {@code propertyNames} entry after them. A node whose
     * {@code allOf} is present but not an array is left unchanged, literal values and name containers are
     * never schema nodes, and the input is left untouched.
     *
     * @param shape the row's shape
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rewriteShapes")
    @DisplayName("TP-002: the rewrite appends the bound at every pattern and bounded-format position")
    void rewriteAppendsTheBoundAtEveryBoundPosition(Shape shape) {
        JsonObject input = shape.input().copy();
        JsonObject untouched = input.copy();

        JsonObject rewritten = PatternInputGuard.rewrite(input);

        assertAll(
                () -> assertEquals(
                        shape.expected(), rewritten, "the rewritten document must gain exactly the bound entries"),
                () -> assertEquals(
                        shape.positions(),
                        boundEntries(rewritten),
                        "one entry must be appended per pattern and bounded-format position"),
                () -> assertEquals(untouched, input, "the rewrite must not modify its input"),
                () -> assertNotSame(input, rewritten, "the rewrite must return a copy, not its input"));
    }

    /**
     * TP-002's shape matrix, which TP-004 reuses with the in-bound instances each row carries. In the
     * JSON literals {@code '} stands for {@code "}, {@code <B>} for the bound entry {@code {"format":
     * "x-vertique-pattern-input-bound"}}, and {@code <P>} for {@code {"propertyNames": <B>}}.
     *
     * @return the rows
     */
    static Stream<Named<Shape>> rewriteShapes() {
        return Stream.of(
                shape(
                        "pattern at the root with no allOf: an allOf is created holding the one entry",
                        "{'type':'string','pattern':'^a+$'}",
                        "{'type':'string','pattern':'^a+$','allOf':[<B>]}",
                        1,
                        ok("'aa'"),
                        bad("'ab'")),
                shape(
                        "pattern beside an existing allOf of two branches: the entry becomes index 2",
                        "{'type':'string','pattern':'^a+$','allOf':[{'minLength':2},{'maxLength':8}]}",
                        "{'type':'string','pattern':'^a+$','allOf':[{'minLength':2},{'maxLength':8},<B>]}",
                        1,
                        ok("'aaa'"),
                        bad("'abcdefghij'"),
                        bad("'a'")),
                shape(
                        "pattern under properties",
                        "{'type':'object','properties':{'name':{'type':'string','pattern':'^a+$'}}}",
                        "{'type':'object','properties':{'name':{'type':'string','pattern':'^a+$','allOf':[<B>]}}}",
                        1,
                        ok("{'name':'aa'}"),
                        bad("{'name':'ab'}")),
                shape(
                        "pattern under items",
                        "{'type':'array','items':{'type':'string','pattern':'^a+$'}}",
                        "{'type':'array','items':{'type':'string','pattern':'^a+$','allOf':[<B>]}}",
                        1,
                        ok("['a','aa']"),
                        bad("['a','b']")),
                shape(
                        "pattern under prefixItems",
                        "{'type':'array','prefixItems':[{'type':'string','pattern':'^a+$'},{'type':'integer'}]}",
                        "{'type':'array','prefixItems':[{'type':'string','pattern':'^a+$','allOf':[<B>]},"
                                + "{'type':'integer'}]}",
                        1,
                        ok("['aa',1]"),
                        bad("['b','x']")),
                shape(
                        "pattern under $defs, reached through $ref",
                        "{'$ref':'#/$defs/code','$defs':{'code':{'type':'string','pattern':'^a+$'}}}",
                        "{'$ref':'#/$defs/code','$defs':{'code':{'type':'string','pattern':'^a+$','allOf':[<B>]}}}",
                        1,
                        ok("'aa'"),
                        bad("'b'")),
                shape(
                        "pattern under dependentSchemas",
                        "{'type':'object','dependentSchemas':{'a':{'properties':{'b':{'type':'string',"
                                + "'pattern':'^a+$'}}}}}",
                        "{'type':'object','dependentSchemas':{'a':{'properties':{'b':{'type':'string',"
                                + "'pattern':'^a+$','allOf':[<B>]}}}}}",
                        1,
                        ok("{'a':1,'b':'aa'}"),
                        bad("{'a':1,'b':'bb'}")),
                shape(
                        "pattern under not",
                        "{'type':'string','not':{'pattern':'^a+$'}}",
                        "{'type':'string','not':{'pattern':'^a+$','allOf':[<B>]}}",
                        1,
                        ok("'b'"),
                        bad("'aa'")),
                shape(
                        "pattern under anyOf",
                        "{'anyOf':[{'type':'string','pattern':'^a+$'},{'type':'integer'}]}",
                        "{'anyOf':[{'type':'string','pattern':'^a+$','allOf':[<B>]},{'type':'integer'}]}",
                        1,
                        ok("'aa'"),
                        ok("5"),
                        bad("'b'")),
                shape(
                        "pattern under oneOf",
                        "{'oneOf':[{'type':'string','pattern':'^a+$'},{'type':'string','pattern':'^b+$'}]}",
                        "{'oneOf':[{'type':'string','pattern':'^a+$','allOf':[<B>]},"
                                + "{'type':'string','pattern':'^b+$','allOf':[<B>]}]}",
                        2,
                        ok("'aa'"),
                        bad("'c'")),
                shape(
                        "pattern under if, then, and else",
                        "{'type':'string','if':{'pattern':'^a'},'then':{'pattern':'^ab'},'else':{'pattern':'^b'}}",
                        "{'type':'string','if':{'pattern':'^a','allOf':[<B>]},'then':{'pattern':'^ab','allOf':[<B>]},"
                                + "'else':{'pattern':'^b','allOf':[<B>]}}",
                        3,
                        ok("'ab'"),
                        ok("'bb'"),
                        bad("'aa'"),
                        bad("'cc'")),
                shape(
                        "propertyNames carrying a pattern",
                        "{'type':'object','propertyNames':{'pattern':'^[a-z]+$'}}",
                        "{'type':'object','propertyNames':{'pattern':'^[a-z]+$','allOf':[<B>]}}",
                        1,
                        ok("{'abc':1}"),
                        bad("{'ABC':1}")),
                shape(
                        "a non-empty patternProperties: the propertyNames entry",
                        "{'type':'object','patternProperties':{'^x-':{'type':'string'}}}",
                        "{'type':'object','patternProperties':{'^x-':{'type':'string'}},'allOf':[<P>]}",
                        1,
                        ok("{'x-a':'v'}"),
                        bad("{'x-a':1}")),
                unchanged(
                        "an empty patternProperties: unchanged",
                        "{'type':'object','patternProperties':{}}",
                        ok("{'a':1}"),
                        bad("'s'")),
                shape(
                        "pattern and a non-empty patternProperties: both entries, the format entry first",
                        "{'type':['string','object'],'pattern':'^a+$','patternProperties':{'^x-':{'type':'integer'}}}",
                        "{'type':['string','object'],'pattern':'^a+$','patternProperties':{'^x-':{'type':'integer'}},"
                                + "'allOf':[<B>,<P>]}",
                        2,
                        ok("'aa'"),
                        ok("{'x-1':1}"),
                        bad("'b'"),
                        bad("{'x-1':'s'}")),
                unchanged(
                        "a pattern member inside a const value: unchanged",
                        "{'type':'object','const':{'pattern':'^a+$'}}",
                        ok("{'pattern':'^a+$'}"),
                        bad("{'pattern':'x'}")),
                unchanged(
                        "a pattern member inside an enum value: unchanged",
                        "{'enum':[{'pattern':'^a+$'},'z']}",
                        ok("'z'"),
                        bad("'y'")),
                unchanged(
                        "a pattern member inside a default value: unchanged",
                        "{'type':'string','default':{'pattern':'^a+$'}}",
                        ok("'s'"),
                        bad("5")),
                unchanged(
                        "a pattern member inside an examples value: unchanged",
                        "{'type':'string','examples':[{'pattern':'^a+$'}]}",
                        ok("'s'"),
                        bad("5")),
                unchanged(
                        "a pattern member inside an example value: unchanged",
                        "{'type':'string','example':{'pattern':'^a+$'}}",
                        ok("'s'"),
                        bad("5")),
                shape(
                        "properties literally named pattern and patternProperties: the container is not a schema"
                                + " node, and each subschema is walked",
                        "{'type':'object','properties':{'pattern':{'type':'string','pattern':'^a+$'},"
                                + "'patternProperties':{'type':'object','patternProperties':{'^x-':{'type':'integer'}}}}}",
                        "{'type':'object','properties':{'pattern':{'type':'string','pattern':'^a+$','allOf':[<B>]},"
                                + "'patternProperties':{'type':'object','patternProperties':{'^x-':{'type':'integer'}},"
                                + "'allOf':[<P>]}}}",
                        2,
                        ok("{'pattern':'aa','patternProperties':{'x-1':1}}"),
                        bad("{'pattern':'b','patternProperties':{'x-1':'s'}}")),
                unchanged(
                        "a pattern keyword whose value is not a string: unchanged",
                        "{'minimum':1,'pattern':5}",
                        ok("2"),
                        bad("0")),
                shape(
                        "format idn-hostname: one format entry",
                        "{'type':'string','format':'idn-hostname'}",
                        "{'type':'string','format':'idn-hostname','allOf':[<B>]}",
                        1,
                        ok("'bücher.example'"),
                        bad("'exa mple'")),
                shape(
                        "format idn-email: one format entry",
                        "{'type':'string','format':'idn-email'}",
                        "{'type':'string','format':'idn-email','allOf':[<B>]}",
                        1,
                        ok("'user@bücher.example'"),
                        bad("'not an email'")),
                shape(
                        "format regex beside an existing allOf: one format entry, appended",
                        "{'type':'string','format':'regex','allOf':[{'minLength':1}]}",
                        "{'type':'string','format':'regex','allOf':[{'minLength':1},<B>]}",
                        1,
                        ok("'^a+$'"),
                        bad("'('"),
                        bad("''")),
                shape(
                        "a string pattern and format regex: two format entries",
                        "{'type':'string','pattern':'[$]$','format':'regex'}",
                        "{'type':'string','pattern':'[$]$','format':'regex','allOf':[<B>,<B>]}",
                        2,
                        ok("'^a+$'"),
                        bad("'a('")),
                shape(
                        "pattern, format idn-email, and a non-empty patternProperties: two format entries, then the"
                                + " propertyNames entry",
                        "{'type':['string','object'],'pattern':'@','format':'idn-email',"
                                + "'patternProperties':{'^x-':{'type':'integer'}}}",
                        "{'type':['string','object'],'pattern':'@','format':'idn-email',"
                                + "'patternProperties':{'^x-':{'type':'integer'}},'allOf':[<B>,<B>,<P>]}",
                        3,
                        ok("'user@example.com'"),
                        ok("{'x-1':1}"),
                        bad("'nope'"),
                        bad("{'x-1':'s'}")),
                unchanged(
                        "format date-time: unchanged",
                        "{'type':'string','format':'date-time'}",
                        ok("'2026-09-27T12:00:00Z'"),
                        bad("'yesterday'")),
                shape(
                        "format uri: renamed, not bounded",
                        "{'type':'string','format':'uri'}",
                        "{'type':'string','format':'" + RENAMED_URI + "'}",
                        0,
                        ok("'https://example.com/a'"),
                        bad("'not a uri'")),
                unchanged(
                        "format iri: unchanged",
                        "{'type':'string','format':'iri'}",
                        ok("'https://example.com/ä'"),
                        bad("5")),
                unchanged(
                        "an application format: unchanged",
                        "{'type':'string','format':'x-app-format'}",
                        ok("'anything'"),
                        bad("5")),
                unchanged(
                        "a format member inside a const value: unchanged",
                        "{'type':'object','const':{'format':'idn-hostname'}}",
                        ok("{'format':'idn-hostname'}"),
                        bad("{'format':'x'}")),
                unchanged(
                        "a format member inside an examples value: unchanged",
                        "{'type':'string','examples':[{'format':'regex'}]}",
                        ok("'s'"),
                        bad("5")),
                unchanged(
                        "a property literally named format: unchanged",
                        "{'type':'object','properties':{'format':{'type':'string'}}}",
                        ok("{'format':'x'}"),
                        bad("{'format':5}")),
                unchanged(
                        "a format keyword whose value is not a string: unchanged",
                        "{'type':'string','format':['idn-hostname']}",
                        unjudgeable("'abc'")),
                unchanged(
                        "a pattern beside an allOf that is not an array: unchanged, the engine failing at the allOf"
                                + " step",
                        "{'pattern':'^a+$','allOf':{}}",
                        unjudgeable("'aa'")));
    }

    // --- TP-003: the shared schemas ---

    /**
     * TP-003. Building the gate compiles and rewrites private copies only: every schema object {@link
     * OperationSchemas} holds equals its pre-call copy and carries neither {@code __absolute_uri__} (the key
     * {@link JsonSchema#of(JsonObject)} writes into the object it compiles) nor a bound entry, and the
     * rewrite of a pattern-free schema equals its input.
     *
     * @param row the row's operation and schemas
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedSchemaOperations")
    @DisplayName("TP-003: shared schemas are never compiled or mutated; pattern-free ones compile unchanged")
    void sharedSchemasAreNeverCompiledOrMutated(SharedSchemas row) {
        Map<String, JsonObject> held = row.held();
        Map<String, JsonObject> snapshot = new LinkedHashMap<>();
        held.forEach((location, schema) -> snapshot.put(location, schema.copy()));

        new WebValidationStrategy(JaxRsConfig.builder().build()).gateFor(row.operation(), row.schemas());

        List<Executable> checks = new ArrayList<>();
        held.forEach((location, schema) -> {
            JsonObject before = snapshot.get(location);
            checks.add(() -> assertEquals(before, schema, location + " must equal its pre-call copy"));
            checks.add(() -> assertFalse(
                    schema.encode().contains("__absolute_uri__"),
                    location + " must carry no __absolute_uri__ key: the gate compiled the shared object"));
            checks.add(() -> assertFalse(
                    schema.encode().contains(BOUND),
                    location + " must carry no bound entry: the gate rewrote the shared object"));
            if (row.patternFree()) {
                checks.add(() -> assertEquals(
                        before,
                        PatternInputGuard.rewrite(before.copy()),
                        "the rewrite of the pattern-free " + location + " must equal its input"));
            }
        });
        assertAll(checks.stream());
    }

    /**
     * TP-003's operations: one whose body and path-parameter schemas carry {@code pattern}, one whose body
     * and query-parameter schemas carry none, and one per pattern-free document of the pinned
     * {@code schema-corpus} (as the body, beside the same pattern-free query parameter).
     *
     * @return the rows
     */
    static Stream<Named<SharedSchemas>> sharedSchemaOperations() {
        Stream<Named<SharedSchemas>> handBuilt = Stream.of(
                Named.of(
                        "pattern-bearing operation: body and path parameter carry pattern",
                        shared(
                                schema(
                                        "{'type':'object','properties':{'code':{'type':'string','pattern':'^[A-Z]+$'}}}"),
                                ParamLocation.PATH,
                                "id",
                                schema("{'type':'string','pattern':'^[0-9]+$'}"),
                                false)),
                Named.of(
                        "pattern-free operation: body and query parameter carry none",
                        shared(
                                schema("{'type':'object','required':['name'],'properties':{'name':{'type':'string',"
                                        + "'minLength':1}}}"),
                                ParamLocation.QUERY,
                                "limit",
                                schema("{'type':'integer','maximum':100}"),
                                true)));
        Stream<Named<SharedSchemas>> corpus = corpusDocuments()
                .filter(CorpusDocument::patternFree)
                .map(document -> Named.of(
                        "pattern-free corpus document " + document.label(),
                        shared(
                                document.schema(),
                                ParamLocation.QUERY,
                                "limit",
                                schema("{'type':'integer','maximum':100}"),
                                true)));
        return Stream.concat(handBuilt, corpus);
    }

    // --- TP-004: in-bound preservation ---

    /**
     * TP-004. For every corpus document and every shape of the TP-002 matrix (with the format rows TP-004
     * adds), each in-bound instance gets the same verdict and the same errors, element by element in
     * {@code keywordLocation}, {@code instanceLocation}, and {@code error}, from the gate's guarded
     * compilation (the guard's format validator, inside a counting window) as from {@code
     * Validator.create(JsonSchema.of(copy), options)} over an untouched copy, both with the gate's options.
     *
     * @param row the row's schema and instance
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("inBoundInstances")
    @DisplayName("TP-004: in-bound verdicts and violation lists are unchanged")
    void inBoundVerdictsAndViolationListsAreUnchanged(PreservationCase row) {
        Object instance = row.instance().value();
        assertInBound(instance);
        Judgement unguarded = judge(() -> unguarded(row.schema()).validate(instance));
        assertGivenVerdict(row.instance(), unguarded);

        PatternInputGuard guard = new PatternInputGuard(MAX_CHARS, MAX_TOTAL_CHARS);
        Validator guardedValidator = guard.compile(row.schema().copy(), GATE_OPTIONS);
        Judgement guarded;
        try (PatternInputGuard.Window window = guard.openWindow()) {
            guarded = judge(() -> guardedValidator.validate(instance));
        }

        assertSameJudgement(unguarded, guarded);
    }

    /**
     * TP-004's rows: every instance of every TP-002 shape and of the TP-004-only format shapes, then every
     * instance of every corpus document.
     *
     * @return the rows
     */
    static Stream<Named<PreservationCase>> inBoundInstances() {
        Stream<Named<PreservationCase>> matrix = Stream.concat(rewriteShapes(), preservationOnlyShapes())
                .flatMap(shape -> shape.getPayload().instances().stream()
                        .map(instance -> Named.of(
                                shape.getName() + " | " + instance,
                                new PreservationCase(shape.getPayload().input(), instance))));
        Stream<Named<PreservationCase>> corpus = corpusDocuments()
                .flatMap(document -> corpusInstances(document.fixtureName()).stream()
                        .map(instance -> Named.of(
                                "corpus " + document.label() + " | " + instance,
                                new PreservationCase(document.schema(), instance))));
        return Stream.concat(matrix, corpus);
    }

    /**
     * The shapes TP-004 adds to the TP-002 matrix: the measured-linear formats {@code email} and {@code
     * uuid}, C-FORMAT's formats the matrix does not already carry (values of at most 64 characters, still
     * the engine's own checks), a numeric instance at a {@code pattern} node and at a {@code format}
     * node, and a non-empty {@code patternProperties} beside {@code "unevaluatedProperties": false}, whose
     * verdicts hold only while the appended {@code propertyNames} entry marks no key evaluated.
     *
     * @return the shapes
     */
    static Stream<Named<Shape>> preservationOnlyShapes() {
        return Stream.of(
                formatShape("email", "'user@example.com'", "'nope'"),
                formatShape("uuid", "'00000000-0000-0000-0000-000000000001'", "'xyz'"),
                formatShape("uri-reference", "'/a/b'", "'a b'"),
                formatShape("url", "'https://example.com/b'", "'nope'"),
                formatShape("json-pointer", "'/a/b'", "'a/b'"),
                formatShape("relative-json-pointer", "'0/a'", "'/a'"),
                formatShape("json-pointer-uri-fragment", "'#/a/b'", "'/a'"),
                formatShape("uri-template", "'https://example.com/{id}'", "'https://example.com/{id'"),
                preserved("a numeric instance at a pattern node", "{'pattern':'^a+$'}", ok("5"), bad("'b'")),
                preserved(
                        "a numeric instance at a format node", "{'format':'idn-hostname'}", ok("5"), bad("'exa mple'")),
                preserved(
                        "a non-empty patternProperties beside unevaluatedProperties false: the propertyNames entry"
                                + " evaluates no key (engine fact c)",
                        "{'type':'object','patternProperties':{'^x-':{}},'unevaluatedProperties':false}",
                        ok("{'x-a':1}"),
                        bad("{'y':1}")));
    }

    // --- TP-005: the per-string bound at every location ---

    /**
     * TP-005. A 4,097-character string at a pattern position is rejected at every location with exactly one
     * value-free {@code patternInputLength} detail, and validation of the request stops there in both modes;
     * called directly, the guard's format validator throws both exceptions stack-trace-free and value-free.
     *
     * @param row the row's proof
     * @throws Throwable when the row's assertion fails
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("overLongStringRows")
    @DisplayName("TP-005: an over-long string is rejected at every location, value-free, and validation stops")
    void overLongStringIsRejectedAtEveryLocation(Executable row) throws Throwable {
        row.execute();
    }

    /**
     * TP-005's rows.
     *
     * @return the rows
     */
    static Stream<Named<Executable>> overLongStringRows() {
        return Stream.of(
                Named.of(
                        "(path) a 4,097-character path parameter",
                        overLongParameter(ParamLocation.PATH, "token", "path")),
                Named.of(
                        "(query) a 4,097-character query parameter",
                        overLongParameter(ParamLocation.QUERY, "token", "query")),
                Named.of(
                        "(header) a 4,097-character header",
                        overLongParameter(ParamLocation.HEADER, "X-Token", "header")),
                Named.of(
                        "(cookie) a 4,097-character cookie",
                        overLongParameter(ParamLocation.COOKIE, "session", "cookie")),
                Named.of("(form) a 4,097-character form field", overLongParameter(ParamLocation.FORM, "token", "form")),
                Named.of("(body) a 4,097-character body value", PatternInputGuardTest::overLongBodyValue),
                Named.of(
                        "(query collection) the second value of a List<String> query parameter",
                        PatternInputGuardTest::overLongCollectionValue),
                Named.of(
                        "(aggregate) the earlier detail stays ahead of the rejection, and nothing after it runs",
                        PatternInputGuardTest::aggregateModeStopsAtTheRejection),
                Named.of(
                        "(failFast) only the rejection is reported, and nothing after it runs",
                        PatternInputGuardTest::failFastModeStopsAtTheRejection),
                Named.of(
                        "(collection stop) a rejected collection parameter stops validation: no later parameter,"
                                + " body, or file part is reported",
                        PatternInputGuardTest::collectionRejectionStopsValidation),
                Named.of(
                        "(body stop) a rejected body stops validation: the violating file part is never reported",
                        PatternInputGuardTest::bodyRejectionStopsValidation),
                Named.of(
                        "(format validator) called directly, it throws both exceptions stack-trace-free and"
                                + " value-free",
                        PatternInputGuardTest::formatValidatorThrowsBothExceptions));
    }

    /**
     * A TP-005 row: one parameter at {@code location} whose schema carries {@link #CATASTROPHIC}, sent the
     * 4,097-character value.
     *
     * @param location the parameter location
     * @param name     the parameter name
     * @param token    the detail's expected location token
     * @return the row
     */
    private static Executable overLongParameter(ParamLocation location, String name, String token) {
        return () -> {
            Handler<RoutingContext> gate = gate(
                    defaults(),
                    operation(List.of(param(name, location, String.class, null)), false, List.of()),
                    OperationSchemas.builder()
                            .parameterSchema(location, name, catastrophicString())
                            .build(),
                    Set.of());
            RoutingContext ctx = new GateRequest()
                    .parameter(location, name, OVER_LONG_CATASTROPHIC_INPUT)
                    .context();

            List<ValidationErrorDetail> details = handle(gate, ctx, ROW_TIMEOUT).rejection();

            assertEquals(1, details.size(), () -> "exactly one detail: " + types(details));
            assertPerStringDetail(details.get(0), name, token, MAX_CHARS);
            assertValueFree(details, A_RUN, CATASTROPHIC_TEXT);
        };
    }

    /** TP-005 (body): a body property carrying {@link #CATASTROPHIC}, sent the 4,097-character value. */
    private static void overLongBodyValue() {
        JsonObject schema = new JsonObject()
                .put("type", "object")
                .put("properties", new JsonObject().put("text", catastrophicString()));
        RoutingContext ctx = new GateRequest()
                .body(new JsonObject().put("text", OVER_LONG_CATASTROPHIC_INPUT))
                .context();

        List<ValidationErrorDetail> details =
                handle(bodyGate(defaults(), schema), ctx, ROW_TIMEOUT).rejection();

        assertEquals(1, details.size(), () -> "exactly one detail: " + types(details));
        assertPerStringDetail(details.get(0), "", "body", MAX_CHARS);
        assertValueFree(details, A_RUN, CATASTROPHIC_TEXT);
    }

    /**
     * TP-005 (query collection): a {@code List<String>} query parameter whose item schema carries {@link
     * #CATASTROPHIC}, sent a short first value and the 4,097-character second value.
     */
    private static void overLongCollectionValue() {
        JsonObject schema = new JsonObject().put("type", "array").put("items", catastrophicString());
        Handler<RoutingContext> gate = gate(
                defaults(),
                operation(List.of(param("tags", ParamLocation.QUERY, List.class, String.class)), false, List.of()),
                OperationSchemas.builder()
                        .parameterSchema(ParamLocation.QUERY, "tags", schema)
                        .build(),
                Set.of());
        RoutingContext ctx = new GateRequest()
                .parameter(ParamLocation.QUERY, "tags", "aa")
                .parameter(ParamLocation.QUERY, "tags", OVER_LONG_CATASTROPHIC_INPUT)
                .context();

        List<ValidationErrorDetail> details = handle(gate, ctx, ROW_TIMEOUT).rejection();

        assertEquals(1, details.size(), () -> "exactly one detail: " + types(details));
        assertPerStringDetail(details.get(0), "tags", "query", MAX_CHARS);
        assertValueFree(details, A_RUN, CATASTROPHIC_TEXT);
    }

    /**
     * TP-005 (aggregate): parameters {@code z} (a {@code minLength} violation), {@code a} (the over-long
     * value), and {@code b} (a {@code minLength} violation), an invalid body, a violating named upload, and
     * a counting file-content verifier. Exactly {@code z}'s detail and then {@code a}'s: {@code b}, the
     * body, the file part, and the verifier are never reached.
     */
    private static void aggregateModeStopsAtTheRejection() {
        CountingVerifier verifier = new CountingVerifier();
        Handler<RoutingContext> gate = gate(defaults(), stopRuleOperation(), stopRuleSchemas(), Set.of(verifier));
        RoutingContext ctx = new GateRequest()
                .parameter(ParamLocation.QUERY, "z", "zz")
                .parameter(ParamLocation.QUERY, "a", OVER_LONG_CATASTROPHIC_INPUT)
                .parameter(ParamLocation.QUERY, "b", "bb")
                .body(new JsonObject())
                .upload(upload("avatar", "application/pdf"))
                .context();

        List<ValidationErrorDetail> details = handle(gate, ctx, ROW_TIMEOUT).rejection();

        assertEquals(2, details.size(), () -> "exactly z's detail and then a's: " + types(details));
        assertAll(
                () -> assertEquals("z", details.get(0).path(), "the earlier detail comes first"),
                () -> assertEquals("query", details.get(0).location()),
                () -> assertEquals("minLength", details.get(0).type()),
                () -> assertPerStringDetail(details.get(1), "a", "query", MAX_CHARS),
                () -> assertEquals(0, verifier.calls(), "no file-content verifier may run after the rejection"),
                () -> assertValueFree(details, A_RUN, CATASTROPHIC_TEXT));
    }

    /**
     * TP-005 (failFast): the same operation with {@code z} conforming, in {@code failFast} mode. Exactly
     * {@code a}'s detail; nothing after it runs.
     */
    private static void failFastModeStopsAtTheRejection() {
        CountingVerifier verifier = new CountingVerifier();
        Handler<RoutingContext> gate = gate(
                JaxRsConfig.builder().validationMode("failFast").build(),
                stopRuleOperation(),
                stopRuleSchemas(),
                Set.of(verifier));
        RoutingContext ctx = new GateRequest()
                .parameter(ParamLocation.QUERY, "z", "zzzzz")
                .parameter(ParamLocation.QUERY, "a", OVER_LONG_CATASTROPHIC_INPUT)
                .parameter(ParamLocation.QUERY, "b", "bb")
                .body(new JsonObject())
                .upload(upload("avatar", "application/pdf"))
                .context();

        List<ValidationErrorDetail> details = handle(gate, ctx, ROW_TIMEOUT).rejection();

        assertEquals(1, details.size(), () -> "exactly a's detail: " + types(details));
        assertAll(
                () -> assertPerStringDetail(details.get(0), "a", "query", MAX_CHARS),
                () -> assertEquals(0, verifier.calls(), "no file-content verifier may run after the rejection"),
                () -> assertValueFree(details, A_RUN, CATASTROPHIC_TEXT));
    }

    /**
     * TP-005 (collection stop): the {@code List<String>} query parameter {@code tags}, whose item schema
     * carries {@link #CATASTROPHIC}, sent a short first value and the 4,097-character second value, followed by
     * {@code b} (a {@code minLength} violation), an invalid body, a violating named upload, and a counting
     * file-content verifier. Exactly {@code tags}' detail: {@code b}, the body, the file part, and the verifier
     * are never reached. The Given: with only the short value, the gate reports {@code b}, the body, and the
     * upload, so each later input is a violation the gate reports once reached.
     */
    private static void collectionRejectionStopsValidation() {
        CountingVerifier verifier = new CountingVerifier();
        Handler<RoutingContext> gate = gate(
                defaults(),
                operation(
                        List.of(
                                param("tags", ParamLocation.QUERY, List.class, String.class),
                                param("b", ParamLocation.QUERY, String.class, null)),
                        true,
                        List.of(new FilePartDescriptor("avatar", List.of("image/png"), -1))),
                OperationSchemas.builder()
                        .parameterSchema(
                                ParamLocation.QUERY,
                                "tags",
                                new JsonObject().put("type", "array").put("items", catastrophicString()))
                        .parameterSchema(ParamLocation.QUERY, "b", schema("{'type':'string','minLength':5}"))
                        .bodySchema(schema("{'type':'object','required':['name']}"))
                        .build(),
                Set.of(verifier));
        RoutingContext inBound = new GateRequest()
                .parameter(ParamLocation.QUERY, "tags", "aa")
                .parameter(ParamLocation.QUERY, "b", "bb")
                .body(new JsonObject())
                .upload(upload("avatar", "application/pdf"))
                .context();
        RoutingContext ctx = new GateRequest()
                .parameter(ParamLocation.QUERY, "tags", "aa")
                .parameter(ParamLocation.QUERY, "tags", OVER_LONG_CATASTROPHIC_INPUT)
                .parameter(ParamLocation.QUERY, "b", "bb")
                .body(new JsonObject())
                .upload(upload("avatar", "application/pdf"))
                .context();

        List<ValidationErrorDetail> reached = handle(gate, inBound, ROW_TIMEOUT).rejection();
        assertEquals(
                List.of("query", "body", "file"),
                reached.stream().map(ValidationErrorDetail::location).toList(),
                () -> "the Given: b, the body, and the upload each violate once reached: " + types(reached));

        List<ValidationErrorDetail> details = handle(gate, ctx, ROW_TIMEOUT).rejection();

        assertEquals(1, details.size(), () -> "exactly tags' detail: " + types(details));
        assertAll(
                () -> assertPerStringDetail(details.get(0), "tags", "query", MAX_CHARS),
                () -> assertEquals(0, verifier.calls(), "no file-content verifier may run after the rejection"),
                () -> assertValueFree(details, A_RUN, CATASTROPHIC_TEXT));
    }

    /**
     * TP-005 (body stop): a body property carrying {@link #CATASTROPHIC}, sent the 4,097-character value, with a
     * violating named upload and a counting file-content verifier. Exactly the body's detail: the file part and
     * the verifier are never reached. The Given: with a short value, the gate reports the upload, so it is a
     * violation the gate reports once reached.
     */
    private static void bodyRejectionStopsValidation() {
        CountingVerifier verifier = new CountingVerifier();
        Handler<RoutingContext> gate = gate(
                defaults(),
                operation(List.of(), true, List.of(new FilePartDescriptor("avatar", List.of("image/png"), -1))),
                OperationSchemas.builder()
                        .bodySchema(new JsonObject()
                                .put("type", "object")
                                .put("properties", new JsonObject().put("text", catastrophicString())))
                        .build(),
                Set.of(verifier));
        RoutingContext inBound = new GateRequest()
                .body(new JsonObject().put("text", "aa"))
                .upload(upload("avatar", "application/pdf"))
                .context();
        RoutingContext ctx = new GateRequest()
                .body(new JsonObject().put("text", OVER_LONG_CATASTROPHIC_INPUT))
                .upload(upload("avatar", "application/pdf"))
                .context();

        List<ValidationErrorDetail> reached = handle(gate, inBound, ROW_TIMEOUT).rejection();
        assertEquals(
                List.of("file"),
                reached.stream().map(ValidationErrorDetail::location).toList(),
                () -> "the Given: the upload violates once reached: " + types(reached));

        List<ValidationErrorDetail> details = handle(gate, ctx, ROW_TIMEOUT).rejection();

        assertEquals(1, details.size(), () -> "exactly the body's detail: " + types(details));
        assertAll(
                () -> assertPerStringDetail(details.get(0), "", "body", MAX_CHARS),
                () -> assertEquals(0, verifier.calls(), "no file-content verifier may run after the rejection"),
                () -> assertValueFree(details, A_RUN, CATASTROPHIC_TEXT));
    }

    /**
     * TP-005 (format validator): at the bound entry, with the default limits and inside one counting window,
     * a 4,097-character string throws {@code PatternInputTooLong} (and is not counted), 64 strings of 4,096
     * characters pass (262,144, the limit), and one more character throws {@code
     * PatternInputTotalExceeded}. Both exceptions have empty stack traces and messages free of the value.
     */
    private static void formatValidatorThrowsBothExceptions() {
        PatternInputGuard guard = new PatternInputGuard(MAX_CHARS, MAX_TOTAL_CHARS);
        PatternInputGuard.PatternInputTooLong tooLong;
        PatternInputGuard.PatternInputTotalExceeded totalExceeded;
        try (PatternInputGuard.Window window = guard.openWindow()) {
            tooLong = assertThrows(
                    PatternInputGuard.PatternInputTooLong.class,
                    () -> guard.validateFormat("string", BOUND, OVER_LONG_CATASTROPHIC_INPUT));
            for (int string = 0; string < 64; string++) {
                assertNull(
                        guard.validateFormat("string", BOUND, "a".repeat(MAX_CHARS)),
                        "strings up to the total pass the bound entry");
            }
            totalExceeded = assertThrows(
                    PatternInputGuard.PatternInputTotalExceeded.class,
                    () -> guard.validateFormat("string", BOUND, "a"));
        }

        assertAll(
                () -> assertEquals(0, tooLong.getStackTrace().length, "PatternInputTooLong must be stack-trace-free"),
                () -> assertEquals(
                        0, totalExceeded.getStackTrace().length, "PatternInputTotalExceeded must be stack-trace-free"),
                () -> assertFalse(
                        String.valueOf(tooLong.getMessage()).contains(A_RUN), "the message must not carry the value"),
                () -> assertFalse(
                        String.valueOf(totalExceeded.getMessage()).contains(A_RUN),
                        "the message must not carry the value"));
    }

    // --- TP-006: the per-string bound on keys ---

    /**
     * TP-006. A 4,097-character key is rejected before a {@code patternProperties} or {@code propertyNames}
     * regex runs; a 4,096-character key reaches the key regex and keeps the unguarded verdict (ruling E1:
     * on {@code ^a*$}, since no all-{@code a} key over 900 characters finishes against {@link
     * #CATASTROPHIC}).
     *
     * @param row the row's proof
     * @throws Throwable when the row's assertion fails
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("overLongKeyRows")
    @DisplayName("TP-006: an over-long key is rejected before patternProperties or propertyNames regexes run")
    void overLongKeyIsRejectedBeforeKeyRegexesRun(Executable row) throws Throwable {
        row.execute();
    }

    /**
     * TP-006's rows.
     *
     * @return the rows
     */
    static Stream<Named<Executable>> overLongKeyRows() {
        return Stream.of(
                Named.<Executable>of(
                        "(patternProperties) a 4,097-character key",
                        () -> overLongKeyIsRejected(new JsonObject()
                                .put("type", "object")
                                .put("patternProperties", new JsonObject().put(CATASTROPHIC, new JsonObject())))),
                Named.<Executable>of(
                        "(propertyNames) a 4,097-character key",
                        () -> overLongKeyIsRejected(new JsonObject()
                                .put("type", "object")
                                .put("propertyNames", new JsonObject().put("pattern", CATASTROPHIC)))),
                Named.of(
                        "(in bound, ruling E1) a 4,096-character key reaches the key regex with the unguarded verdict",
                        PatternInputGuardTest::inBoundKeyReachesTheKeyRegex));
    }

    /**
     * A TP-006 over-long row: a body object with one 4,097-character key.
     *
     * @param schema the body schema whose key regex is {@link #CATASTROPHIC}
     */
    private static void overLongKeyIsRejected(JsonObject schema) {
        RoutingContext ctx = new GateRequest()
                .body(new JsonObject().put(OVER_LONG_CATASTROPHIC_INPUT, "v"))
                .context();

        List<ValidationErrorDetail> details =
                handle(bodyGate(defaults(), schema), ctx, ROW_TIMEOUT).rejection();

        assertEquals(1, details.size(), () -> "exactly one detail: " + types(details));
        assertPerStringDetail(details.get(0), "", "body", MAX_CHARS);
        assertValueFree(details, A_RUN, CATASTROPHIC_TEXT);
    }

    /**
     * TP-006 (in bound): {@code {"type":"object","patternProperties":{"^a*$":{"type":"integer"}}}} with a
     * 4,096-character key and a string value: no bound detail, the unguarded verdict and violations, and a
     * {@code type} violation, which only the key regex matching can produce.
     */
    private static void inBoundKeyReachesTheKeyRegex() {
        JsonObject schema = schema("{'type':'object','patternProperties':{'^a*$':{'type':'integer'}}}");
        JsonObject body = new JsonObject().put("a".repeat(MAX_CHARS), "v");

        GateOutcome outcome = handle(
                bodyGate(defaults(), schema), new GateRequest().body(body).context(), ROW_TIMEOUT);

        assertAll(
                () -> assertNoBoundDetail(outcome),
                () -> assertMatchesUnguarded(outcome, schema, body),
                () -> assertTrue(
                        detailTypes(outcome).contains("type"),
                        () -> "the key regex must run: its subschema's type check reports the value, got "
                                + detailTypes(outcome)));
    }

    // --- TP-007: the per-request total ---

    /**
     * TP-007. The request total rejects at the crossing with one value-free {@code patternInputTotalLength}
     * detail and no pattern evaluated after it, counts a string once per checking node, and is reset for every
     * request, including one that ended in a per-string rejection.
     *
     * @param row the row's proof
     * @throws Throwable when the row's assertion fails
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("requestTotalRows")
    @DisplayName("TP-007: the request total rejects at the crossing, counts every check, and resets per request")
    void requestTotalIsBoundedPerRequest(Executable row) throws Throwable {
        row.execute();
    }

    /**
     * TP-007's rows.
     *
     * @return the rows
     */
    static Stream<Named<Executable>> requestTotalRows() {
        return Stream.of(
                Named.of(
                        "(a) 65 strings of 4,000 then catastrophic ones: rejected at the 66th string, unevaluated",
                        PatternInputGuardTest::totalRejectsAtTheCrossing),
                Named.of(
                        "(b) two pattern nodes count each string twice: 262,144 accepted, 262,146 rejected",
                        PatternInputGuardTest::stringCheckedTwiceCountsTwice),
                Named.of(
                        "(c) two sequential 200,000-character requests on one thread are both accepted",
                        PatternInputGuardTest::totalResetsBetweenRequests),
                Named.of(
                        "(d) a request ended by a per-string rejection leaves no tally for the next",
                        PatternInputGuardTest::totalResetsAfterAPerStringRejection),
                Named.of(
                        "(e) a request ended by an escaping non-bound exception leaves no tally for the next on its"
                                + " thread",
                        PatternInputGuardTest::totalResetsAfterAnEscapingException));
    }

    /**
     * TP-007 (a): 65 strings of 4,000 {@code b} characters, which {@link #CATASTROPHIC} rejects in
     * microseconds, then 35 catastrophic strings of 4,000 characters: the 66th string crosses 262,144
     * (264,000) and is rejected before its pattern runs.
     */
    private static void totalRejectsAtTheCrossing() {
        JsonObject schema = new JsonObject().put("type", "array").put("items", catastrophicString());
        JsonArray body = strings(65, "b".repeat(4000)).addAll(strings(35, "a".repeat(3999) + "!"));

        List<ValidationErrorDetail> details = handle(
                        bodyGate(defaults(), schema),
                        new GateRequest().body(body).context(),
                        ROW_TIMEOUT)
                .rejection();

        assertEquals(1, details.size(), () -> "exactly one detail: " + types(details));
        assertPerRequestDetail(details.get(0), "", "body", MAX_TOTAL_CHARS);
        assertValueFree(details, A_RUN, B_RUN, CATASTROPHIC_TEXT);
    }

    /**
     * TP-007 (b): items checked by two pattern nodes; 32 strings of 4,096 count 262,144 (accepted), and one
     * more {@code a} counts 262,146 (rejected with the total detail).
     */
    private static void stringCheckedTwiceCountsTwice() {
        Handler<RoutingContext> gate = bodyGate(
                defaults(), schema("{'type':'array','items':{'allOf':[{'pattern':'^a*$'},{'pattern':'^a*$'}]}}"));
        JsonArray atLimit = strings(32, "a".repeat(MAX_CHARS));
        JsonArray overLimit = atLimit.copy().add("a");

        GateOutcome first = handle(gate, new GateRequest().body(atLimit).context(), ROW_TIMEOUT);
        GateOutcome second = handle(gate, new GateRequest().body(overLimit).context(), ROW_TIMEOUT);

        assertAll(() -> first.assertAccepted("a body counted at exactly 262,144"), () -> {
            List<ValidationErrorDetail> details = second.rejection();
            assertEquals(1, details.size(), () -> "exactly one detail: " + types(details));
            assertPerRequestDetail(details.get(0), "", "body", MAX_TOTAL_CHARS);
            assertValueFree(details, A_RUN);
        });
    }

    /** TP-007 (c): two requests of 50 strings of 4,000 characters (200,000 each), on one thread. */
    private static void totalResetsBetweenRequests() {
        Handler<RoutingContext> gate = bodyGate(defaults(), patternedStrings());
        RoutingContext first =
                new GateRequest().body(strings(50, "a".repeat(4000))).context();
        RoutingContext second =
                new GateRequest().body(strings(50, "a".repeat(4000))).context();

        assertTimeoutPreemptively(ROW_TIMEOUT, () -> {
            gate.handle(first);
            gate.handle(second);
        });

        assertAll(() -> outcomeOf(first).assertAccepted("the first 200,000-character request"), () -> outcomeOf(second)
                .assertAccepted("the second 200,000-character request"));
    }

    /**
     * TP-007 (d): 65 strings of 4,000 characters (260,000 counted) then one of 4,097, which the per-string
     * bound rejects; then, on the same thread, a request with one string of 4,000 characters, accepted only
     * if the first request's tally did not survive it (260,000 + 4,000 would cross).
     */
    private static void totalResetsAfterAPerStringRejection() {
        Handler<RoutingContext> gate = bodyGate(defaults(), patternedStrings());
        RoutingContext first = new GateRequest()
                .body(strings(65, "a".repeat(4000)).add("a".repeat(MAX_CHARS + 1)))
                .context();
        RoutingContext second =
                new GateRequest().body(strings(1, "a".repeat(4000))).context();

        assertTimeoutPreemptively(ROW_TIMEOUT, () -> {
            gate.handle(first);
            gate.handle(second);
        });

        assertAll(
                () -> {
                    List<ValidationErrorDetail> details = outcomeOf(first).rejection();
                    assertEquals(1, details.size(), () -> "exactly one detail: " + types(details));
                    assertPerStringDetail(details.get(0), "", "body", MAX_CHARS);
                    assertValueFree(details, A_RUN);
                },
                () -> outcomeOf(second).assertAccepted("the request after a per-string rejection"));
    }

    /**
     * TP-007 (e): with limits of 16 and 32, a body array whose first item is an array of strings at {@link
     * #ALL_A} and whose second item is ruling D-1's node, {@code {"type":"string","format":["idn-hostname"]}},
     * at which the engine throws {@link ClassCastException}. The first request counts one 16-character string
     * and then throws that exception, which propagates out of the gate unchanged; then, on the same thread, a
     * request with two 16-character strings (counted at exactly the total, 32) is accepted only if the first
     * request's tally did not survive its exception (16 + 32 would cross). The Given, inside one window of a
     * guard with the same limits: the first body throws only after its string is counted, so a surviving tally
     * rejects the second body.
     */
    private static void totalResetsAfterAnEscapingException() {
        JsonObject schema = schema("{'type':'array','prefixItems':["
                + "{'type':'array','items':{'type':'string','pattern':'^a*$'}},"
                + "{'type':'string','format':['idn-hostname']}]}");
        JsonArray firstBody = new JsonArray().add(strings(1, "a".repeat(16))).add("abc");
        JsonArray secondBody = new JsonArray().add(strings(2, "a".repeat(16)));
        PatternInputGuard guard = new PatternInputGuard(16, 32);
        Validator guarded = guard.compile(schema.copy(), GATE_OPTIONS);
        try (PatternInputGuard.Window window = guard.openWindow()) {
            assertThrows(
                    ClassCastException.class,
                    () -> guarded.validate(firstBody),
                    "the Given: the first body throws the engine's non-bound exception");
            assertThrows(
                    PatternInputGuard.PatternInputTotalExceeded.class,
                    () -> guarded.validate(secondBody),
                    "the Given: the first body is counted before it throws, so its tally would reject the second");
        }
        JaxRsConfig smallLimits = JaxRsConfig.builder()
                .validationPatternMaxChars(16)
                .validationPatternMaxTotalChars(32)
                .build();
        Handler<RoutingContext> gate = bodyGate(smallLimits, schema);
        RoutingContext first = new GateRequest().body(firstBody).context();
        RoutingContext second = new GateRequest().body(secondBody).context();
        AtomicReference<Throwable> escaped = new AtomicReference<>();
        List<Thread> threads = new CopyOnWriteArrayList<>();

        assertTimeoutPreemptively(ROW_TIMEOUT, () -> {
            threads.add(Thread.currentThread());
            try {
                gate.handle(first);
            } catch (RuntimeException thrown) {
                escaped.set(thrown);
            }
            threads.add(Thread.currentThread());
            gate.handle(second);
        });

        assertAll(
                () -> assertEquals(2, threads.size(), "both requests were handled"),
                () -> assertSame(
                        threads.get(0), threads.get(1), "both requests must run on one thread, the tally's scope"),
                () -> assertInstanceOf(
                        ClassCastException.class,
                        escaped.get(),
                        "the first request's non-bound exception propagates out of the gate unchanged"),
                () -> assertEquals(
                        new GateOutcome(0, List.of()),
                        outcomeOf(first),
                        "the first request neither fails through the context nor reaches the next handler"),
                () -> outcomeOf(second)
                        .assertAccepted("the request after an escaping exception, counted at exactly the total"));
    }

    // --- TP-014: exactly the three bounded formats ---

    /**
     * TP-014. Exactly {@code idn-hostname}, {@code idn-email}, and {@code regex} are bounded and counted, at
     * a bound entry evaluated before the engine's own check; every other format keeps the unguarded verdict
     * and violations and is neither bounded nor counted.
     *
     * @param row the row's proof
     * @throws Throwable when the row's assertion fails
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("boundedFormatRows")
    @DisplayName("TP-014: exactly the three bounded formats are bounded and counted, before their checks run")
    void onlyBoundedFormatsAreBoundedAndCounted(Executable row) throws Throwable {
        row.execute();
    }

    /**
     * TP-014's rows, labelled with the contract's case letters.
     *
     * @return the rows
     */
    static Stream<Named<Executable>> boundedFormatRows() {
        Stream<Named<Executable>> boundedFormats = Stream.of("idn-hostname", "idn-email", "regex")
                .map(format -> Named.<Executable>of(
                        "(a) format " + format + ": a 4,097-character body string is rejected at the bound",
                        () -> boundedFormatBodyIsRejected(format)));
        Stream<Named<Executable>> single = Stream.of(
                Named.of(
                        "(b) format idn-hostname: a 4,097-character query parameter is rejected at the bound",
                        PatternInputGuardTest::boundedFormatQueryParameterIsRejected),
                Named.of(
                        "(c) a bounded-format string is counted: 260,000 + 4,000 crosses the total",
                        PatternInputGuardTest::boundedFormatStringIsCounted),
                Named.of(
                        "(d) a string at a pattern and a bounded format counts twice: 262,144 accepted, 262,146"
                                + " rejected",
                        PatternInputGuardTest::patternAndBoundedFormatCountTwice),
                Named.of(
                        "(e) a non-string instance at a bounded format passes",
                        PatternInputGuardTest::nonStringAtABoundedFormatPasses));
        Stream<Named<Executable>> unboundedFormats = Stream.of(
                        "date",
                        "time",
                        "date-time",
                        "duration",
                        "email",
                        "hostname",
                        "ipv4",
                        "ipv6",
                        "uuid",
                        "iri",
                        "iri-reference",
                        "x-app-format")
                .map(format -> Named.<Executable>of(
                        "(f) format " + format + ": 5,000 characters are neither bounded nor counted",
                        () -> unboundedFormatIsNotBounded(format)));
        Stream<Named<Executable>> cFormats = cFormatValues().entrySet().stream()
                .map(entry -> Named.<Executable>of(
                        "(f2) format " + entry.getKey() + ": three valid values over limits of 16 and 32 are"
                                + " neither bounded nor counted",
                        () -> cFormatValuesAreNotCounted(entry.getKey(), entry.getValue())));
        Stream<Named<Executable>> tail = Stream.of(
                Named.of(
                        "(g) 280,000 date-time and 280,000 iri characters are not counted",
                        PatternInputGuardTest::unboundedFormatsAreNotCounted),
                Named.of(
                        "(h) SLOW_IDN at idn-hostname is rejected at the bound before the engine's own check",
                        PatternInputGuardTest::boundedFormatIsRejectedBeforeItsCheck));
        return Stream.of(boundedFormats, single, unboundedFormats, cFormats, tail)
                .flatMap(rows -> rows);
    }

    /**
     * TP-014 (a): {@code {"type":"string","format":F}} with a 4,097-character body string.
     *
     * @param format the bounded format
     */
    private static void boundedFormatBodyIsRejected(String format) {
        RoutingContext ctx = new GateRequest().body("a".repeat(MAX_CHARS + 1)).context();

        List<ValidationErrorDetail> details = handle(bodyGate(defaults(), formatString(format)), ctx, ROW_TIMEOUT)
                .rejection();

        assertEquals(1, details.size(), () -> "exactly one detail: " + types(details));
        assertPerStringDetail(details.get(0), "", "body", MAX_CHARS);
        assertValueFree(details, A_RUN);
    }

    /** TP-014 (b): an {@code idn-hostname} query parameter with a 4,097-character value. */
    private static void boundedFormatQueryParameterIsRejected() {
        Handler<RoutingContext> gate = gate(
                defaults(),
                operation(List.of(param("host", ParamLocation.QUERY, String.class, null)), false, List.of()),
                OperationSchemas.builder()
                        .parameterSchema(ParamLocation.QUERY, "host", formatString("idn-hostname"))
                        .build(),
                Set.of());
        RoutingContext ctx = new GateRequest()
                .parameter(ParamLocation.QUERY, "host", "a".repeat(MAX_CHARS + 1))
                .context();

        List<ValidationErrorDetail> details = handle(gate, ctx, ROW_TIMEOUT).rejection();

        assertEquals(1, details.size(), () -> "exactly one detail: " + types(details));
        assertPerStringDetail(details.get(0), "host", "query", MAX_CHARS);
        assertValueFree(details, A_RUN);
    }

    /**
     * TP-014 (c): 65 {@code items} values of 4,000 characters at a pattern (260,000) and an {@code idn-email}
     * {@code tail} of 4,000: the total crosses only if the bounded-format string is counted.
     */
    private static void boundedFormatStringIsCounted() {
        JsonObject schema = schema("{'type':'object','properties':{'items':{'type':'array','items':{'type':'string',"
                + "'pattern':'^a*$'}},'tail':{'type':'string','format':'idn-email'}}}");
        JsonObject body =
                new JsonObject().put("items", strings(65, "a".repeat(4000))).put("tail", "a".repeat(4000));

        List<ValidationErrorDetail> details = handle(
                        bodyGate(defaults(), schema),
                        new GateRequest().body(body).context(),
                        ROW_TIMEOUT)
                .rejection();

        assertEquals(1, details.size(), () -> "exactly one detail: " + types(details));
        assertPerRequestDetail(details.get(0), "", "body", MAX_TOTAL_CHARS);
        assertValueFree(details, A_RUN);
    }

    /**
     * TP-014 (d): 63 {@code items} values of 4,096 characters (258,048) and a {@code both} value at a {@code
     * pattern} and a {@code regex} format, counted at both entries: 2,048 characters make 262,144 (accepted)
     * and 2,049 make 262,146 (rejected). Both {@code both} values pass the default {@code regex} check (the
     * Given, recorded at step 3).
     */
    private static void patternAndBoundedFormatCountTwice() {
        JsonObject schema = schema("{'type':'object','properties':{'items':{'type':'array','items':{'type':'string',"
                + "'pattern':'^a*$'}},'both':{'type':'string','pattern':'^a*$','format':'regex'}}}");
        JsonObject atLimit = new JsonObject()
                .put("items", strings(63, "a".repeat(MAX_CHARS)))
                .put("both", "a".repeat(2048));
        JsonObject overLimit = atLimit.copy().put("both", "a".repeat(2049));
        assertAll(
                "the Given: the default regex check accepts both values",
                () -> assertEquals(
                        Boolean.TRUE, unguarded(schema).validate(atLimit).getValid()),
                () -> assertEquals(
                        Boolean.TRUE, unguarded(schema).validate(overLimit).getValid()));
        Handler<RoutingContext> gate = bodyGate(defaults(), schema);

        GateOutcome first = handle(gate, new GateRequest().body(atLimit).context(), ROW_TIMEOUT);
        GateOutcome second = handle(gate, new GateRequest().body(overLimit).context(), ROW_TIMEOUT);

        assertAll(() -> first.assertAccepted("a body counted at exactly 262,144"), () -> {
            List<ValidationErrorDetail> details = second.rejection();
            assertEquals(1, details.size(), () -> "exactly one detail: " + types(details));
            assertPerRequestDetail(details.get(0), "", "body", MAX_TOTAL_CHARS);
            assertValueFree(details, A_RUN);
        });
    }

    /** TP-014 (e): {@code {"type":["string","integer"],"format":"idn-email"}} with the integer 5. */
    private static void nonStringAtABoundedFormatPasses() {
        JsonObject schema = schema("{'type':['string','integer'],'format':'idn-email'}");
        assertEquals(Boolean.TRUE, unguarded(schema).validate(5).getValid(), "the unguarded compilation accepts 5");

        GateOutcome outcome =
                handle(bodyGate(defaults(), schema), new GateRequest().body(5).context(), ROW_TIMEOUT);

        outcome.assertAccepted("an integer at a bounded format");
    }

    /**
     * TP-014 (f): {@code {"type":"string","format":F}} with 5,000 {@code a} characters: no bound detail and
     * exactly the unguarded verdict and violations. Proves only that the format is neither bounded nor
     * counted; its linearity rests on the security recheck's measurements (SP2-001).
     *
     * @param format the unbounded format
     */
    private static void unboundedFormatIsNotBounded(String format) {
        JsonObject schema = formatString(format);
        String value = "a".repeat(5000);

        GateOutcome outcome = handle(
                bodyGate(defaults(), schema), new GateRequest().body(value).context(), ROW_TIMEOUT);

        assertAll(
                () -> assertNoBoundDetail(outcome),
                () -> assertMatchesUnguarded(outcome, schema, value),
                () -> assertValueFree(allDetails(outcome), A_RUN));
    }

    /**
     * TP-014 (f2): with limits of 16 and 32, an array of three valid values of 40 to 64 characters at one of
     * C-FORMAT's seven formats: no bound detail and exactly the unguarded verdict, so the format is neither
     * bounded nor counted.
     *
     * @param format the C-FORMAT format
     * @param values the three valid values chosen at step 3
     */
    private static void cFormatValuesAreNotCounted(String format, List<String> values) {
        JsonObject schema = new JsonObject().put("type", "array").put("items", formatString(format));
        JsonArray body = new JsonArray(new ArrayList<>(values));
        assertAll(
                "the Given: three values of 40 to 64 characters, each over 16, together over 32, valid by the"
                        + " engine's own check",
                () -> assertEquals(3, values.size()),
                () -> assertTrue(values.stream().allMatch(value -> value.length() >= 40 && value.length() <= 64)),
                () -> assertTrue(values.stream().mapToInt(String::length).sum() > 32),
                () -> assertEquals(
                        Boolean.TRUE, unguarded(schema).validate(body).getValid()));
        JaxRsConfig smallLimits = JaxRsConfig.builder()
                .validationPatternMaxChars(16)
                .validationPatternMaxTotalChars(32)
                .build();

        GateOutcome outcome = handle(
                bodyGate(smallLimits, schema), new GateRequest().body(body).context(), ROW_TIMEOUT);

        assertAll(() -> assertNoBoundDetail(outcome), () -> assertMatchesUnguarded(outcome, schema, body));
    }

    /**
     * TP-014 (f2)'s values, chosen at step 3 and recorded with the engine's verdict (valid): three per
     * format, each 40 to 64 characters.
     *
     * @return the values by format, in C-FORMAT's order
     */
    private static Map<String, List<String>> cFormatValues() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put(
                "uri",
                List.of(
                        "https://example.com/catalogue/items/0001?view=full",
                        "https://example.com/catalogue/items/0002?view=full",
                        "https://example.com/catalogue/items/0003?view=full"));
        values.put(
                "uri-reference",
                List.of(
                        "/catalogue/items/0001/attachments/primary?view=full",
                        "/catalogue/items/0002/attachments/primary?view=full",
                        "/catalogue/items/0003/attachments/primary?view=full"));
        values.put(
                "url",
                List.of(
                        "https://example.com/catalogue/items/0001?view=full",
                        "https://example.com/catalogue/items/0002?view=full",
                        "https://example.com/catalogue/items/0003?view=full"));
        values.put(
                "json-pointer",
                List.of(
                        "/catalogue/items/0001/attachments/primary/name",
                        "/catalogue/items/0002/attachments/primary/name",
                        "/catalogue/items/0003/attachments/primary/name"));
        values.put(
                "relative-json-pointer",
                List.of(
                        "0/catalogue/items/0001/attachments/primary/name",
                        "1/catalogue/items/0002/attachments/primary/name",
                        "2/catalogue/items/0003/attachments/primary/name"));
        values.put(
                "json-pointer-uri-fragment",
                List.of(
                        "#/catalogue/items/0001/attachments/primary/name",
                        "#/catalogue/items/0002/attachments/primary/name",
                        "#/catalogue/items/0003/attachments/primary/name"));
        values.put(
                "uri-template",
                List.of(
                        "https://example.com/catalogue/items/{itemId}{?view,page}",
                        "https://example.com/catalogue/orders/{orderId}{?view,page}",
                        "https://example.com/catalogue/owners/{ownerId}{?view,page}"));
        return values;
    }

    /**
     * TP-014 (g): 14,000 {@code date-time} values (280,000 characters), 70 {@code iri} values of 4,000
     * (280,000), and a patterned {@code note} of 4,000: accepted, so neither unbounded format was counted.
     */
    private static void unboundedFormatsAreNotCounted() {
        JsonObject schema = schema("{'type':'object','properties':{"
                + "'stamps':{'type':'array','items':{'type':'string','format':'date-time'}},"
                + "'refs':{'type':'array','items':{'type':'string','format':'iri'}},"
                + "'note':{'type':'string','pattern':'^a*$'}}}");
        JsonObject body = new JsonObject()
                .put("stamps", strings(14_000, "2026-09-27T12:00:00Z"))
                .put("refs", strings(70, "a".repeat(4000)))
                .put("note", "a".repeat(4000));
        assertEquals(
                Boolean.TRUE,
                unguarded(schema).validate(body).getValid(),
                "the Given: the unguarded compilation accepts the body (recorded at step 3)");

        GateOutcome outcome = handle(
                bodyGate(defaults(), schema), new GateRequest().body(body).context(), ROW_TIMEOUT);

        outcome.assertAccepted("280,000 date-time and 280,000 iri characters beside a 4,000-character pattern");
    }

    /**
     * TP-014 (h): {@code {"type":"string","format":"idn-hostname"}} with {@link #SLOW_IDN}, answered within
     * {@link #SLOW_IDN_TIMEOUT} with the per-string detail: the bound entry ran before the engine's own
     * {@code idn-hostname} check (upgrade invariant 4 through the real gate).
     */
    private static void boundedFormatIsRejectedBeforeItsCheck() {
        RoutingContext ctx = new GateRequest().body(SLOW_IDN).context();

        List<ValidationErrorDetail> details = handle(
                        bodyGate(defaults(), formatString("idn-hostname")), ctx, SLOW_IDN_TIMEOUT)
                .rejection();

        assertEquals(1, details.size(), () -> "exactly one detail: " + types(details));
        assertPerStringDetail(details.get(0), "", "body", MAX_CHARS);
        assertValueFree(details, A_RUN);
    }

    // --- TP-015: the engine behaviors the guard relies on ---

    /**
     * TP-015. The five vertx-json-schema 5.1.6 behaviors the guard relies on hold: (1) a node's
     * {@code allOf} runs before its {@code pattern} and key regexes; (2) the custom format validator is
     * called for every format; (3) its exception escapes {@code validate()} unwrapped; (4) a node's
     * {@code allOf} runs before the engine's own check for the node's {@code format}; (5) a format name
     * the engine does not know passes its built-in check and still reaches the custom format validator.
     * Rows (vi) to (viii) pin two further engine facts the rewrite relies on: (a) a node whose {@code allOf}
     * is present but not an array fails at the {@code allOf} step before its other keywords run, which is
     * why the rewrite leaves such a node without an entry; (b) {@code allOf} evaluates every branch, so an
     * appended entry runs even when an earlier branch fails. The third, (c) {@code propertyNames} marks no
     * key evaluated, is pinned by TP-004's {@code unevaluatedProperties} row. The conversion row pins that a
     * guard exception still produces the 400 detail when it arrives wrapped.
     *
     * @param row the row's proof
     * @throws Throwable when the row's assertion fails
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource({"engineBehaviorsTheGuardReliesOnHoldRows", "patternInputBoundConversionRows"})
    @DisplayName("TP-015: the engine behaviors the guard relies on hold")
    void engineBehaviorsTheGuardReliesOnHold(Executable row) throws Throwable {
        row.execute();
    }

    /**
     * TP-015's rows, labelled with the contract's case letters.
     *
     * @return the rows
     */
    static Stream<Named<Executable>> engineBehaviorsTheGuardReliesOnHoldRows() {
        return Stream.of(
                Named.of(
                        "(i) a node's allOf runs before its pattern, and the hook's exception escapes unwrapped"
                                + " (invariants 1, 3)",
                        PatternInputGuardTest::allOfRunsBeforePattern),
                Named.of(
                        "(ii) a node's allOf runs before its patternProperties key regexes, and the hook's"
                                + " exception escapes unwrapped (invariants 1, 3)",
                        PatternInputGuardTest::allOfRunsBeforeKeyRegexes),
                Named.of(
                        "(iii) the format validator is called once for every format, built-in and unknown alike"
                                + " (invariant 2)",
                        PatternInputGuardTest::formatValidatorIsCalledForEveryFormat),
                Named.of(
                        "(iv) a node's allOf runs before the engine's own check for its format (invariant 4)",
                        PatternInputGuardTest::allOfRunsBeforeTheEnginesFormatCheck),
                Named.of(
                        "(v) a format name the engine does not know passes its built-in check and reaches the"
                                + " format validator (invariant 5)",
                        PatternInputGuardTest::unknownFormatNameReachesTheFormatValidator),
                Named.<Executable>of(
                        "(vi) a node whose allOf is an object fails at the allOf step, before its pattern runs"
                                + " (engine fact a)",
                        () -> allOfThatIsNotAnArrayFailsBeforePattern(new JsonObject(), ClassCastException.class)),
                Named.<Executable>of(
                        "(vii) a node whose allOf is null fails at the allOf step, before its pattern runs"
                                + " (engine fact a)",
                        () -> allOfThatIsNotAnArrayFailsBeforePattern(null, NullPointerException.class)),
                Named.of(
                        "(viii) allOf evaluates every branch: a branch after a failing one still runs"
                                + " (engine fact b)",
                        PatternInputGuardTest::allOfEvaluatesEveryBranch));
    }

    /**
     * TP-015 (i): {@code {"allOf": [{"format": "probe"}], "pattern": CATASTROPHIC}} on a catastrophic
     * string throws the recorder's sentinel itself within the timeout.
     */
    private static void allOfRunsBeforePattern() {
        RecordingFormatValidator recorder = new RecordingFormatValidator();
        Validator validator = compile(
                new JsonObject()
                        .put("allOf", new JsonArray().add(probeFormat()))
                        .put("pattern", CATASTROPHIC),
                recorder);

        Throwable thrown = assertTimeoutPreemptively(
                ROW_TIMEOUT,
                () -> assertThrows(Throwable.class, () -> validator.validate(OVER_LONG_CATASTROPHIC_INPUT)),
                "the allOf entry must run before the catastrophic pattern, or validation never finishes");

        assertSame(
                recorder.sentinel,
                thrown,
                "the format validator's exception must escape validate() as the very instance thrown, not"
                        + " a wrapper");
    }

    /**
     * TP-015 (ii): {@code {"allOf": [{"propertyNames": {"format": "probe"}}], "patternProperties":
     * {CATASTROPHIC: {}}}} on an object with a catastrophic key throws the recorder's sentinel itself
     * within the timeout.
     */
    private static void allOfRunsBeforeKeyRegexes() {
        RecordingFormatValidator recorder = new RecordingFormatValidator();
        Validator validator = compile(
                new JsonObject()
                        .put("allOf", new JsonArray().add(new JsonObject().put("propertyNames", probeFormat())))
                        .put("patternProperties", new JsonObject().put(CATASTROPHIC, new JsonObject())),
                recorder);
        JsonObject instance = new JsonObject().put(OVER_LONG_CATASTROPHIC_INPUT, "v");

        Throwable thrown = assertTimeoutPreemptively(
                ROW_TIMEOUT,
                () -> assertThrows(Throwable.class, () -> validator.validate(instance)),
                "the allOf entry must run before the catastrophic key regex, or validation never finishes");

        assertSame(
                recorder.sentinel,
                thrown,
                "the format validator's exception must escape validate() as the very instance thrown, not"
                        + " a wrapper");
    }

    /**
     * TP-015 (iii): an object whose properties carry {@code date-time}, {@code email},
     * {@code idn-hostname}, {@code uri}, {@code uri-reference}, {@code url}, {@code uuid} and
     * {@code x-app-format}, each with a short string: the recorder sees exactly one call per format.
     * Calls without a format (the engine calls the validator per node, passing {@code null} where the
     * node has none) are not counted.
     */
    private static void formatValidatorIsCalledForEveryFormat() {
        List<Call> values = List.of(
                new Call("date-time", "2026-09-27T12:00:00Z"),
                new Call("email", "user@example.com"),
                new Call("idn-hostname", "example.com"),
                new Call("uri", "https://example.com/a"),
                new Call("uri-reference", "/a/b"),
                new Call("url", "https://example.com/b"),
                new Call("uuid", "00000000-0000-0000-0000-000000000001"),
                new Call("x-app-format", "anything"));
        JsonObject properties = new JsonObject();
        JsonObject instance = new JsonObject();
        for (Call value : values) {
            properties.put(
                    value.format(), new JsonObject().put("type", "string").put("format", value.format()));
            instance.put(value.format(), value.instance());
        }
        RecordingFormatValidator recorder = new RecordingFormatValidator();
        Validator validator = compile(new JsonObject().put("type", "object").put("properties", properties), recorder);

        assertTimeoutPreemptively(ROW_TIMEOUT, () -> validator.validate(instance));

        assertEquals(
                values.stream().sorted().toList(),
                recorder.formatCalls().stream().sorted().toList(),
                "the format validator must be called exactly once for every format, built-in and unknown"
                        + " alike, with the property's value");
    }

    /**
     * TP-015 (iv): {@code {"allOf": [{"format": "probe"}], "format": "uri"}} on a 5,000-character URI,
     * validated on a thread with a 1 MB stack, throws the recorder's sentinel and no {@link
     * StackOverflowError}: the {@code allOf} entry ran before the engine's own {@code uri} expression.
     */
    private static void allOfRunsBeforeTheEnginesFormatCheck() {
        RecordingFormatValidator recorder = new RecordingFormatValidator();
        Validator validator = compile(
                new JsonObject()
                        .put("allOf", new JsonArray().add(probeFormat()))
                        .put("format", "uri"),
                recorder);

        SmallStackOutcome outcome = validateOnOneMegabyteStack(validator, LONG_URI);

        assertAll(
                () -> assertFalse(
                        outcome.uncaught() instanceof StackOverflowError,
                        "the engine's own uri expression must not run before the allOf entry; it overflowed"
                                + " the stack"),
                () -> assertSame(
                        recorder.sentinel,
                        outcome.uncaught(),
                        "the allOf entry must run, and throw, before the engine's own check for the node's"
                                + " format"),
                () -> assertNull(outcome.result(), "validation must not complete normally"));
    }

    /**
     * TP-015 (v): {@code {"format": "x-vertique-format-uri"}}, a name the engine does not know and for
     * which the recorder returns normally, is valid for {@code "not a uri at all"} and for the
     * 5,000-character URI, with no {@link StackOverflowError} on a 1 MB stack, and the recorder sees one
     * call per value under that name.
     */
    private static void unknownFormatNameReachesTheFormatValidator() {
        RecordingFormatValidator recorder = new RecordingFormatValidator();
        Validator validator = compile(new JsonObject().put("format", RENAMED_URI), recorder);

        SmallStackOutcome notAUri = validateOnOneMegabyteStack(validator, "not a uri at all");
        SmallStackOutcome longUri = validateOnOneMegabyteStack(validator, LONG_URI);

        assertAll(
                () -> assertNull(notAUri.uncaught(), "a short non-URI must validate without throwing"),
                () -> assertNull(longUri.uncaught(), "a 5,000-character URI must validate without throwing"),
                () -> assertEquals(
                        Boolean.TRUE,
                        notAUri.valid(),
                        "an unknown format name must pass the engine's built-in check: a non-URI is valid"),
                () -> assertEquals(
                        Boolean.TRUE,
                        longUri.valid(),
                        "an unknown format name must pass the engine's built-in check: the long URI is valid"),
                () -> assertEquals(
                        List.of(new Call(RENAMED_URI, "not a uri at all"), new Call(RENAMED_URI, LONG_URI)),
                        recorder.formatCalls(),
                        "an unknown format name must still reach the format validator, once per value"));
    }

    /**
     * TP-015 (vi) and (vii): {@code {"allOf": <allOf>, "pattern": CATASTROPHIC}}, whose {@code allOf} is not an
     * array, on a catastrophic string throws {@code expected} within the timeout: the engine fails at the
     * node's {@code allOf} step before its {@code pattern} runs, which is why the rewrite leaves such a node
     * without an entry.
     *
     * @param allOf    the node's {@code allOf} value, an object or {@code null}
     * @param expected the class of the exception the engine throws at the {@code allOf} step
     */
    private static void allOfThatIsNotAnArrayFailsBeforePattern(Object allOf, Class<? extends Throwable> expected) {
        RecordingFormatValidator recorder = new RecordingFormatValidator();
        Validator validator = compile(new JsonObject().put("allOf", allOf).put("pattern", CATASTROPHIC), recorder);

        Throwable thrown = assertTimeoutPreemptively(
                ROW_TIMEOUT,
                () -> assertThrows(Throwable.class, () -> validator.validate(OVER_LONG_CATASTROPHIC_INPUT)),
                "the allOf step must fail before the catastrophic pattern runs, or validation never finishes");

        assertEquals(
                expected,
                thrown.getClass(),
                "the engine must fail at the node's allOf step, not at a later keyword: " + thrown);
    }

    /**
     * TP-015 (viii): {@code {"allOf": [{"minLength": 99999}, {"format": "probe"}], "pattern": CATASTROPHIC}}
     * on a catastrophic string throws the recorder's sentinel itself within the timeout: the engine evaluates
     * the second branch after the first has failed, so an appended entry runs even when an earlier branch
     * fails.
     */
    private static void allOfEvaluatesEveryBranch() {
        RecordingFormatValidator recorder = new RecordingFormatValidator();
        Validator validator = compile(
                new JsonObject()
                        .put(
                                "allOf",
                                new JsonArray()
                                        .add(new JsonObject().put("minLength", 99_999))
                                        .add(probeFormat()))
                        .put("pattern", CATASTROPHIC),
                recorder);

        Throwable thrown = assertTimeoutPreemptively(
                ROW_TIMEOUT,
                () -> assertThrows(Throwable.class, () -> validator.validate(OVER_LONG_CATASTROPHIC_INPUT)),
                "the branch after the failing one must run before the catastrophic pattern, or validation never"
                        + " finishes");

        assertSame(
                recorder.sentinel,
                thrown,
                "the allOf branch after the failing minLength branch must be evaluated and throw the sentinel");
    }

    /**
     * TP-015's conversion row, after the engine rows.
     *
     * @return the row
     */
    static Stream<Named<Executable>> patternInputBoundConversionRows() {
        return Stream.of(Named.of(
                "(conversion) a guard exception converts to the same single detail whether thrown bare or as the"
                        + " cause of another exception, and an unrelated exception is left unconverted",
                PatternInputGuardTest::guardExceptionConvertsBareOrWrapped));
    }

    /**
     * TP-015 (conversion): the gate's conversion helper yields the same single {@code patternInputLength}
     * detail for {@code PatternInputTooLong} thrown bare and as the cause of another {@link
     * RuntimeException}, and nothing for an unrelated {@link RuntimeException}.
     */
    private static void guardExceptionConvertsBareOrWrapped() {
        PatternInputGuard.PatternInputTooLong bare = new PatternInputGuard.PatternInputTooLong(MAX_CHARS);
        RuntimeException wrapped =
                new RuntimeException("wrapper", new PatternInputGuard.PatternInputTooLong(MAX_CHARS));
        RuntimeException unrelated = new RuntimeException("unrelated");

        Optional<ValidationErrorDetail> fromBare = WebValidationStrategy.patternInputBoundDetail(bare, "body", null);
        Optional<ValidationErrorDetail> fromWrapped =
                WebValidationStrategy.patternInputBoundDetail(wrapped, "body", null);
        Optional<ValidationErrorDetail> fromUnrelated =
                WebValidationStrategy.patternInputBoundDetail(unrelated, "body", null);

        assertTrue(fromBare.isPresent(), "a bare guard exception must convert to the detail");
        assertAll(
                () -> assertPerStringDetail(fromBare.get(), "", "body", MAX_CHARS),
                () -> assertEquals(
                        fromBare, fromWrapped, "a wrapped guard exception must convert to the same single detail"),
                () -> assertEquals(Optional.empty(), fromUnrelated, "an unrelated exception must be left unconverted"));
    }

    // --- Engine-row helpers ---

    /**
     * Compiles a copy of {@code schema} with the gate's options and the given format validator; a copy
     * because {@link JsonSchema#of(JsonObject)} writes into the object it is given.
     *
     * @param schema  the schema
     * @param formats the format validator
     * @return the validator
     */
    private static Validator compile(JsonObject schema, JsonFormatValidator formats) {
        return Validator.create(JsonSchema.of(schema.copy()), GATE_OPTIONS, formats);
    }

    /**
     * Returns {@code {"format": "probe"}}.
     *
     * @return the probe format entry
     */
    private static JsonObject probeFormat() {
        return new JsonObject().put("format", PROBE);
    }

    /**
     * Validates {@code instance} on a new thread created with a 1 MB stack, inside the row timeout, and
     * captures the thread's uncaught throwable.
     *
     * @param validator the validator
     * @param instance  the instance
     * @return the result, or the uncaught throwable
     */
    private static SmallStackOutcome validateOnOneMegabyteStack(Validator validator, Object instance) {
        AtomicReference<OutputUnit> result = new AtomicReference<>();
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread thread =
                new Thread(null, () -> result.set(validator.validate(instance)), "tp015-1mb-stack", ONE_MEGABYTE_STACK);
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((failed, throwable) -> uncaught.set(throwable));
        assertTimeoutPreemptively(ROW_TIMEOUT, () -> {
            thread.start();
            thread.join();
        });
        return new SmallStackOutcome(result.get(), uncaught.get());
    }

    /**
     * What a validation on the 1 MB-stack thread ended with.
     *
     * @param result   the validation result, or {@code null} when the thread ended abruptly
     * @param uncaught the thread's uncaught throwable, or {@code null} when it completed normally
     */
    private record SmallStackOutcome(OutputUnit result, Throwable uncaught) {

        /**
         * Returns the verdict.
         *
         * @return the result's verdict, or {@code null} when the thread ended abruptly
         */
        Boolean valid() {
            return result == null ? null : result.getValid();
        }
    }

    /**
     * One recorded format-validator call.
     *
     * @param format   the format name the engine passed
     * @param instance the instance the engine passed
     */
    private record Call(String format, Object instance) implements Comparable<Call> {

        @Override
        public int compareTo(Call other) {
            return format.compareTo(other.format);
        }

        @Override
        public String toString() {
            String text = String.valueOf(instance);
            return format + "=" + (text.length() > 40 ? text.substring(0, 40) + "...(" + text.length() + ")" : text);
        }
    }

    /**
     * A test {@link JsonFormatValidator} that records every call and throws its {@link #sentinel} for
     * the format {@code probe}; every other call passes.
     */
    private static final class RecordingFormatValidator implements JsonFormatValidator {

        /** The exception thrown for the format {@code probe}. */
        final RuntimeException sentinel = new RuntimeException("TP-015 probe sentinel");

        private final List<Call> calls = new CopyOnWriteArrayList<>();

        @Override
        public String validateFormat(String instanceType, String format, Object instance) {
            calls.add(new Call(format, instance));
            if (PROBE.equals(format)) {
                throw sentinel;
            }
            return null;
        }

        /**
         * Returns the recorded calls that named a format, in call order.
         *
         * @return the calls with a non-null format
         */
        List<Call> formatCalls() {
            return calls.stream().filter(call -> call.format() != null).toList();
        }
    }

    // --- Rewrite and preservation helpers (TP-002 to TP-004) ---

    /**
     * Builds a TP-002 row whose shape gains entries.
     *
     * @param label     the row label
     * @param input     the input schema, as a JSON literal
     * @param expected  the expected rewritten schema, as a JSON literal
     * @param positions the number of pattern and bounded-format positions
     * @param instances TP-004's in-bound instances of the shape
     * @return the row
     */
    private static Named<Shape> shape(
            String label, String input, String expected, int positions, Instance... instances) {
        return Named.of(label, new Shape(schema(input), schema(expected), positions, List.of(instances)));
    }

    /**
     * Builds a TP-002 row whose shape the rewrite leaves unchanged.
     *
     * @param label     the row label
     * @param input     the schema, as a JSON literal
     * @param instances TP-004's in-bound instances of the shape
     * @return the row
     */
    private static Named<Shape> unchanged(String label, String input, Instance... instances) {
        return shape(label, input, input, 0, instances);
    }

    /**
     * Builds a TP-004-only shape, which TP-002 does not rewrite-check.
     *
     * @param label     the row label
     * @param input     the schema, as a JSON literal
     * @param instances the in-bound instances
     * @return the shape
     */
    private static Named<Shape> preserved(String label, String input, Instance... instances) {
        return Named.of(label, new Shape(schema(input), null, -1, List.of(instances)));
    }

    /**
     * Builds a TP-004-only {@code {"type":"string","format":F}} shape.
     *
     * @param format     the format
     * @param conforming a conforming instance, as a JSON literal
     * @param violating  a violating instance, as a JSON literal
     * @return the shape
     */
    private static Named<Shape> formatShape(String format, String conforming, String violating) {
        return preserved(
                "format " + format + ": still the engine's own check",
                "{'type':'string','format':'" + format + "'}",
                ok(conforming),
                bad(violating));
    }

    /**
     * An instance the unguarded compilation accepts.
     *
     * @param json the instance, as a JSON literal
     * @return the instance
     */
    private static Instance ok(String json) {
        return new Instance(Verdict.CONFORMING, value(json));
    }

    /**
     * An instance the unguarded compilation rejects.
     *
     * @param json the instance, as a JSON literal
     * @return the instance
     */
    private static Instance bad(String json) {
        return new Instance(Verdict.VIOLATING, value(json));
    }

    /**
     * An instance the baseline engine cannot judge: its validation throws.
     *
     * @param json the instance, as a JSON literal
     * @return the instance
     */
    private static Instance unjudgeable(String json) {
        return new Instance(Verdict.UNJUDGEABLE, value(json));
    }

    /**
     * Parses a schema literal in which {@code '} stands for {@code "}, {@code <B>} for the bound entry and
     * {@code <P>} for the {@code propertyNames} entry.
     *
     * @param text the literal
     * @return the schema
     */
    private static JsonObject schema(String text) {
        return new JsonObject(
                text.replace('\'', '"').replace("<B>", BOUND_ENTRY_JSON).replace("<P>", PROPERTY_NAMES_ENTRY_JSON));
    }

    /**
     * Parses an instance literal in which {@code '} stands for {@code "}.
     *
     * @param text the literal
     * @return the instance: a JSON object, array, string, number, boolean, or {@code null}
     */
    private static Object value(String text) {
        return Json.decodeValue(text.replace('\'', '"'));
    }

    /**
     * Counts the bound entries a document carries: {@code allOf} members equal to the bound entry or to the
     * {@code propertyNames} entry, at any depth.
     *
     * @param node the document or a node of it
     * @return the number of entries
     */
    private static int boundEntries(Object node) {
        int entries = 0;
        if (node instanceof JsonObject object) {
            JsonObject boundEntry = new JsonObject(BOUND_ENTRY_JSON);
            JsonObject propertyNamesEntry = new JsonObject(PROPERTY_NAMES_ENTRY_JSON);
            if (object.getValue("allOf") instanceof JsonArray allOf) {
                for (Object member : allOf) {
                    if (boundEntry.equals(member) || propertyNamesEntry.equals(member)) {
                        entries++;
                    }
                }
            }
            for (String field : object.fieldNames()) {
                entries += boundEntries(object.getValue(field));
            }
        } else if (node instanceof JsonArray array) {
            for (Object member : array) {
                entries += boundEntries(member);
            }
        }
        return entries;
    }

    /**
     * Compiles the pre-change way, {@code Validator.create(JsonSchema.of(copy), options)} over an untouched
     * copy, with the gate's options and the engine's default format validator.
     *
     * @param schema the schema
     * @return the unguarded validator
     */
    private static Validator unguarded(JsonObject schema) {
        return Validator.create(JsonSchema.of(schema.copy()), GATE_OPTIONS);
    }

    /**
     * Runs one validation and records its verdict and errors, or the class of the exception it threw.
     *
     * @param validation the validation
     * @return the judgement
     */
    private static Judgement judge(Supplier<OutputUnit> validation) {
        try {
            OutputUnit result = validation.get();
            List<ErrorLine> errors = result.getErrors() == null
                    ? List.of()
                    : result.getErrors().stream()
                            .map(error -> new ErrorLine(
                                    error.getKeywordLocation(), error.getInstanceLocation(), error.getError()))
                            .toList();
            return new Judgement(result.getValid(), errors, null);
        } catch (RuntimeException thrown) {
            return new Judgement(null, List.of(), thrown.getClass());
        }
    }

    /**
     * Asserts TP-004's Given: the unguarded compilation judges the instance as the row declares.
     *
     * @param instance  the instance and its declared verdict
     * @param unguarded the unguarded judgement
     */
    private static void assertGivenVerdict(Instance instance, Judgement unguarded) {
        switch (instance.verdict()) {
            case CONFORMING ->
                assertEquals(
                        Boolean.TRUE, unguarded.valid(), "the Given: the unguarded compilation accepts the instance");
            case VIOLATING ->
                assertEquals(
                        Boolean.FALSE, unguarded.valid(), "the Given: the unguarded compilation rejects the instance");
            case UNJUDGEABLE ->
                assertNotNull(
                        unguarded.thrown(), "the Given: the baseline engine cannot judge any instance of the shape");
        }
    }

    /**
     * Asserts two judgements are equal, reporting the first differing error.
     *
     * @param unguarded the pre-change judgement
     * @param guarded   the guarded compilation's judgement
     */
    private static void assertSameJudgement(Judgement unguarded, Judgement guarded) {
        assertEquals(
                unguarded.thrown(),
                guarded.thrown(),
                "the guarded compilation must throw exactly when, and what, the unguarded one does");
        assertEquals(unguarded.valid(), guarded.valid(), "the verdict must be unchanged");
        int errors = Math.max(unguarded.errors().size(), guarded.errors().size());
        for (int index = 0; index < errors; index++) {
            ErrorLine expected =
                    index < unguarded.errors().size() ? unguarded.errors().get(index) : null;
            ErrorLine actual =
                    index < guarded.errors().size() ? guarded.errors().get(index) : null;
            if (!Objects.equals(expected, actual)) {
                fail("error " + index + " differs: unguarded " + expected + ", guarded " + actual);
            }
        }
    }

    /**
     * Asserts TP-004's Given that every string and key of the instance is at most 4,096 code units and that
     * they total under 262,144.
     *
     * @param instance the instance
     */
    private static void assertInBound(Object instance) {
        int[] longestAndTotal = new int[2];
        measure(instance, longestAndTotal);
        assertAll(
                "the Given: the instance is within both limits",
                () -> assertTrue(longestAndTotal[0] <= MAX_CHARS, "every string and key is at most 4,096"),
                () -> assertTrue(longestAndTotal[1] < MAX_TOTAL_CHARS, "the strings and keys total under 262,144"));
    }

    /**
     * Accumulates the longest string or key and the total length of all of them.
     *
     * @param value           the value
     * @param longestAndTotal the accumulator: longest at 0, total at 1
     */
    private static void measure(Object value, int[] longestAndTotal) {
        if (value instanceof String text) {
            longestAndTotal[0] = Math.max(longestAndTotal[0], text.length());
            longestAndTotal[1] += text.length();
        } else if (value instanceof JsonObject object) {
            for (String field : object.fieldNames()) {
                measure(field, longestAndTotal);
                measure(object.getValue(field), longestAndTotal);
            }
        } else if (value instanceof JsonArray array) {
            for (Object member : array) {
                measure(member, longestAndTotal);
            }
        }
    }

    /**
     * Loads every document of the pinned {@code schema-corpus}: each fixture under each profile directory.
     *
     * @return the documents
     */
    private static Stream<CorpusDocument> corpusDocuments() {
        return Stream.of("legacy", "system", "vertique").flatMap(profile -> SchemaCorpus.FIXTURES.stream()
                .map(fixture -> new CorpusDocument(
                        profile + "/" + fixture.name(), fixture.name(), corpusDocument(profile, fixture))));
    }

    /**
     * Reads one corpus document from the test classpath.
     *
     * @param profile the profile directory
     * @param fixture the fixture
     * @return the document
     */
    private static JsonObject corpusDocument(String profile, CorpusFixture fixture) {
        String resource = "/schema-corpus/" + profile + "/" + fixture.fileName();
        try (InputStream in = PatternInputGuardTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing corpus document " + resource);
            }
            return new JsonObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /**
     * TP-004's conforming and violating instances of each corpus fixture, shared by its three profile
     * documents.
     *
     * @param fixtureName the fixture name
     * @return the instances
     */
    private static List<Instance> corpusInstances(String fixtureName) {
        return switch (fixtureName) {
            case "RequiredPropertyDto" -> List.of(ok("{'name':'n'}"), bad("{}"));
            case "ConstrainedStringDto" ->
                List.of(ok("{'category':'ABC','code':'xy'}"), bad("{'category':'abc','code':'x'}"));
            case "NestedObjectDto" -> List.of(ok("{'address':{'city':'Oulu'}}"), bad("{'address':{'city':''}}"));
            case "DefaultSentinelDto" -> List.of(ok("{'label':'x'}"), bad("{'label':5}"));
            case "CollectionItemDto" -> List.of(ok("{'sku':'A1','quantity':2}"), bad("{'quantity':'two'}"));
            case "CollectionItemDtoList" -> List.of(ok("[{'sku':'A1'}]"), bad("[{'quantity':1},'x']"));
            case "RootDecimalDto" -> List.of(ok("{'amount':1.5}"), bad("{'amount':'x'}"));
            case "NestedDecimalDto" -> List.of(ok("{'nested':{'amount':1.25}}"), bad("{'nested':{'amount':'1.25'}}"));
            case "DecimalListDto" -> List.of(ok("{'amounts':[1.5,2]}"), bad("{'amounts':[1,'two']}"));
            case "DecimalMapKeyDto" -> List.of(ok("{'labels':{'1.5':'a'}}"), bad("{'labels':['1.5']}"));
            case "OptionalPropertyDto" -> List.of(ok("{'note':null}"), bad("{'note':5}"));
            case "InstantPropertyDto" ->
                List.of(ok("{'occurredAt':'2026-09-27T12:00:00Z'}"), bad("{'occurredAt':'yesterday'}"));
            case "LocalDatePropertyDto" -> List.of(ok("{'due':'2026-09-27'}"), bad("{'due':'2026-13-45'}"));
            case "EnumDefaultValueDto" -> List.of(ok("{'status':'ACTIVE'}"), bad("{'status':'GONE'}"));
            case "PatternNamedPropertyDto" -> List.of(ok("{'pattern':'x'}"), bad("{'pattern':7}"));
            case "PrivateDatePropertyDto" -> List.of(ok("{'due':'2026-09-27'}"), bad("{'due':'yesterday'}"));
            case "LombokBuilderDto" -> List.of(ok("{'name':'n','quantity':1}"), bad("{'name':1,'quantity':1.5}"));
            case "GetterOnlyListDto" -> List.of(ok("{'tags':['a','b']}"), bad("{'tags':['a',2]}"));
            case "GetterOnlyMapDto" -> List.of(ok("{'labels':{'k':'v'}}"), bad("{'labels':['k']}"));
            case "NestedPrivateDateDto" ->
                List.of(ok("{'detail':{'due':'2026-09-27'}}"), bad("{'detail':{'due':'yesterday'}}"));
            default -> throw new IllegalStateException("no TP-004 instances for corpus fixture " + fixtureName);
        };
    }

    /**
     * Builds a TP-003 operation: a body and one parameter, wrapped in {@link OperationSchemas} built through
     * its public builder, which holds the very objects passed in.
     *
     * @param body        the body schema
     * @param location    the parameter's location
     * @param name        the parameter's name
     * @param parameter   the parameter schema
     * @param patternFree whether both schemas are free of pattern positions and bounded formats
     * @return the operation and its schemas
     */
    private static SharedSchemas shared(
            JsonObject body, ParamLocation location, String name, JsonObject parameter, boolean patternFree) {
        OperationSchemas schemas = OperationSchemas.builder()
                .bodySchema(body)
                .parameterSchema(location, name, parameter)
                .build();
        JaxRsOperationDescriptor operation =
                operation(List.of(param(name, location, String.class, null)), true, List.of());
        return new SharedSchemas(operation, schemas, location, name, patternFree);
    }

    // --- Gate helpers (TP-005 to TP-007, TP-014) ---

    /**
     * Returns the default configuration: the default limits and {@code aggregate} mode.
     *
     * @return the configuration
     */
    private static JaxRsConfig defaults() {
        return JaxRsConfig.builder().build();
    }

    /**
     * Builds the gate for an operation through the strategy's public {@code gateFor}.
     *
     * @param config    the configuration
     * @param operation the operation
     * @param schemas   its schemas
     * @param verifiers the bound file-content verifiers
     * @return the gate handler
     */
    private static Handler<RoutingContext> gate(
            JaxRsConfig config,
            JaxRsOperationDescriptor operation,
            OperationSchemas schemas,
            Set<FileContentVerifier> verifiers) {
        return new WebValidationStrategy(config, ConversionContexts.defaultResolver(), verifiers)
                .gateFor(operation, schemas)
                .orElseThrow();
    }

    /**
     * Builds the gate for a body-only operation.
     *
     * @param config     the configuration
     * @param bodySchema the body schema
     * @return the gate handler
     */
    private static Handler<RoutingContext> bodyGate(JaxRsConfig config, JsonObject bodySchema) {
        return gate(
                config,
                operation(List.of(), true, List.of()),
                OperationSchemas.builder().bodySchema(bodySchema).build(),
                Set.of());
    }

    /**
     * Builds an operation descriptor.
     *
     * @param parameters the declared parameters, in declaration order
     * @param body       whether the operation declares a body
     * @param fileParts  the declared file parts
     * @return the descriptor
     */
    private static JaxRsOperationDescriptor operation(
            List<ParamDescriptor> parameters, boolean body, List<FilePartDescriptor> fileParts) {
        StubDescriptors.Builder builder = StubDescriptors.builder()
                .operationId(OPERATION_ID)
                .httpMethod("POST")
                .routeTemplate("/bound")
                .parameters(parameters)
                .fileParts(fileParts);
        if (body) {
            builder.body(new BodyDescriptor(Object.class, null, List.of()));
        }
        return builder.build();
    }

    /**
     * Builds a parameter descriptor.
     *
     * @param name          the name
     * @param location      the location
     * @param type          the declared type
     * @param componentType the element type of a collection parameter, or {@code null}
     * @return the descriptor
     */
    private static ParamDescriptor param(String name, ParamLocation location, Class<?> type, Class<?> componentType) {
        return new ParamDescriptor(name, location, type, componentType, null, null, List.of());
    }

    /**
     * Returns {@code {"type":"string","pattern":CATASTROPHIC}}.
     *
     * @return the schema
     */
    private static JsonObject catastrophicString() {
        return new JsonObject().put("type", "string").put("pattern", CATASTROPHIC);
    }

    /**
     * Returns {@code {"type":"string","format":F}}.
     *
     * @param format the format
     * @return the schema
     */
    private static JsonObject formatString(String format) {
        return new JsonObject().put("type", "string").put("format", format);
    }

    /**
     * Returns an array schema whose items are strings at the linear pattern {@link #ALL_A}.
     *
     * @return the schema
     */
    private static JsonObject patternedStrings() {
        return new JsonObject()
                .put("type", "array")
                .put("items", new JsonObject().put("type", "string").put("pattern", ALL_A));
    }

    /**
     * Builds an array of {@code count} copies of {@code value}.
     *
     * @param count the count
     * @param value the string
     * @return the array
     */
    private static JsonArray strings(int count, String value) {
        JsonArray array = new JsonArray();
        for (int index = 0; index < count; index++) {
            array.add(value);
        }
        return array;
    }

    /**
     * TP-005's stop-rule operation: query parameters {@code z}, {@code a}, and {@code b} in that order, a
     * body, and a file part {@code avatar} allowing only {@code image/png}.
     *
     * @return the descriptor
     */
    private static JaxRsOperationDescriptor stopRuleOperation() {
        return operation(
                List.of(
                        param("z", ParamLocation.QUERY, String.class, null),
                        param("a", ParamLocation.QUERY, String.class, null),
                        param("b", ParamLocation.QUERY, String.class, null)),
                true,
                List.of(new FilePartDescriptor("avatar", List.of("image/png"), -1)));
    }

    /**
     * TP-005's stop-rule schemas: {@code z} and {@code b} at {@code minLength: 5}, {@code a} at {@link
     * #CATASTROPHIC}, and a body requiring {@code name}.
     *
     * @return the schemas
     */
    private static OperationSchemas stopRuleSchemas() {
        return OperationSchemas.builder()
                .parameterSchema(ParamLocation.QUERY, "z", schema("{'type':'string','minLength':5}"))
                .parameterSchema(ParamLocation.QUERY, "a", catastrophicString())
                .parameterSchema(ParamLocation.QUERY, "b", schema("{'type':'string','minLength':5}"))
                .bodySchema(schema("{'type':'object','required':['name']}"))
                .build();
    }

    /**
     * Mocks a physical upload.
     *
     * @param name        the part name
     * @param contentType the client-declared content type
     * @return the upload
     */
    private static FileUpload upload(String name, String contentType) {
        FileUpload upload = mock(FileUpload.class);
        when(upload.name()).thenReturn(name);
        when(upload.fileName()).thenReturn(name + ".bin");
        when(upload.uploadedFileName()).thenReturn("target/" + name + ".upload");
        when(upload.contentType()).thenReturn(contentType);
        when(upload.size()).thenReturn(8L);
        return upload;
    }

    /**
     * Handles one request inside a preemptive timeout and reads how the gate answered.
     *
     * @param gate    the gate
     * @param ctx     the request's routing context
     * @param timeout the timeout; only an evaluation on unbounded input can exhaust it
     * @return the outcome
     */
    private static GateOutcome handle(Handler<RoutingContext> gate, RoutingContext ctx, Duration timeout) {
        assertTimeoutPreemptively(
                timeout,
                () -> gate.handle(ctx),
                "the gate must answer within the timeout; only an evaluation on unbounded input exhausts it");
        return outcomeOf(ctx);
    }

    /**
     * Reads how the gate answered a request: its {@code next()} calls and its {@code fail(...)} failures.
     *
     * @param ctx the request's routing context
     * @return the outcome
     */
    private static GateOutcome outcomeOf(RoutingContext ctx) {
        int nextCalls = 0;
        List<Throwable> failures = new ArrayList<>();
        for (Invocation invocation : mockingDetails(ctx).getInvocations()) {
            String method = invocation.getMethod().getName();
            if ("next".equals(method)) {
                nextCalls++;
            } else if ("fail".equals(method)) {
                Throwable failure = null;
                for (Object argument : invocation.getArguments()) {
                    if (argument instanceof Throwable thrown) {
                        failure = thrown;
                    }
                }
                failures.add(
                        failure != null
                                ? failure
                                : new AssertionError("fail(" + invocation.getArguments()[0] + ")"));
            }
        }
        return new GateOutcome(nextCalls, List.copyOf(failures));
    }

    /**
     * Asserts the C-BOUND per-string detail.
     *
     * @param detail   the detail
     * @param path     the expected path: {@code ""} for the body, else the parameter name
     * @param location the expected location token
     * @param maxChars the configured per-string limit
     */
    private static void assertPerStringDetail(
            ValidationErrorDetail detail, String path, String location, int maxChars) {
        assertBoundDetail(
                detail,
                path,
                location,
                PER_STRING_TYPE,
                "exceeds the maximum length of " + maxChars + " characters for pattern validation",
                "maxChars",
                maxChars);
    }

    /**
     * Asserts the C-BOUND per-request detail.
     *
     * @param detail        the detail
     * @param path          the expected path: {@code ""} for the body, else the parameter name
     * @param location      the expected location token
     * @param maxTotalChars the configured per-request limit
     */
    private static void assertPerRequestDetail(
            ValidationErrorDetail detail, String path, String location, int maxTotalChars) {
        assertBoundDetail(
                detail,
                path,
                location,
                PER_REQUEST_TYPE,
                "exceeds the maximum total length of " + maxTotalChars + " characters for pattern validation",
                "maxTotalChars",
                maxTotalChars);
    }

    /**
     * Asserts every field of a bound detail; {@code args} is compared as JSON numbers.
     *
     * @param detail  the detail
     * @param path    the expected path
     * @param location the expected location token
     * @param type    the expected type
     * @param text    the expected detail text
     * @param argName the expected single {@code args} key
     * @param limit   the expected {@code args} value
     */
    private static void assertBoundDetail(
            ValidationErrorDetail detail,
            String path,
            String location,
            String type,
            String text,
            String argName,
            int limit) {
        assertAll(
                "the " + type + " detail",
                () -> assertEquals(path, abbreviate(detail.path()), "path"),
                () -> assertEquals(location, detail.location(), "location"),
                () -> assertEquals(type, detail.type(), "type"),
                () -> assertEquals(text, abbreviate(detail.detail()), "detail"),
                () -> assertNotNull(detail.args(), "args"),
                () -> assertEquals(Set.of(argName), detail.args().keySet(), "args names only the limit"),
                () -> assertEquals(
                        (long) limit,
                        assertInstanceOf(Number.class, detail.args().get(argName))
                                .longValue(),
                        "args carries the configured limit"));
    }

    /**
     * Asserts no detail contains any of the fragments: the value, the key, or the pattern.
     *
     * @param details   the details
     * @param fragments the forbidden fragments
     */
    private static void assertValueFree(List<ValidationErrorDetail> details, String... fragments) {
        for (ValidationErrorDetail detail : details) {
            String text = String.valueOf(detail);
            for (String fragment : fragments) {
                assertFalse(
                        text.contains(fragment), () -> "no detail may contain '" + fragment + "': " + abbreviate(text));
            }
        }
    }

    /**
     * Asserts the gate reported neither bound detail.
     *
     * @param outcome the outcome
     */
    private static void assertNoBoundDetail(GateOutcome outcome) {
        List<String> types = detailTypes(outcome);
        assertFalse(
                types.contains(PER_STRING_TYPE) || types.contains(PER_REQUEST_TYPE),
                () -> "no patternInputLength or patternInputTotalLength detail may be reported: " + types);
    }

    /**
     * Asserts the gate answered with the unguarded compilation's verdict and, for a rejection, one detail per
     * concrete violation the unguarded compilation reports, keyword by keyword in order (a detail's path is
     * the gate's own rendering of the location, so it is not compared).
     *
     * @param outcome  the gate's outcome
     * @param schema   the schema the gate compiled
     * @param instance the instance it validated
     */
    private static void assertMatchesUnguarded(GateOutcome outcome, JsonObject schema, Object instance) {
        OutputUnit expected = unguarded(schema).validate(instance);
        if (Boolean.TRUE.equals(expected.getValid())) {
            outcome.assertAccepted("the unguarded compilation accepts the instance");
            return;
        }
        assertEquals(
                concreteKeywords(expected),
                outcome.rejection().stream().map(ValidationErrorDetail::type).toList(),
                "the gate must report exactly the unguarded compilation's violations");
    }

    /**
     * Maps a result's errors to the detail types the gate renders for them: the failed keyword, skipping
     * structural wrappers, {@code null} for an error without a keyword, and one {@code null} (the value-free
     * detail) when every error was structural.
     *
     * @param result the unguarded result
     * @return the keywords, in order
     */
    private static List<String> concreteKeywords(OutputUnit result) {
        List<OutputUnit> errors = result.getErrors() == null ? List.of() : result.getErrors();
        List<String> keywords = new ArrayList<>();
        for (OutputUnit error : errors) {
            String keyword = SchemaErrorKeywords.extractKeyword(error.getKeywordLocation());
            if (keyword == null || !SchemaErrorKeywords.STRUCTURAL_KEYWORDS.contains(keyword)) {
                keywords.add(keyword);
            }
        }
        return keywords.isEmpty() ? Collections.singletonList(null) : keywords;
    }

    /**
     * Returns every detail of every validation failure the gate reported.
     *
     * @param outcome the outcome
     * @return the details
     */
    private static List<ValidationErrorDetail> allDetails(GateOutcome outcome) {
        return outcome.failures().stream()
                .filter(RestValidationException.class::isInstance)
                .flatMap(failure -> ((RestValidationException) failure).errors().stream())
                .toList();
    }

    /**
     * Returns the types of every detail the gate reported.
     *
     * @param outcome the outcome
     * @return the types
     */
    private static List<String> detailTypes(GateOutcome outcome) {
        return allDetails(outcome).stream().map(ValidationErrorDetail::type).toList();
    }

    /**
     * Summarizes details for a failure message: type and abbreviated path.
     *
     * @param details the details
     * @return the summary
     */
    private static List<String> types(List<ValidationErrorDetail> details) {
        return details.stream()
                .map(detail -> detail.type() + "@" + abbreviate(detail.path()))
                .toList();
    }

    /**
     * Shortens text over 80 characters, so a failure message never carries a long value.
     *
     * @param text the text, possibly {@code null}
     * @return the text, or its first 40 characters and its length
     */
    private static String abbreviate(String text) {
        return text == null || text.length() <= 80 ? text : text.substring(0, 40) + "...(" + text.length() + ")";
    }

    /**
     * Mocks a request body over the JSON encoding of {@code body}, parsed as Vert.x parses it.
     *
     * @param raw the encoded body
     * @return the request body
     */
    private static RequestBody requestBody(Buffer raw) {
        RequestBody body = mock(RequestBody.class);
        when(body.buffer()).thenReturn(raw);
        when(body.asJsonObject())
                .thenAnswer(invocation -> Json.decodeValue(raw) instanceof JsonObject object ? object : null);
        when(body.asJsonArray())
                .thenAnswer(invocation -> Json.decodeValue(raw) instanceof JsonArray array ? array : null);
        return body;
    }

    // --- Test types ---

    /**
     * One TP-002 shape.
     *
     * @param input     the input schema
     * @param expected  the expected rewritten schema, or {@code null} for a TP-004-only shape
     * @param positions the number of pattern and bounded-format positions, or -1 for a TP-004-only shape
     * @param instances TP-004's in-bound instances of the shape
     */
    private record Shape(JsonObject input, JsonObject expected, int positions, List<Instance> instances) {}

    /** How the unguarded compilation judges an instance. */
    private enum Verdict {
        /** Valid. */
        CONFORMING,
        /** Invalid. */
        VIOLATING,
        /** The validation throws. */
        UNJUDGEABLE
    }

    /**
     * One in-bound instance.
     *
     * @param verdict the unguarded verdict the row declares
     * @param value   the instance
     */
    private record Instance(Verdict verdict, Object value) {

        @Override
        public String toString() {
            return verdict.name().toLowerCase(Locale.ROOT) + " " + Json.encode(value);
        }
    }

    /**
     * One TP-004 row.
     *
     * @param schema   the schema
     * @param instance the instance
     */
    private record PreservationCase(JsonObject schema, Instance instance) {}

    /**
     * One validation's result as TP-004 compares it.
     *
     * @param valid  the verdict, or {@code null} when the validation threw
     * @param errors the errors, in order
     * @param thrown the class of the exception thrown, or {@code null}
     */
    private record Judgement(Boolean valid, List<ErrorLine> errors, Class<?> thrown) {}

    /**
     * One reported error, as TP-004 compares it.
     *
     * @param keywordLocation  the keyword location
     * @param instanceLocation the instance location
     * @param error            the message
     */
    private record ErrorLine(String keywordLocation, String instanceLocation, String error) {}

    /**
     * One pinned corpus document.
     *
     * @param label       the profile and fixture name
     * @param fixtureName the fixture name
     * @param schema      the document
     */
    private record CorpusDocument(String label, String fixtureName, JsonObject schema) {

        /**
         * Returns whether the document has no pattern position and no bounded format: no string-valued
         * {@code pattern} member, no {@code patternProperties}, and none of the three bounded format names.
         *
         * @return whether the document is pattern-free
         */
        boolean patternFree() {
            String text = schema.encode();
            return !text.contains("\"pattern\":\"")
                    && !text.contains("\"patternProperties\"")
                    && !text.contains("\"idn-hostname\"")
                    && !text.contains("\"idn-email\"")
                    && !text.contains("\"regex\"");
        }
    }

    /**
     * One TP-003 operation.
     *
     * @param operation   the descriptor
     * @param schemas     the schemas, holding the very objects checked
     * @param location    the parameter's location
     * @param name        the parameter's name
     * @param patternFree whether both schemas are pattern-free
     */
    private record SharedSchemas(
            JaxRsOperationDescriptor operation,
            OperationSchemas schemas,
            ParamLocation location,
            String name,
            boolean patternFree) {

        /**
         * Returns the schema objects {@link OperationSchemas} holds, by location.
         *
         * @return the held objects
         */
        Map<String, JsonObject> held() {
            Map<String, JsonObject> held = new LinkedHashMap<>();
            held.put("the body schema", schemas.bodySchema().orElseThrow());
            held.put(
                    "the " + location.name().toLowerCase(Locale.ROOT) + " parameter " + name + "'s schema",
                    schemas.parameterSchema(location, name).orElseThrow());
            return held;
        }
    }

    /**
     * How the gate answered one request.
     *
     * @param nextCalls how often it called {@code next()}
     * @param failures  what it passed to {@code fail(...)}
     */
    private record GateOutcome(int nextCalls, List<Throwable> failures) {

        /**
         * Asserts the request reached the next handler once and did not fail.
         *
         * @param what the request, for the message
         */
        void assertAccepted(String what) {
            assertAll(
                    what + " must be accepted",
                    () -> assertEquals(
                            List.of(),
                            failures.stream()
                                    .map(PatternInputGuardTest::describe)
                                    .toList(),
                            "no failure"),
                    () -> assertEquals(1, nextCalls, "one next() call"));
        }

        /**
         * Asserts the request failed once, with a {@link RestValidationException}, never reaching the next
         * handler, and returns its details.
         *
         * @return the details
         */
        List<ValidationErrorDetail> rejection() {
            assertEquals(0, nextCalls, "a rejected request must never reach the next handler");
            assertEquals(
                    1,
                    failures.size(),
                    () -> "the request must fail exactly once: "
                            + failures.stream()
                                    .map(PatternInputGuardTest::describe)
                                    .toList());
            return assertInstanceOf(
                            RestValidationException.class,
                            failures.get(0),
                            () -> "the failure must be a 400 RestValidationException: " + describe(failures.get(0)))
                    .errors();
        }
    }

    /**
     * Describes a failure for a message: a validation failure by its detail types, anything else by class.
     *
     * @param failure the failure
     * @return the description
     */
    private static String describe(Throwable failure) {
        return failure instanceof RestValidationException validation
                ? "RestValidationException" + types(validation.errors())
                : failure.getClass().getName();
    }

    /**
     * A mocked request: parameters at every location, a JSON body, and uploads.
     */
    private static final class GateRequest {

        private final Map<String, String> path = new HashMap<>();
        private final MultiMap query = MultiMap.caseInsensitiveMultiMap();
        private final MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        private final MultiMap form = MultiMap.caseInsensitiveMultiMap();
        private final Set<Cookie> cookies = new HashSet<>();
        private final List<FileUpload> uploads = new ArrayList<>();
        private Object body;

        /**
         * Adds a parameter value.
         *
         * @param location the location
         * @param name     the name
         * @param value    the raw value
         * @return this request
         */
        GateRequest parameter(ParamLocation location, String name, String value) {
            switch (location) {
                case PATH -> path.put(name, value);
                case QUERY -> query.add(name, value);
                case HEADER -> headers.add(name, value);
                case COOKIE -> cookies.add(Cookie.cookie(name, value));
                case FORM -> form.add(name, value);
            }
            return this;
        }

        /**
         * Sets the JSON body.
         *
         * @param json the body: a JSON object, array, string, or number
         * @return this request
         */
        GateRequest body(Object json) {
            this.body = json;
            return this;
        }

        /**
         * Adds a physical upload.
         *
         * @param upload the upload
         * @return this request
         */
        GateRequest upload(FileUpload upload) {
            uploads.add(upload);
            return this;
        }

        /**
         * Builds the mocked routing context, with a JSON content type and a data map backing {@code get} and
         * {@code put}.
         *
         * @return the routing context
         */
        RoutingContext context() {
            RoutingContext ctx = mock(RoutingContext.class);
            HttpServerRequest request = mock(HttpServerRequest.class);
            Map<String, Object> data = new HashMap<>();
            RequestBody requestBody = body == null ? null : requestBody(Json.encodeToBuffer(body));
            when(ctx.request()).thenReturn(request);
            when(ctx.pathParams()).thenReturn(path);
            when(ctx.queryParams()).thenReturn(query);
            when(ctx.fileUploads()).thenReturn(uploads);
            when(ctx.body()).thenReturn(requestBody);
            when(request.headers()).thenReturn(headers);
            when(request.cookies()).thenReturn(cookies);
            when(request.formAttributes()).thenReturn(form);
            when(request.getHeader("Content-Type")).thenReturn("application/json");
            when(ctx.get(anyString())).thenAnswer(invocation -> data.get(invocation.getArgument(0, String.class)));
            when(ctx.put(anyString(), any())).thenAnswer(invocation -> {
                data.put(invocation.getArgument(0, String.class), invocation.getArgument(1));
                return ctx;
            });
            return ctx;
        }
    }

    /** A file-content verifier that counts its invocations and accepts every upload. */
    private static final class CountingVerifier implements FileContentVerifier {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Future<FileVerificationResult> verify(FileUpload part) {
            calls.incrementAndGet();
            return Future.succeededFuture(FileVerificationResult.accepted());
        }

        /**
         * Returns how often {@link #verify(FileUpload)} ran.
         *
         * @return the count
         */
        int calls() {
            return calls.get();
        }
    }
}
