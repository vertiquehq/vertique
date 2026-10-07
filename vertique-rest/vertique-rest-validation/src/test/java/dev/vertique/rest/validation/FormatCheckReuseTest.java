// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.sun.management.ThreadMXBean;
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
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Proofs for the {@code web-validation} gate's reused format checks (rest-022 T021, FR-020): TP-001,
 * TP-002, TP-003, TP-006, and TP-007. TP-004 ({@code url}'s filtering corpus) and TP-005 (the HTTP
 * integration test) live in their own classes.
 *
 * <p>"The gate's compiled validator" (TP-002, TP-003) is {@code new PatternInputGuard(4096,
 * 262_144).compile(schema, GATE_OPTIONS)}; "the engine's verdict" is {@code
 * Validator.create(JsonSchema.of(schema.copy()), GATE_OPTIONS)}, the unguarded compilation with no
 * custom format validator (E4). "C-FORMAT's verdict" for a value is computed directly with {@link URI}
 * and the single-pass and scanner rules C-FORMAT's frozen table states ({@link #cFormatVerdict(String,
 * String)} and its per-format helpers below), never through the new production classes this task adds
 * (E5): {@code UriFormatChecks}, {@code PointerFormatChecks}, {@code UrlFormatCheck}, and {@code
 * UriTemplateSyntax} do not exist yet, and this proof never references them.
 *
 * <p>At this task's baseline (T004's head on the FR-020 branch) the gate-private copy still carries the
 * seven formats' standard names, so vertx-json-schema's own regular expressions decide them: the
 * overflow and timeout reds below are that engine deciding a value its own checks cannot handle.
 */
class FormatCheckReuseTest {

    /**
     * The gate's compile options, copied literally from {@code WebValidationStrategy.SCHEMA_OPTIONS}
     * (E4), so every validator here characterizes the engine and the gate exactly as the gate
     * configures them.
     */
    private static final JsonSchemaOptions GATE_OPTIONS = new JsonSchemaOptions()
            .setDraft(Draft.DRAFT202012)
            .setBaseUri("https://vertique.local/")
            .setOutputFormat(OutputFormat.Basic);

    /** T004's default per-string limit. */
    private static final int MAX_CHARS = 4096;

    /** T004's default per-request limit. */
    private static final int MAX_TOTAL_CHARS = 262_144;

    /** The stack size TP-001, TP-006's {@code gateFor} row, and TP-007 validate on (S2-001). */
    private static final long ONE_MEGABYTE_STACK = 1 << 20;

    /** TP-001, TP-002, TP-003, and TP-006's per-call timeout. */
    private static final Duration ROW_TIMEOUT = Duration.ofSeconds(10);

    /** TP-007's per-row timeout. */
    private static final Duration BUDGET_TIMEOUT = Duration.ofSeconds(30);

    /** The operation id every gate row builds its descriptor with. */
    private static final String OPERATION_ID = "formatCheckReuse";

    /**
     * The 4,096-character value the baseline engine's {@code url} expression is still evaluating after a
     * 10-second cutoff on a 1 MB stack (step 3, E7): {@code "http://" + "a".repeat(4089)}, a single-label
     * host, invalid under C-FORMAT and the engine alike. TP-001's last row (E11): its red leaves a daemon
     * thread spinning, so this class's overflow run stays in its own Maven invocation.
     */
    private static final String SLOW_URL_4096 = "http://" + "a".repeat(4089);

    /**
     * The default-limit proofs' envelope (CX-009): the default {@code maxBodySize} of 2 MiB less the 8
     * bytes of {@code {"v":""}}.
     */
    private static final int BODY_MAX = 2_097_144;

    /** The default-limit proofs' header envelope, under the default {@code maxHeaderSize} of 8,192 bytes. */
    private static final int HEADER_MAX = 8_000;

    /**
     * TP-007's {@link #HEADER_MAX} rows go through a header parameter of this name (R-003: the contract's
     * Given routes them through a header parameter, not the body).
     */
    private static final String BUDGET_HEADER_NAME = "X-Format-Value";

    /** TP-007's elapsed-time budget (S4-001). */
    private static final long BUDGET_ELAPSED_NANOS = Duration.ofMillis(250).toNanos();

    /** TP-007's allocation budget's fixed term (S4-001): 1 MiB. */
    private static final long BUDGET_ALLOCATION_FIXED = 1L << 20;

    /** TP-007's allocation budget's per-character term (S4-001). */
    private static final long BUDGET_ALLOCATION_PER_CHAR = 16L;

    /** E20's short invalid oracle for each format: at most 16 characters, rejected by the engine too. */
    private static final Map<String, String> SHORT_INVALID = Map.ofEntries(
            Map.entry("uri", "a b"),
            Map.entry("uri-reference", "a b"),
            Map.entry("url", "http://a b"),
            Map.entry("json-pointer", "a"),
            Map.entry("relative-json-pointer", "a"),
            Map.entry("json-pointer-uri-fragment", "/a"),
            Map.entry("uri-template", "{"));

    /** The seven formats C-FORMAT reuses, in the frozen table's order. */
    private static final List<String> SEVEN_FORMATS = List.of(
            "uri",
            "uri-reference",
            "url",
            "uri-template",
            "json-pointer",
            "relative-json-pointer",
            "json-pointer-uri-fragment");

    // =========================================================================================
    // TP-001 — Valid and invalid 4,096-character values are judged without overflow, unbounded
    // =========================================================================================

    /**
     * TP-001. Given a body schema {@code {"type": "string", "format": F}} for each of the seven formats
     * and one valid and one invalid value of exactly 4,096 characters under C-FORMAT's verdict (plus a
     * small-limit array-body row per format), when the gate handler handles each request on the 1 MB-stack
     * thread under the 10-second preemptive timeout, then no request raises {@code StackOverflowError} or
     * any other throwable, every valid value is accepted, every invalid value's one detail matches its
     * format's short invalid oracle, and the small-limit gate accepts every array body unbounded and
     * uncounted.
     *
     * @param row the row
     * @throws Throwable never, but {@link Executable#execute()} declares it
     */
    @DisplayName("TP-001: reused formats judge long values without overflow, unbounded")
    @ParameterizedTest(name = "{0}")
    @MethodSource("reusedFormatsJudgeLongValuesWithoutOverflowRows")
    void reusedFormatsJudgeLongValuesWithoutOverflow(Executable row) throws Throwable {
        row.execute();
    }

    /**
     * TP-001's rows: one valid and one invalid 4,096-character row per format (url and uri-template carry
     * extra rows per the Given), then one small-limit array-body row per format. {@link #SLOW_URL_4096} is
     * last (E11).
     *
     * @return the rows
     */
    static Stream<Named<Executable>> reusedFormatsJudgeLongValuesWithoutOverflowRows() {
        List<Named<Executable>> rows = new ArrayList<>();

        String uriValid = "https://example.com/" + "a".repeat(4076);
        rows.add(longValueRow("uri", "valid, 4096 chars", uriValid, true));
        rows.add(longValueRow("uri", "invalid, space@2048", withSpaceAt(uriValid, 2048), false));

        String urefValid = "/" + "a".repeat(4095);
        rows.add(longValueRow("uri-reference", "valid, 4096 chars", urefValid, true));
        rows.add(longValueRow("uri-reference", "invalid, space@2048", withSpaceAt(urefValid, 2048), false));

        String urlValid = "https://example.com/" + "a".repeat(4076);
        rows.add(longValueRow("url", "valid, 4096 chars", urlValid, true));
        rows.add(longValueRow(
                "url", "invalid, https://127.0.0.1/..., 4096 chars", "https://127.0.0.1/" + "a".repeat(4078), false));

        String jpValid = "/a".repeat(2048);
        rows.add(longValueRow("json-pointer", "valid, 4096 chars", jpValid, true));
        rows.add(longValueRow("json-pointer", "invalid, trailing ~2", "/a".repeat(2047) + "~2", false));

        String rjpValid = "0" + "/a".repeat(2047) + "b";
        rows.add(longValueRow("relative-json-pointer", "valid, 4096 chars", rjpValid, true));
        rows.add(longValueRow("relative-json-pointer", "invalid, leading 01", "01" + "/a".repeat(2047), false));

        String jpufValid = "#" + "/a".repeat(2047) + "b";
        rows.add(longValueRow("json-pointer-uri-fragment", "valid, 4096 chars", jpufValid, true));
        rows.add(
                longValueRow("json-pointer-uri-fragment", "invalid, trailing ~", "#" + "/a".repeat(2047) + "~", false));

        String utValid1 = "https://example.com/" + "{a}".repeat(1358) + "bc";
        String utValid2 = "{" + "a".repeat(4094) + "}";
        rows.add(longValueRow("uri-template", "valid, embedded expressions, 4096 chars", utValid1, true));
        rows.add(longValueRow("uri-template", "valid, one 4094-char variable name", utValid2, true));
        rows.add(longValueRow(
                "uri-template",
                "invalid, unclosed final expression",
                "https://example.com/" + "{a}".repeat(1358) + "{b",
                false));

        // SLOW_URL_4096 must be last (E11): its baseline red leaves a daemon thread spinning.
        rows.add(longValueRow("url", "invalid, SLOW_URL_4096", SLOW_URL_4096, false));

        for (String format : SEVEN_FORMATS) {
            String valid =
                    switch (format) {
                        case "uri" -> uriValid;
                        case "uri-reference" -> urefValid;
                        case "url" -> urlValid;
                        case "json-pointer" -> jpValid;
                        case "relative-json-pointer" -> rjpValid;
                        case "json-pointer-uri-fragment" -> jpufValid;
                        case "uri-template" -> utValid1;
                        default -> throw new IllegalStateException(format);
                    };
            rows.add(smallLimitRow(format, valid));
        }
        return rows.stream();
    }

    /**
     * Builds one of TP-001's long-value rows.
     *
     * @param format the format
     * @param label  the row's label suffix
     * @param value  the 4,096-character value
     * @param valid  whether the value is valid under C-FORMAT's verdict
     * @return the row
     */
    private static Named<Executable> longValueRow(String format, String label, String value, boolean valid) {
        return Named.of("(" + format + ") " + label, () -> assertLongValueRow(format, value, valid));
    }

    /**
     * Asserts one TP-001 long-value row: the gate handler, on the 1 MB-stack thread, judges {@code value}
     * without a throwable, accepting it when {@code valid} and otherwise producing one detail matching the
     * format's short invalid oracle.
     *
     * @param format the format
     * @param value  the value
     * @param valid  the declared verdict
     */
    private static void assertLongValueRow(String format, String value, boolean valid) {
        Handler<RoutingContext> gate = bodyGate(defaults(), formatString(format));
        RoutingContext ctx = new GateRequest().body(value).context();

        SmallStackResult<GateOutcome> outcome = onSmallStack(ROW_TIMEOUT, () -> handleOnCallingThread(gate, ctx));

        assertNull(
                outcome.uncaught(),
                () -> "no throwable on the 1 MB-stack thread for " + format + ": " + outcome.uncaught());
        if (valid) {
            outcome.value().assertAccepted("(" + format + ") a valid 4,096-character value");
        } else {
            List<ValidationErrorDetail> details = outcome.value().rejection();
            assertEquals(1, details.size(), () -> "(" + format + ") exactly one detail: " + types(details));
            ValidationErrorDetail oracle = oracleDetail(format);
            ValidationErrorDetail actual = details.get(0);
            assertAll(
                    "(" + format + ") the invalid value's detail must match its short invalid value's detail",
                    () -> assertEquals(oracle.path(), actual.path(), "path"),
                    () -> assertEquals(oracle.location(), actual.location(), "location"),
                    () -> assertEquals(oracle.type(), actual.type(), "type"),
                    () -> assertEquals(oracle.detail(), actual.detail(), "detail"));
            assertValueFree(details, value.length() >= 16 ? value.substring(0, 16) : value);
        }
    }

    /**
     * TP-001's small-limit row: with {@code validationPatternMaxChars} 16 and
     * {@code validationPatternMaxTotalChars} 32, an array body holding {@code format}'s valid
     * 4,096-character value twice must be accepted, with no {@code patternInputLength} or
     * {@code patternInputTotalLength} detail: the format is neither bounded (the value exceeds 16) nor
     * counted (the body exceeds 32).
     *
     * @param format the format
     * @param valid  the format's valid 4,096-character value
     * @return the row
     */
    private static Named<Executable> smallLimitRow(String format, String valid) {
        return Named.of(
                "(" + format + ") small-limit array body: neither bounded nor counted",
                () -> assertSmallLimitRow(format, valid));
    }

    private static void assertSmallLimitRow(String format, String valid) {
        JaxRsConfig smallLimits = JaxRsConfig.builder()
                .validationPatternMaxChars(16)
                .validationPatternMaxTotalChars(32)
                .build();
        JsonObject schema = new JsonObject().put("type", "array").put("items", formatString(format));
        JsonArray body = new JsonArray().add(valid).add(valid);
        Handler<RoutingContext> gate = bodyGate(smallLimits, schema);
        RoutingContext ctx = new GateRequest().body(body).context();

        SmallStackResult<GateOutcome> outcome = onSmallStack(ROW_TIMEOUT, () -> handleOnCallingThread(gate, ctx));

        assertNull(outcome.uncaught(), () -> "(" + format + ") no throwable: " + outcome.uncaught());
        outcome.value().assertAccepted("(" + format + ") the small-limit array body");
    }

    /**
     * Returns the detail the gate reports for {@code format}'s short invalid value (E20), used as TP-001's
     * per-format oracle.
     *
     * @param format the format
     * @return the one detail
     */
    private static ValidationErrorDetail oracleDetail(String format) {
        Handler<RoutingContext> gate = bodyGate(defaults(), formatString(format));
        RoutingContext ctx = new GateRequest().body(SHORT_INVALID.get(format)).context();
        GateOutcome outcome = handle(gate, ctx, ROW_TIMEOUT);
        List<ValidationErrorDetail> details = outcome.rejection();
        assertEquals(
                1,
                details.size(),
                () -> "(" + format + ") the short invalid oracle value must yield exactly one detail: "
                        + types(details));
        return details.get(0);
    }

    // =========================================================================================
    // TP-002 — The reused checks follow the JSON Schema Test Suite, with recorded differences only
    // =========================================================================================

    /**
     * TP-002. Given every case of the five copied suite files, the derived {@code json-pointer-uri-fragment}
     * cases, the single-pass rows, and the extra fragment rows, when each is validated through the gate's
     * compiled validator, then every case gets the suite's verdict except exactly the cases on its format's
     * recorded-difference list (uri-template has none, F1).
     *
     * @param row the row
     * @throws Throwable never, but {@link Executable#execute()} declares it
     */
    @DisplayName("TP-002: reused formats follow the JSON Schema Test Suite, with recorded differences only")
    @ParameterizedTest(name = "{0}")
    @MethodSource("reusedFormatsFollowTheJsonSchemaTestSuiteRows")
    void reusedFormatsFollowTheJsonSchemaTestSuite(Executable row) throws Throwable {
        row.execute();
    }

    /**
     * {@code uri.json}'s two recorded differences (step 3): {@code java.net.URI} accepts a non-numeric port
     * and a leading zero in an IPv4 address embedded in an IPv6 literal, both of which the suite rejects.
     */
    private static final Set<String> URI_JSON_DIFFERENCES =
            Set.of("non-numeric port is invalid", "leading zero in an embedded IPv4 address is invalid");

    /**
     * {@code uri-reference.json}'s four recorded differences (step 3): an empty network-path authority
     * ({@code java.net.URI} rejects it, the suite accepts it), and three cases where {@code java.net.URI}
     * is more lenient than the suite (a non-numeric port, more than one at-sign, a leading zero in an
     * embedded IPv4 address).
     */
    private static final Set<String> URI_REFERENCE_JSON_DIFFERENCES = Set.of(
            "a network-path reference with an empty authority",
            "a non-numeric port in a network-path reference",
            "more than one at-sign in the authority",
            "a leading zero in the IPv4 part of an IPv6 literal");

    /** {@code json-pointer.json}: no recorded difference (step 3: the single-pass rule matches every case). */
    private static final Set<String> JSON_POINTER_JSON_DIFFERENCES = Set.of();

    /**
     * {@code relative-json-pointer.json}: no recorded difference (step 3: the prefix-plus-pointer rule
     * matches every case).
     */
    private static final Set<String> RELATIVE_JSON_POINTER_JSON_DIFFERENCES = Set.of();

    /**
     * The {@code json-pointer.json} string cases, percent-encoded and prefixed with {@code #}: no recorded
     * difference (step 3).
     */
    private static final Set<String> JSON_POINTER_URI_FRAGMENT_DIFFERENCES = Set.of();

    static Stream<Named<Executable>> reusedFormatsFollowTheJsonSchemaTestSuiteRows() {
        List<Named<Executable>> rows = new ArrayList<>();
        rows.addAll(suiteRows("uri", "uri.json", URI_JSON_DIFFERENCES));
        rows.addAll(suiteRows("uri-reference", "uri-reference.json", URI_REFERENCE_JSON_DIFFERENCES));
        rows.addAll(suiteRows("json-pointer", "json-pointer.json", JSON_POINTER_JSON_DIFFERENCES));
        rows.addAll(suiteRows(
                "relative-json-pointer", "relative-json-pointer.json", RELATIVE_JSON_POINTER_JSON_DIFFERENCES));
        rows.addAll(uriTemplateSuiteRows());
        rows.addAll(jsonPointerUriFragmentDerivedRows());
        rows.addAll(jsonPointerUriFragmentExtraRows());
        return rows.stream();
    }

    /**
     * Builds one suite file's rows against {@code format}, expecting the suite's verdict except on
     * {@code differences}.
     *
     * @param format      the format
     * @param file        the suite file name under {@code SUITE}
     * @param differences the recorded-difference list, keyed by the suite case's description
     * @return the rows
     */
    private static List<Named<Executable>> suiteRows(String format, String file, Set<String> differences) {
        List<Named<Executable>> rows = new ArrayList<>();
        for (SuiteCase testCase : readSuiteCases(file)) {
            boolean expected = differences.contains(testCase.description()) ? !testCase.valid() : testCase.valid();
            rows.add(Named.of(
                    "(" + format + ") " + testCase.description() + " " + jsonLabel(testCase.data()),
                    () -> assertEquals(
                            expected,
                            gateVerdict(format, testCase.data()),
                            "(" + format + ") " + testCase.description())));
        }
        return rows;
    }

    /**
     * Builds {@code uri-template.json}'s rows: every case must get the suite's verdict (F1, no difference
     * list).
     *
     * @return the rows
     */
    private static List<Named<Executable>> uriTemplateSuiteRows() {
        List<Named<Executable>> rows = new ArrayList<>();
        for (SuiteCase testCase : readSuiteCases("uri-template.json")) {
            rows.add(Named.of(
                    "(uri-template) " + testCase.description() + " " + jsonLabel(testCase.data()),
                    () -> assertEquals(
                            testCase.valid(),
                            gateVerdict("uri-template", testCase.data()),
                            "(uri-template) " + testCase.description())));
        }
        return rows;
    }

    /**
     * Derives {@code json-pointer-uri-fragment}'s rows from {@code json-pointer.json}'s string cases: each
     * prefixed with {@code #}, its characters percent-encoded outside RFC 3986 {@code pchar / "/" / "?"}
     * (E8), plus the non-string cases tested directly.
     *
     * @return the rows
     */
    private static List<Named<Executable>> jsonPointerUriFragmentDerivedRows() {
        List<Named<Executable>> rows = new ArrayList<>();
        for (SuiteCase testCase : readSuiteCases("json-pointer.json")) {
            if (!(testCase.data() instanceof String s)) {
                rows.add(Named.of(
                        "(json-pointer-uri-fragment, non-string) " + testCase.description(),
                        () -> assertEquals(
                                testCase.valid(),
                                gateVerdict("json-pointer-uri-fragment", testCase.data()),
                                "(json-pointer-uri-fragment) " + testCase.description())));
                continue;
            }
            String fragment = "#" + percentEncodeOutsidePcharSlashQuestion(s);
            boolean expected = JSON_POINTER_URI_FRAGMENT_DIFFERENCES.contains(testCase.description())
                    ? !testCase.valid()
                    : testCase.valid();
            rows.add(Named.of(
                    "(json-pointer-uri-fragment) " + testCase.description() + " " + jsonLabel(fragment),
                    () -> assertEquals(
                            expected,
                            gateVerdict("json-pointer-uri-fragment", fragment),
                            "(json-pointer-uri-fragment) " + testCase.description())));
        }
        return rows;
    }

    /**
     * The extra {@code json-pointer-uri-fragment} rows (E8, TP-002's Given) and the single-pass rows (E14).
     *
     * @return the rows
     */
    private static List<Named<Executable>> jsonPointerUriFragmentExtraRows() {
        return List.of(
                Named.of(
                        "(json-pointer-uri-fragment) #/%7E0 (valid)",
                        () -> assertEquals(true, gateVerdict("json-pointer-uri-fragment", "#/%7E0"))),
                Named.of(
                        "(json-pointer-uri-fragment) #/%7E2 (invalid)",
                        () -> assertEquals(false, gateVerdict("json-pointer-uri-fragment", "#/%7E2"))),
                Named.of(
                        "(json-pointer-uri-fragment) #/%7E (invalid)",
                        () -> assertEquals(false, gateVerdict("json-pointer-uri-fragment", "#/%7E"))),
                Named.of(
                        "(json-pointer-uri-fragment) #/a%2Fb (valid)",
                        () -> assertEquals(true, gateVerdict("json-pointer-uri-fragment", "#/a%2Fb"))),
                Named.of(
                        "(json-pointer) single-pass /~~01 (invalid)",
                        () -> assertEquals(false, gateVerdict("json-pointer", "/~~01"))),
                Named.of(
                        "(json-pointer-uri-fragment) single-pass #/~~01 (invalid)",
                        () -> assertEquals(false, gateVerdict("json-pointer-uri-fragment", "#/~~01"))));
    }

    /**
     * Percent-encodes every character of {@code value} (by UTF-8 byte) outside RFC 3986
     * {@code pchar / "/" / "?"}, with {@code %} itself re-encoded as {@code %25} (E8).
     *
     * @param value the string case's data
     * @return the encoded fragment content, without its leading {@code #}
     */
    private static String percentEncodeOutsidePcharSlashQuestion(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int c = raw & 0xFF;
            if (isPcharSlashQuestionSafe(c)) {
                encoded.append((char) c);
            } else {
                encoded.append(String.format("%%%02X", c));
            }
        }
        return encoded.toString();
    }

    /**
     * Returns whether byte {@code c} is inside RFC 3986 {@code pchar / "/" / "?"} and so needs no
     * percent-encoding; {@code %} is always excluded so it is always re-encoded (E8).
     *
     * @param c the byte, as an unsigned int
     * @return whether it is safe unencoded
     */
    private static boolean isPcharSlashQuestionSafe(int c) {
        if (c == '%') {
            return false;
        }
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
            return true;
        }
        if (c == '-' || c == '.' || c == '_' || c == '~') {
            return true;
        }
        if ("!$&'()*+,;=".indexOf(c) >= 0) {
            return true;
        }
        return c == ':' || c == '@' || c == '/' || c == '?';
    }

    // =========================================================================================
    // TP-003 — Verdict differences against the engine are exactly the documented list
    // =========================================================================================

    /**
     * TP-003. Given a pinned corpus of at most 512-character values for the six formats other than
     * {@code url} (TP-004 owns {@code url}), when each is validated through the gate's compiled validator,
     * then the gate's verdict equals C-FORMAT's verdict on every row, so the rows where the gate differs
     * from the engine are exactly the documented difference list.
     *
     * @param row the row
     * @throws Throwable never, but {@link Executable#execute()} declares it
     */
    @DisplayName("TP-003: engine verdict differences are exactly the documented list")
    @ParameterizedTest(name = "{0}")
    @MethodSource("engineVerdictDifferencesAreExactlyTheDocumentedListRows")
    void engineVerdictDifferencesAreExactlyTheDocumentedList(Executable row) throws Throwable {
        row.execute();
    }

    /**
     * {@code uri}'s documented differences (step 3, extended by review round 1 finding R-002):
     * {@code java.net.URI} rejects {@code "http://"}; a trailing {@code \n} the engine accepts;
     * {@code [0]} inside a query, which the engine rejects but {@link URI} parses; and an IPvFuture host
     * literal ({@code [v1.x]}), which the engine accepts but {@link URI} does not parse.
     */
    private static final Set<String> URI_CORPUS_DIFFERENCES =
            Set.of("http://", "http://foo.bar/\n", "http://a.com/?x[0]=1", "http://[v1.x]/");

    /**
     * {@code uri-reference}'s documented differences (step 3, extended by R-002): {@code java.net.URI}
     * rejects {@code "http://"} and {@code "a:"}, both of which the engine's expression accepts; a
     * trailing {@code \n} the engine accepts; {@code [0]} inside a query or a bare fragment, which the
     * engine rejects but {@link URI} parses; and an IPvFuture host literal, which the engine accepts but
     * {@link URI} does not parse.
     */
    private static final Set<String> URI_REFERENCE_CORPUS_DIFFERENCES =
            Set.of("http://", "a:", "/p\n", "http://a.com/?x[0]=1", "#[0]", "http://[v1.x]/");

    /**
     * {@code json-pointer}'s one documented difference (R-002): a trailing {@code \n}, which is not an
     * empty string and does not start with {@code /}, so C-FORMAT rejects it while the engine accepts it.
     */
    private static final Set<String> JSON_POINTER_CORPUS_DIFFERENCES = Set.of("\n");

    /**
     * {@code relative-json-pointer}'s one documented difference (R-002): a trailing {@code \n} after the
     * {@code 0} prefix, which C-FORMAT's {@code json-pointer} check on the remainder rejects while the
     * engine accepts.
     */
    private static final Set<String> RELATIVE_JSON_POINTER_CORPUS_DIFFERENCES = Set.of("0\n");

    /**
     * {@code json-pointer-uri-fragment}'s documented differences (R-002): a trailing {@code \n}, which the
     * engine accepts; {@code %7E2} and a trailing {@code %7E}, whose percent-decoded tilde the engine's
     * expression does not check the way C-FORMAT's {@code json-pointer} pass does; and a non-ASCII
     * character, {@code ?}, and {@code [0]} after the leading {@code /}, all valid JSON-pointer reference
     * tokens C-FORMAT accepts once percent-decoded through {@link URI}'s fragment, which the engine's
     * expression rejects.
     */
    private static final Set<String> JSON_POINTER_URI_FRAGMENT_CORPUS_DIFFERENCES =
            Set.of("#/a\n", "#/%7E2", "#/%7E", "#/ä", "#/a?b", "#/a[0]");

    /**
     * {@code uri-template}'s documented differences (step 3, E14; extended by review round 1 findings
     * R-002 and R-005, and by the closing-round PIT contract-gap rows): the engine's expression does not
     * accept a dotted variable name ({@code {a.b}}), or one whose second label is a percent-encoded
     * triplet ({@code {a.%41}}), both of which RFC 6570 and the scanner accept; a trailing {@code \n}, an
     * apostrophe, U+0085, a lone high surrogate embedded in a literal, and U+FFFF, on which the scanner
     * and the engine's expression disagree in both directions; a lone high surrogate at the end of the
     * value and a lone low surrogate, both of which the scanner rejects and the engine's expression
     * accepts; and U+E0001, U+EFFFE, and U+FFFFE, each outside RFC 3987's {@code ucschar} range (the
     * {@code %xE1000-EFFFD} and {@code %xF0000-FFFFD} bands exclude them, R-005) but accepted by the
     * engine's expression.
     */
    private static final Set<String> URI_TEMPLATE_CORPUS_DIFFERENCES = Set.of(
            "{a.b}",
            "{a.%41}",
            "{a}\n",
            "a'b",
            "a\u0085b",
            "a\uD800b",
            "a\uFFFFb",
            "a\uDB40\uDC01b",
            "a\uD800",
            "a\uDC00b",
            "a\uDB7F\uDFFEb",
            "a\uDBBF\uDFFEb");

    static Stream<Named<Executable>> engineVerdictDifferencesAreExactlyTheDocumentedListRows() {
        List<Named<Executable>> rows = new ArrayList<>();

        List<String> uriCorpus = List.of(
                "http://example.com/",
                "https://example.com/",
                "ftp://example.com/",
                "mailto:a@example.com",
                "urn:example:a123",
                "file:///etc/passwd",
                "/a/b",
                "a/b?c#d",
                "#f",
                "?q",
                "",
                "//host/p",
                "a b",
                "http://example.com/%zz",
                "http://example.com/%20",
                "http://example.com/bücher",
                "http://example.com/‮",
                "http://example.com/[",
                "http://[::1]/",
                "http://",
                "a:",
                // R-002 (review round 1): a bracketed query index and an IPvFuture host, shared by uri
                // and uri-reference (the finding's "uri/uri-reference" class)
                "http://a.com/?x[0]=1",
                "#[0]",
                "http://[v1.x]/");
        for (String value : uriCorpus) {
            rows.add(corpusRow("uri", value, URI_CORPUS_DIFFERENCES));
            rows.add(corpusRow("uri-reference", value, URI_REFERENCE_CORPUS_DIFFERENCES));
        }
        // R-002: a trailing newline, one row per format since each format's grammar starts differently
        rows.add(corpusRow("uri", "http://foo.bar/\n", URI_CORPUS_DIFFERENCES));
        rows.add(corpusRow("uri-reference", "/p\n", URI_REFERENCE_CORPUS_DIFFERENCES));

        List<String> pointerCorpus =
                List.of("", "/", "//", "/~0", "/~1", "/~", "/0", "/00", "/01", "/1a", "#", "#/", "#a", "x#/a", "9");
        for (String value : pointerCorpus) {
            rows.add(corpusRow("json-pointer", value, JSON_POINTER_CORPUS_DIFFERENCES));
            rows.add(corpusRow("relative-json-pointer", value, RELATIVE_JSON_POINTER_CORPUS_DIFFERENCES));
            rows.add(corpusRow("json-pointer-uri-fragment", value, JSON_POINTER_URI_FRAGMENT_CORPUS_DIFFERENCES));
        }
        // R-002: a trailing newline, one row per pointer format (each format's own prefix rule differs)
        rows.add(corpusRow("json-pointer", "\n", JSON_POINTER_CORPUS_DIFFERENCES));
        rows.add(corpusRow("relative-json-pointer", "0\n", RELATIVE_JSON_POINTER_CORPUS_DIFFERENCES));
        rows.add(corpusRow("json-pointer-uri-fragment", "#/a\n", JSON_POINTER_URI_FRAGMENT_CORPUS_DIFFERENCES));
        // R-002: percent-decoded tilde escapes and reference tokens valid only after fragment decoding
        rows.add(corpusRow("json-pointer-uri-fragment", "#/%7E2", JSON_POINTER_URI_FRAGMENT_CORPUS_DIFFERENCES));
        rows.add(corpusRow("json-pointer-uri-fragment", "#/%7E", JSON_POINTER_URI_FRAGMENT_CORPUS_DIFFERENCES));
        rows.add(corpusRow("json-pointer-uri-fragment", "#/ä", JSON_POINTER_URI_FRAGMENT_CORPUS_DIFFERENCES));
        rows.add(corpusRow("json-pointer-uri-fragment", "#/a?b", JSON_POINTER_URI_FRAGMENT_CORPUS_DIFFERENCES));
        rows.add(corpusRow("json-pointer-uri-fragment", "#/a[0]", JSON_POINTER_URI_FRAGMENT_CORPUS_DIFFERENCES));

        List<String> templateCorpus = List.of(
                "literal",
                "{+var}",
                "{#var}",
                "{.var}",
                "{/var}",
                "{;var}",
                "{?var}",
                "{&var}",
                "{var:1}",
                "{var*}",
                "{v:0}",
                "{v:10000}",
                "{a,,b}",
                "{a.b}",
                "{a..b}",
                "{=a}",
                "{a",
                "a}",
                // Closing-round PIT contract-gap rows: literal characters and structural edge cases the
                // corpus above does not yet reach.
                "a~b",
                "a\"b",
                "{a%41}",
                "{a,.b}",
                "{a.",
                "{a.}",
                "{a.%41}",
                ",{}",
                "{a:1",
                "{a:}",
                "{a:9}",
                "{AZ}",
                "{az}",
                "{09}",
                "{_}",
                "a%09b",
                "a%afb",
                "a%AFb",
                "a%0gb");
        for (String value : templateCorpus) {
            rows.add(corpusRow("uri-template", value, URI_TEMPLATE_CORPUS_DIFFERENCES));
        }
        // R-002: a trailing newline in a literal, and literal characters on which the scanner and the
        // engine's expression disagree in both directions (an apostrophe, U+0085, a lone high surrogate,
        // and U+FFFF)
        rows.add(corpusRow("uri-template", "{a}\n", URI_TEMPLATE_CORPUS_DIFFERENCES));
        rows.add(corpusRow("uri-template", "a'b", URI_TEMPLATE_CORPUS_DIFFERENCES));
        rows.add(corpusRow("uri-template", "a\u0085b", URI_TEMPLATE_CORPUS_DIFFERENCES));
        rows.add(corpusRow("uri-template", "a\uD800b", URI_TEMPLATE_CORPUS_DIFFERENCES));
        rows.add(corpusRow("uri-template", "a\uFFFFb", URI_TEMPLATE_CORPUS_DIFFERENCES));
        // R-005: U+E0001 is below RFC 3987's ucschar lower bound 0xE1000 (the %xE1000-EFFFD band starts
        // above it), so the scanner rejects it while the engine's expression accepts it.
        rows.add(corpusRow("uri-template", "a\uDB40\uDC01b", URI_TEMPLATE_CORPUS_DIFFERENCES));

        // Closing-round PIT contract-gap rows: a lone high surrogate at the end of the value, a lone low
        // surrogate, and the code points immediately above the ucschar upper bounds the scanner enforces
        // for the two highest supplementary ranges (R-005) \u2014 all four the engine's expression accepts and
        // the scanner rejects.
        rows.add(corpusRow("uri-template", "a\uD800", URI_TEMPLATE_CORPUS_DIFFERENCES));
        rows.add(corpusRow("uri-template", "a\uDC00b", URI_TEMPLATE_CORPUS_DIFFERENCES));
        rows.add(corpusRow("uri-template", "a\uDB7F\uDFFEb", URI_TEMPLATE_CORPUS_DIFFERENCES));
        rows.add(corpusRow("uri-template", "a\uDBBF\uDFFEb", URI_TEMPLATE_CORPUS_DIFFERENCES));

        // Every ucschar/iprivate range the scanner accepts (source order), both endpoints in one literal
        // so a narrowed bound or a disabled range rejects it.
        List<String> ucscharRangeCorpus = List.of(
                "a\u00A0\uD7FFb",
                "a\uE000\uF8FFb",
                "a\uF900\uFDCFb",
                "a\uFDF0\uFFEFb",
                "a\uD800\uDC00\uD83F\uDFFDb",
                "a\uD840\uDC00\uD87F\uDFFDb",
                "a\uD880\uDC00\uD8BF\uDFFDb",
                "a\uD8C0\uDC00\uD8FF\uDFFDb",
                "a\uD900\uDC00\uD93F\uDFFDb",
                "a\uD940\uDC00\uD97F\uDFFDb",
                "a\uD980\uDC00\uD9BF\uDFFDb",
                "a\uD9C0\uDC00\uD9FF\uDFFDb",
                "a\uDA00\uDC00\uDA3F\uDFFDb",
                "a\uDA40\uDC00\uDA7F\uDFFDb",
                "a\uDA80\uDC00\uDABF\uDFFDb",
                "a\uDAC0\uDC00\uDAFF\uDFFDb",
                "a\uDB00\uDC00\uDB3F\uDFFDb",
                "a\uDB44\uDC00\uDB7F\uDFFDb",
                "a\uDB80\uDC00\uDBBF\uDFFDb",
                "a\uDBC0\uDC00\uDBFF\uDFFDb");
        for (String value : ucscharRangeCorpus) {
            rows.add(corpusRow("uri-template", value, URI_TEMPLATE_CORPUS_DIFFERENCES));
        }
        return rows.stream();
    }

    /**
     * Builds one TP-003 corpus row: the gate's verdict must equal C-FORMAT's verdict, and the row must be
     * on {@code differences} exactly when C-FORMAT's verdict differs from the engine's.
     *
     * @param format      the format
     * @param value       the corpus value
     * @param differences the format's documented-difference list
     * @return the row
     */
    private static Named<Executable> corpusRow(String format, String value, Set<String> differences) {
        return Named.of("(" + format + ") " + jsonLabel(value), () -> assertCorpusRow(format, value, differences));
    }

    private static void assertCorpusRow(String format, String value, Set<String> differences) {
        boolean cFormat = cFormatVerdict(format, value);
        boolean engine = engineVerdict(format, value);
        boolean gate = gateVerdict(format, value);
        boolean expectedDifference = differences.contains(value);
        assertAll(
                "(" + format + ") " + jsonLabel(value),
                () -> assertEquals(
                        cFormat,
                        gate,
                        () -> "(" + format + ") the gate's verdict must equal C-FORMAT's verdict for "
                                + jsonLabel(value)),
                () -> assertEquals(
                        expectedDifference,
                        cFormat != engine,
                        () -> "(" + format + ") the documented-difference list must name exactly the rows"
                                + " where C-FORMAT differs from the engine: " + jsonLabel(value)));
    }

    // =========================================================================================
    // TP-006 — The private copy renames exactly the seven formats; the shared schemas keep them
    // =========================================================================================

    /**
     * TP-006. Given a matrix of hand-built schemas carrying each of the seven formats at seven structural
     * positions, a node combining {@code format: uri} with a string {@code pattern}, the bounded and
     * unaffected formats, formats inside literal-keyword values, a property literally named {@code format},
     * a non-string {@code format} value, and an operation whose body and query-parameter schemas carry
     * {@code format: uri}, when the guard's rewrite runs on each matrix input and {@code gateFor} runs for
     * the operation, then each rewritten document equals its expected form exactly and every schema
     * {@link OperationSchemas} holds is unchanged by {@code gateFor} and still says {@code format: uri}.
     *
     * @param row the row
     */
    @DisplayName("TP-006: the private copy renames exactly the seven formats")
    @ParameterizedTest(name = "{0}")
    @MethodSource("rewriteRenamesTheReusedFormatsInThePrivateCopyRows")
    void rewriteRenamesTheReusedFormatsInThePrivateCopy(Executable row) throws Throwable {
        row.execute();
    }

    /** One structural position a {@code format} value can sit at, and how to build a document around it. */
    private record RenamePosition(String label, java.util.function.Function<String, JsonObject> build) {}

    private static final List<RenamePosition> RENAME_POSITIONS = List.of(
            new RenamePosition("root", format -> new JsonObject().put("format", format)),
            new RenamePosition("properties", format -> new JsonObject()
                    .put("type", "object")
                    .put("properties", new JsonObject().put("name", new JsonObject().put("format", format)))),
            new RenamePosition("items", format -> new JsonObject()
                    .put("type", "array")
                    .put("items", new JsonObject().put("format", format))),
            new RenamePosition("$defs via $ref", format -> new JsonObject()
                    .put("$ref", "#/$defs/leaf")
                    .put("$defs", new JsonObject().put("leaf", new JsonObject().put("format", format)))),
            new RenamePosition("not", format -> new JsonObject().put("not", new JsonObject().put("format", format))),
            new RenamePosition("anyOf", format -> new JsonObject()
                    .put(
                            "anyOf",
                            new JsonArray()
                                    .add(new JsonObject().put("format", format))
                                    .add(new JsonObject().put("type", "integer")))),
            new RenamePosition("if/then/else", format -> new JsonObject()
                    .put("if", new JsonObject().put("format", format))
                    .put("then", new JsonObject().put("format", format))
                    .put("else", new JsonObject().put("format", format))));

    static Stream<Named<Executable>> rewriteRenamesTheReusedFormatsInThePrivateCopyRows() {
        List<Named<Executable>> rows = new ArrayList<>();

        for (String format : SEVEN_FORMATS) {
            String renamed = "x-vertique-format-" + format;
            for (RenamePosition position : RENAME_POSITIONS) {
                JsonObject input = position.build().apply(format);
                JsonObject expected = position.build().apply(renamed);
                rows.add(Named.of(
                        "(" + format + ", " + position.label() + ") renamed in place",
                        () -> assertRewriteRow(input, expected)));
            }
        }

        JsonObject patternPlusFormat =
                new JsonObject().put("type", "string").put("format", "uri").put("pattern", "^a+$");
        JsonObject patternPlusFormatExpected = new JsonObject()
                .put("type", "string")
                .put("format", "x-vertique-format-uri")
                .put("pattern", "^a+$")
                .put("allOf", new JsonArray().add(new JsonObject().put("format", "x-vertique-pattern-input-bound")));
        rows.add(Named.of(
                "(uri, pattern position) renamed, plus T004's bound entry",
                () -> assertRewriteRow(patternPlusFormat, patternPlusFormatExpected)));

        for (String format : List.of("idn-hostname", "idn-email", "regex")) {
            JsonObject input = new JsonObject().put("type", "string").put("format", format);
            JsonObject expected = new JsonObject()
                    .put("type", "string")
                    .put("format", format)
                    .put(
                            "allOf",
                            new JsonArray().add(new JsonObject().put("format", "x-vertique-pattern-input-bound")));
            rows.add(Named.of(
                    "(" + format + ") unchanged apart from T004's bound entry",
                    () -> assertRewriteRow(input, expected)));
        }
        for (String format : List.of("iri", "iri-reference", "date-time", "uuid", "x-app-format")) {
            JsonObject input = new JsonObject().put("type", "string").put("format", format);
            JsonObject expected = input.copy();
            rows.add(Named.of("(" + format + ") unchanged", () -> assertRewriteRow(input, expected)));
        }

        for (String literalKeyword : List.of("const", "default", "example")) {
            JsonObject input = new JsonObject().put(literalKeyword, new JsonObject().put("format", "uri"));
            rows.add(Named.of(
                    "(" + literalKeyword + " value) format: uri unchanged",
                    () -> assertRewriteRow(input, input.copy())));
        }
        for (String literalKeyword : List.of("enum", "examples")) {
            JsonObject input =
                    new JsonObject().put(literalKeyword, new JsonArray().add(new JsonObject().put("format", "uri")));
            rows.add(Named.of(
                    "(" + literalKeyword + " value) format: uri unchanged",
                    () -> assertRewriteRow(input, input.copy())));
        }

        JsonObject propertyNamedFormat = new JsonObject()
                .put("type", "object")
                .put("properties", new JsonObject().put("format", new JsonObject().put("type", "string")));
        rows.add(Named.of(
                "(property literally named format) unchanged",
                () -> assertRewriteRow(propertyNamedFormat, propertyNamedFormat.copy())));

        JsonObject nonStringFormat = new JsonObject().put("format", 123);
        rows.add(Named.of(
                "(non-string format value) unchanged",
                () -> assertRewriteRow(nonStringFormat, nonStringFormat.copy())));

        rows.add(Named.of(
                "(gateFor) shared schemas keep the standard format name",
                FormatCheckReuseTest::assertGateForKeepsSharedSchemas));

        return rows.stream();
    }

    /**
     * Asserts one TP-006 rewrite row: {@code PatternInputGuard.rewrite(input)} equals {@code expected}
     * exactly, {@code input} itself is left unmodified, and the rewrite returns a new instance.
     *
     * @param input    the row's input schema
     * @param expected the row's expected rewritten form
     */
    private static void assertRewriteRow(JsonObject input, JsonObject expected) {
        JsonObject untouched = input.copy();

        JsonObject rewritten = PatternInputGuard.rewrite(input);

        assertAll(
                () -> assertEquals(expected, rewritten, "the rewritten document must equal its expected form exactly"),
                () -> assertEquals(untouched, input, "the rewrite must not modify its input"),
                () -> assertNotSame(input, rewritten, "the rewrite must return a copy, not its input"));
    }

    /**
     * TP-006's {@code gateFor} row: a deep copy of the operation's body and query-parameter schemas taken
     * before {@code gateFor}, compared to the same objects after it. {@code gateFor} never mutates or
     * replaces them, so publication, which copies before {@code gateFor}, never sees a renamed format.
     */
    private static void assertGateForKeepsSharedSchemas() {
        JsonObject bodySchema = formatString("uri");
        JsonObject paramSchema = formatString("uri");
        JsonObject bodyBeforeCall = bodySchema.copy();
        JsonObject paramBeforeCall = paramSchema.copy();

        JaxRsOperationDescriptor op =
                operation(List.of(param("q", ParamLocation.QUERY, String.class, null)), true, List.of());
        OperationSchemas schemas = OperationSchemas.builder()
                .bodySchema(bodySchema)
                .parameterSchema(ParamLocation.QUERY, "q", paramSchema)
                .build();

        new WebValidationStrategy(JaxRsConfig.builder().build()).gateFor(op, schemas);

        assertAll(
                () -> assertSame(
                        bodySchema, schemas.bodySchema().orElseThrow(), "the held body schema instance is unchanged"),
                () -> assertEquals(
                        bodyBeforeCall,
                        schemas.bodySchema().orElseThrow(),
                        "the body schema must equal its pre-call copy and still say format: uri"),
                () -> assertSame(
                        paramSchema,
                        schemas.parameterSchema(ParamLocation.QUERY, "q").orElseThrow(),
                        "the held parameter schema instance is unchanged"),
                () -> assertEquals(
                        paramBeforeCall,
                        schemas.parameterSchema(ParamLocation.QUERY, "q").orElseThrow(),
                        "the parameter schema must equal its pre-call copy and still say format: uri"));
    }

    // =========================================================================================
    // TP-007 — Default-limit proofs: every reused format stays within budget, or takes its fallback
    // =========================================================================================

    /**
     * TP-007. Given grammar-shaped worst-case values of each reused format padded to {@link #BODY_MAX} and
     * {@link #HEADER_MAX} characters, each valid shape with an invalid twin whose single violation is its
     * last character (E9), when the gate handler handles each request on the 1 MB-stack thread under a
     * 30-second preemptive timeout (warm-up, then timed) and the format validator judges the
     * already-renamed name directly (warm-up, then timed, measuring elapsed time and allocated bytes),
     * then every row is answered with no throwable and C-FORMAT's verdict, and the timed run stays within
     * the pre-decided budget.
     *
     * @param row the row
     * @throws Throwable never, but {@link Executable#execute()} declares it
     */
    @DisplayName("TP-007: reused formats stay within budget at the default limits")
    @ParameterizedTest(name = "{0}")
    @MethodSource("reusedFormatsStayWithinBudgetAtTheDefaultLimitsRows")
    void reusedFormatsStayWithinBudgetAtTheDefaultLimits(Executable row) throws Throwable {
        row.execute();
    }

    /** One TP-007 grammar-shaped construction (E9): {@code prefix + unit* + suffix}, padded to length. */
    private record BudgetShape(
            String format, String label, String prefix, String unit, String suffix, char filler, char invalidChar) {

        /**
         * Builds the valid value at {@code targetLength}: {@code prefix}, then as many whole repetitions of
         * {@code unit} as fit, then filler characters for any remainder (only when the budget does not
         * divide evenly by {@code unit}'s length), then {@code suffix}. Filler sits after the units, never
         * before {@code prefix}, so a format whose grammar constrains the leading character (json-pointer's
         * "empty or starts with /", for example) is never broken by it; the invalid twin's unconditional
         * last-character technique tolerates filler landing last (verified at BODY_MAX and HEADER_MAX for
         * every shape below, evidence T021.md TP-007).
         *
         * @param targetLength the exact target length
         * @return the valid value
         */
        String valid(int targetLength) {
            int budget = targetLength - suffix.length();
            StringBuilder built = new StringBuilder(targetLength);
            built.append(prefix);
            while (built.length() + unit.length() <= budget) {
                built.append(unit);
            }
            while (built.length() < budget) {
                built.append(filler);
            }
            built.append(suffix);
            return built.toString();
        }

        /**
         * Builds the invalid twin of {@link #valid(int)}: the same value with its last character replaced
         * (E9). uri, uri-reference, url, and uri-template use a raw space, which is unconditionally illegal
         * in all four grammars regardless of what the last character otherwise is; the three JSON-pointer
         * formats use a bare tilde, unconditionally invalid at the end of a value since a trailing
         * {@code ~} is never followed by {@code 0} or {@code 1}.
         *
         * @param targetLength the exact target length
         * @return the invalid twin
         */
        String invalid(int targetLength) {
            String value = valid(targetLength);
            return value.substring(0, value.length() - 1) + invalidChar;
        }
    }

    private static final List<BudgetShape> BUDGET_SHAPES = List.of(
            new BudgetShape("uri", "percent-encoded run", "https://example.com/", "%20", "", 'a', ' '),
            new BudgetShape("uri", "literal path run", "https://example.com/", "a/", "", 'a', ' '),
            new BudgetShape("uri-reference", "percent-encoded run", "/", "%41", "", 'a', ' '),
            new BudgetShape("uri-reference", "query run", "?", "a=b&", "", 'a', ' '),
            new BudgetShape("url", "percent-encoded run", "https://example.com/", "%20", "", 'a', ' '),
            new BudgetShape("url", "host label run", "http://", "a.", "com/", 'a', ' '),
            new BudgetShape("uri-template", "one long variable name", "{", "a", "}", 'a', ' '),
            new BudgetShape("uri-template", "many short expressions", "", "{a}", "", 'a', ' '),
            new BudgetShape("uri-template", "reserved-operator expressions", "", "{+a,b}", "", 'a', ' '),
            new BudgetShape("json-pointer", "unescaped run", "", "/a", "", 'a', '~'),
            new BudgetShape("json-pointer", "escaped run", "", "/~0~1", "", '0', '~'),
            new BudgetShape("relative-json-pointer", "zero-digit run, member-name form", "1", "0", "#", '0', '~'),
            new BudgetShape("relative-json-pointer", "escaped run", "0", "/~1", "", '1', '~'),
            new BudgetShape("json-pointer-uri-fragment", "escaped run", "#", "/%7E0", "", '0', '~'),
            new BudgetShape("json-pointer-uri-fragment", "unescaped run", "#", "/a", "", 'a', '~'));

    static Stream<Named<Executable>> reusedFormatsStayWithinBudgetAtTheDefaultLimitsRows() {
        List<Named<Executable>> rows = new ArrayList<>();
        for (BudgetShape shape : BUDGET_SHAPES) {
            for (int length : List.of(BODY_MAX, HEADER_MAX)) {
                String lengthLabel = length == BODY_MAX ? "BODY_MAX" : "HEADER_MAX";
                rows.add(budgetRow(shape, length, lengthLabel, true));
                rows.add(budgetRow(shape, length, lengthLabel, false));
            }
        }
        return rows.stream();
    }

    private static Named<Executable> budgetRow(BudgetShape shape, int length, String lengthLabel, boolean valid) {
        String label =
                "(" + shape.format() + ", " + shape.label() + ", " + lengthLabel + ") " + (valid ? "valid" : "invalid");
        return Named.of(label, () -> assertBudgetRow(shape, length, valid));
    }

    /**
     * Asserts one TP-007 row: the constructed value's own C-FORMAT verdict matches the row's declared
     * shape (a sanity check on the construction itself); the gate handler answers it twice (warm-up, timed)
     * on the 1 MB-stack thread with no throwable and that verdict, through the body for a {@link
     * #BODY_MAX} row and through a header parameter for a {@link #HEADER_MAX} row (R-003); and the format
     * validator's direct, already-renamed judgement of the value, measured on its own 1 MB-stack thread,
     * also gives that verdict within the pre-decided time and allocation budget.
     *
     * @param shape  the construction
     * @param length the exact target length ({@link #BODY_MAX} or {@link #HEADER_MAX})
     * @param valid  whether this row is the shape's valid construction or its invalid twin
     */
    private static void assertBudgetRow(BudgetShape shape, int length, boolean valid) {
        String value = valid ? shape.valid(length) : shape.invalid(length);
        assertEquals(
                length,
                value.length(),
                () -> "(" + shape.format() + ", " + shape.label() + ") the construction must be exactly " + length
                        + " characters");
        boolean cFormat = cFormatVerdict(shape.format(), value);
        assertEquals(
                valid,
                cFormat,
                () -> "(" + shape.format() + ", " + shape.label() + ") the Given: the construction's declared"
                        + " shape must match C-FORMAT's independently computed verdict");

        boolean header = length == HEADER_MAX;
        Handler<RoutingContext> gate = header
                ? headerGate(defaults(), BUDGET_HEADER_NAME, formatString(shape.format()))
                : bodyGate(defaults(), formatString(shape.format()));
        for (int pass = 0; pass < 2; pass++) {
            int passNumber = pass;
            RoutingContext ctx = header
                    ? new GateRequest()
                            .parameter(ParamLocation.HEADER, BUDGET_HEADER_NAME, value)
                            .context()
                    : new GateRequest().body(value).context();
            SmallStackResult<GateOutcome> outcome =
                    onSmallStack(BUDGET_TIMEOUT, () -> handleOnCallingThread(gate, ctx));
            assertNull(
                    outcome.uncaught(),
                    () -> "(" + shape.format() + ", " + shape.label() + ") pass " + passNumber
                            + ": no throwable on the 1 MB-stack thread: " + outcome.uncaught());
            if (valid) {
                outcome.value().assertAccepted("(" + shape.format() + ", " + shape.label() + ") pass " + passNumber);
            } else {
                outcome.value().rejection();
            }
        }

        assertAllocationMeasurementEnabled();
        String renamedFormat = "x-vertique-format-" + shape.format();
        SmallStackResult<DirectMeasurement> measured =
                onSmallStack(BUDGET_TIMEOUT, () -> directMeasurement(renamedFormat, value));
        assertNull(
                measured.uncaught(),
                () -> "(" + shape.format() + ", " + shape.label() + ") no throwable from the direct call: "
                        + measured.uncaught());
        DirectMeasurement measurement = measured.value();
        long allowedAllocation = BUDGET_ALLOCATION_PER_CHAR * value.length() + BUDGET_ALLOCATION_FIXED;
        printBudgetRowFigures(shape, length, valid, value.length(), measurement, allowedAllocation);
        assertAll(
                "(" + shape.format() + ", " + shape.label() + ") the direct call",
                () -> assertEquals(valid, measurement.valid(), "the direct result must equal C-FORMAT's verdict"),
                () -> assertTrue(
                        measurement.elapsedNanos() <= BUDGET_ELAPSED_NANOS,
                        () -> "elapsed " + measurement.elapsedNanos() + "ns must be at most " + BUDGET_ELAPSED_NANOS
                                + "ns"),
                () -> assertTrue(
                        measurement.allocatedBytes() <= allowedAllocation,
                        () -> "allocated " + measurement.allocatedBytes() + " bytes must be at most "
                                + allowedAllocation + " bytes"));
    }

    /**
     * Prints one TP-007 row's per-row figures to standard output, after its timed run and before the
     * assertions that judge it, so the line appears whether the row passes or fails (the contract's
     * evidence requirement). Never prints the value itself (E9's value-free rule), only its length.
     *
     * @param shape             the construction
     * @param length            the exact target length ({@link #BODY_MAX} or {@link #HEADER_MAX})
     * @param valid             whether this row is the shape's valid construction or its invalid twin
     * @param valueLength       the constructed value's length, equal to {@code length}
     * @param measurement       the timed direct call's result
     * @param allowedAllocation the row's allocation budget in bytes
     */
    private static void printBudgetRowFigures(
            BudgetShape shape,
            int length,
            boolean valid,
            int valueLength,
            DirectMeasurement measurement,
            long allowedAllocation) {
        String lengthLabel = length == BODY_MAX ? "BODY_MAX" : "HEADER_MAX";
        System.out.printf(
                "TP-007 | %s | %s | %s | %s | length=%d | elapsedMs=%.3f | allocatedBytes=%d | budgetBytes=%d |"
                        + " verdict=%s%n",
                shape.format(),
                shape.label(),
                lengthLabel,
                valid ? "valid" : "invalid",
                valueLength,
                measurement.elapsedNanos() / 1_000_000.0,
                measurement.allocatedBytes(),
                allowedAllocation,
                measurement.valid() ? "valid" : "invalid");
    }

    /**
     * Asserts that per-thread allocation measurement is supported and enabled on this JVM (E10); TP-007's
     * allocation budget is meaningless otherwise.
     */
    private static void assertAllocationMeasurementEnabled() {
        ThreadMXBean bean = threadMxBean();
        assertTrue(
                bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled(),
                "per-thread allocation measurement must be supported and enabled on this JVM");
    }

    /**
     * One untimed warm-up judgement of {@code value} at {@code renamedFormat} via {@link
     * PatternInputGuard#validateFormat(String, String, Object)}, then the timed run whose elapsed time and
     * allocated bytes are measured on this call's own thread (PK5-002, E10).
     *
     * @param renamedFormat the already-renamed format name
     * @param value         the already-decoded string value
     * @return the timed run's verdict, elapsed nanoseconds, and allocated bytes
     */
    private static DirectMeasurement directMeasurement(String renamedFormat, String value) {
        PatternInputGuard guard = new PatternInputGuard(MAX_CHARS, MAX_TOTAL_CHARS);
        guard.validateFormat("string", renamedFormat, value); // untimed warm-up

        ThreadMXBean bean = threadMxBean();
        long allocatedBefore = bean.getCurrentThreadAllocatedBytes();
        long start = System.nanoTime();
        String message = guard.validateFormat("string", renamedFormat, value);
        long elapsed = System.nanoTime() - start;
        long allocated = bean.getCurrentThreadAllocatedBytes() - allocatedBefore;
        return new DirectMeasurement(message == null, elapsed, allocated);
    }

    private static ThreadMXBean threadMxBean() {
        return (ThreadMXBean) ManagementFactory.getThreadMXBean();
    }

    /**
     * The timed direct call's result.
     *
     * @param valid          whether {@link PatternInputGuard#validateFormat(String, String, Object)}
     *                       returned {@code null} (valid)
     * @param elapsedNanos   the timed run's elapsed nanoseconds
     * @param allocatedBytes the timed run's allocated bytes on its own thread
     */
    private record DirectMeasurement(boolean valid, long elapsedNanos, long allocatedBytes) {}

    // =========================================================================================
    // Shared verdict computation (E4, E5): C-FORMAT's frozen table, applied directly with java.net.URI
    // and the single-pass and scanner rules it states, never through the new production classes.
    // =========================================================================================

    /**
     * Dispatches to the per-format C-FORMAT verdict function.
     *
     * @param format the format
     * @param value  the value
     * @return C-FORMAT's verdict
     */
    private static boolean cFormatVerdict(String format, String value) {
        return switch (format) {
            case "uri" -> cFormatUri(value);
            case "uri-reference" -> cFormatUriReference(value);
            case "url" -> cFormatUrl(value);
            case "uri-template" -> cFormatUriTemplate(value);
            case "json-pointer" -> cFormatJsonPointer(value);
            case "relative-json-pointer" -> cFormatRelativeJsonPointer(value);
            case "json-pointer-uri-fragment" -> cFormatJsonPointerUriFragment(value);
            default -> throw new IllegalArgumentException("not one of C-FORMAT's seven formats: " + format);
        };
    }

    /**
     * {@code uri}: {@link URI} parse, {@code isAbsolute()}, ASCII-only (C-FORMAT's frozen table).
     *
     * @param value the value
     * @return C-FORMAT's verdict
     */
    private static boolean cFormatUri(String value) {
        if (!isAscii(value)) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException notAUri) {
            return false;
        }
        return uri.isAbsolute();
    }

    /**
     * {@code uri-reference}: {@link URI} parse, ASCII-only (C-FORMAT's frozen table).
     *
     * @param value the value
     * @return C-FORMAT's verdict
     */
    private static boolean cFormatUriReference(String value) {
        if (!isAscii(value)) {
            return false;
        }
        try {
            new URI(value);
        } catch (URISyntaxException notAUriReference) {
            return false;
        }
        return true;
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code url}: {@link URI} absolute, then the post-parse scheme/host/port filter C-FORMAT's frozen
     * table states, matching {@code UrlFormatFilterCorpusTest#cFormatVerdict(String)} (TP-004).
     *
     * @param value the value
     * @return C-FORMAT's verdict
     */
    private static boolean cFormatUrl(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException notAUri) {
            return false;
        }
        if (!uri.isAbsolute()) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http")
                        || scheme.equalsIgnoreCase("https")
                        || scheme.equalsIgnoreCase("ftp"))) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty() || host.startsWith("[")) {
            return false;
        }
        if (!portDigitsOk(uri, host)) {
            return false;
        }
        String trimmedHost = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        String[] labels = trimmedHost.split("\\.", -1);
        if (labels.length == 4 && Arrays.stream(labels).allMatch(FormatCheckReuseTest::isOctetLabel)) {
            return !isRejectedDottedQuad(labels);
        }
        int lastDot = trimmedHost.lastIndexOf('.');
        if (lastDot < 0) {
            return false;
        }
        String lastLabel = trimmedHost.substring(lastDot + 1);
        return lastLabel.length() >= 2 && lastLabel.chars().allMatch(Character::isLetter);
    }

    /**
     * Returns whether {@code label} is a dotted-quad octet under C-FORMAT's rule as refined by review
     * round 1 finding R-001 (ruling E23): all ASCII digits, and, when longer than one character, not
     * starting with {@code 0} (mirrors {@code UrlFormatFilterCorpusTest#isOctetLabel}, TP-004's oracle; a
     * label like {@code 08} or {@code 0000000008} is not an octet, so the engine's octal or decimal
     * reading of it can never widen the filtering the four-octet test performs).
     *
     * @param label one dot-separated host label
     * @return whether it is an octet
     */
    private static boolean isOctetLabel(String label) {
        return isDigits(label) && !(label.length() > 1 && label.charAt(0) == '0');
    }

    private static boolean portDigitsOk(URI uri, String host) {
        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority == null) {
            return true;
        }
        String userInfo = uri.getRawUserInfo();
        String afterUserInfo = userInfo == null ? rawAuthority : rawAuthority.substring(userInfo.length() + 1);
        if (afterUserInfo.length() <= host.length()) {
            return true;
        }
        String remainder = afterUserInfo.substring(host.length());
        if (!remainder.startsWith(":")) {
            return false;
        }
        String portDigits = remainder.substring(1);
        return portDigits.length() >= 2 && portDigits.length() <= 5 && isDigits(portDigits);
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isRejectedDottedQuad(String[] labels) {
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            octets[i] = Integer.parseInt(labels[i]);
        }
        return octets[0] == 0
                || octets[0] == 10
                || octets[0] == 127
                || (octets[0] == 169 && octets[1] == 254)
                || (octets[0] == 192 && octets[1] == 168)
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || octets[0] >= 224;
    }

    /**
     * {@code json-pointer}: empty, or a leading {@code /}, then one iterative pass in which every {@code ~}
     * is followed by {@code 0} or {@code 1} (C-FORMAT's frozen table; no {@code JsonPointer.compile}).
     *
     * @param value the value
     * @return C-FORMAT's verdict
     */
    private static boolean cFormatJsonPointer(String value) {
        if (value.isEmpty()) {
            return true;
        }
        if (value.charAt(0) != '/') {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '~'
                    && (i + 1 >= value.length() || (value.charAt(i + 1) != '0' && value.charAt(i + 1) != '1'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code relative-json-pointer}: prefix {@code 0} or a digit string without a leading zero (one
     * iterative pass, ASCII digits only), then {@code #} alone or the {@code json-pointer} check on the
     * remainder (C-FORMAT's frozen table).
     *
     * @param value the value
     * @return C-FORMAT's verdict
     */
    private static boolean cFormatRelativeJsonPointer(String value) {
        int i = 0;
        int len = value.length();
        if (i >= len || !isAsciiDigit(value.charAt(i))) {
            return false;
        }
        if (value.charAt(i) == '0') {
            i++;
        } else {
            while (i < len && isAsciiDigit(value.charAt(i))) {
                i++;
            }
        }
        String remainder = value.substring(i);
        return remainder.equals("#") || cFormatJsonPointer(remainder);
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * {@code json-pointer-uri-fragment}: leading {@code #}, percent-decoding through {@link URI}'s
     * fragment, then the {@code json-pointer} check (C-FORMAT's frozen table).
     *
     * @param value the value
     * @return C-FORMAT's verdict
     */
    private static boolean cFormatJsonPointerUriFragment(String value) {
        if (value.isEmpty() || value.charAt(0) != '#') {
            return false;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException notAFragment) {
            return false;
        }
        String fragment = uri.getFragment();
        return fragment != null && cFormatJsonPointer(fragment);
    }

    /**
     * {@code uri-template}: the framework's own single-forward-pass RFC 6570 §2 template-syntax scanner
     * (C-FORMAT's frozen table, F1), derived by hand from the grammar (E14): literals (§2.1, percent-encoded
     * triplets checked in place, {@code ucschar}/{@code iprivate} ranges for non-ASCII), and expressions
     * (§2.2–§2.4: {@code {}, an optional operator (op-level2, op-level3, or op-reserve), a comma-separated
     * variable list of varnames each with an optional {@code :} prefix modifier of 1 to 9999 or an
     * {@code *} explode, {@code }}); one forward pass, constant state, no recursion.
     *
     * @param value the value
     * @return C-FORMAT's verdict
     */
    private static boolean cFormatUriTemplate(String value) {
        int i = 0;
        int len = value.length();
        while (i < len) {
            char c = value.charAt(i);
            if (c == '{') {
                int close = scanTemplateExpression(value, i);
                if (close < 0) {
                    return false;
                }
                i = close + 1;
            } else if (c == '}') {
                return false;
            } else {
                int next = scanTemplateLiteralChar(value, i);
                if (next < 0) {
                    return false;
                }
                i = next;
            }
        }
        return true;
    }

    private static int scanTemplateLiteralChar(String value, int i) {
        char c = value.charAt(i);
        if (c == '%') {
            if (i + 2 < value.length() && isHexDigit(value.charAt(i + 1)) && isHexDigit(value.charAt(i + 2))) {
                return i + 3;
            }
            return -1;
        }
        if (Character.isHighSurrogate(c)) {
            if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                int codePoint = Character.toCodePoint(c, value.charAt(i + 1));
                return isUcscharOrIprivate(codePoint) ? i + 2 : -1;
            }
            return -1;
        }
        if (Character.isLowSurrogate(c)) {
            return -1;
        }
        if (isTemplateLiteralDisallowed(c)) {
            return -1;
        }
        return i + 1;
    }

    private static boolean isTemplateLiteralDisallowed(char c) {
        if (c <= 0x20 || c == 0x7f) {
            return true;
        }
        if (c > 0x7e) {
            return !isUcscharOrIprivate(c);
        }
        return "\"%<>\\^`{|}".indexOf(c) >= 0;
    }

    /**
     * Whether {@code codePoint} is in RFC 3987's {@code ucschar} or {@code iprivate} range. Review round 1
     * finding R-005: RFC 3987 §2.2's {@code ucschar} production reads {@code %xE1000-EFFFD} for this band,
     * not {@code %xE0000-EFFFD}; the lower 4,096 code points ({@code U+E0000}–{@code U+E0FFF}) are outside
     * {@code ucschar}, so, absent a matching {@code iprivate} band there, a code point in that range (for
     * example {@code U+E0001}) must be rejected.
     *
     * @param codePoint the code point
     * @return whether it is {@code ucschar} or {@code iprivate}
     */
    private static boolean isUcscharOrIprivate(int codePoint) {
        return (codePoint >= 0xA0 && codePoint <= 0xD7FF)
                || (codePoint >= 0xF900 && codePoint <= 0xFDCF)
                || (codePoint >= 0xFDF0 && codePoint <= 0xFFEF)
                || (codePoint >= 0x10000 && codePoint <= 0x1FFFD)
                || (codePoint >= 0x20000 && codePoint <= 0x2FFFD)
                || (codePoint >= 0x30000 && codePoint <= 0x3FFFD)
                || (codePoint >= 0x40000 && codePoint <= 0x4FFFD)
                || (codePoint >= 0x50000 && codePoint <= 0x5FFFD)
                || (codePoint >= 0x60000 && codePoint <= 0x6FFFD)
                || (codePoint >= 0x70000 && codePoint <= 0x7FFFD)
                || (codePoint >= 0x80000 && codePoint <= 0x8FFFD)
                || (codePoint >= 0x90000 && codePoint <= 0x9FFFD)
                || (codePoint >= 0xA0000 && codePoint <= 0xAFFFD)
                || (codePoint >= 0xB0000 && codePoint <= 0xBFFFD)
                || (codePoint >= 0xC0000 && codePoint <= 0xCFFFD)
                || (codePoint >= 0xD0000 && codePoint <= 0xDFFFD)
                || (codePoint >= 0xE1000 && codePoint <= 0xEFFFD)
                || (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * Scans {@code { operator? varlist }} starting at {@code {}; the operator set is op-level2, op-level3,
     * and op-reserve (E14: {@code + # . / ; ? &} and {@code = , ! @ |}).
     *
     * @param value the value
     * @param start the index of {@code {}
     * @return the index of the matching {@code }}, or -1
     */
    private static int scanTemplateExpression(String value, int start) {
        int i = start + 1;
        int len = value.length();
        if (i < len && isTemplateOperator(value.charAt(i))) {
            i++;
        }
        boolean sawVarspec = false;
        while (true) {
            int afterVar = scanTemplateVarspec(value, i);
            if (afterVar < 0) {
                return -1;
            }
            sawVarspec = true;
            i = afterVar;
            if (i < len && value.charAt(i) == ',') {
                i++;
                continue;
            }
            break;
        }
        if (!sawVarspec) {
            return -1;
        }
        return i < len && value.charAt(i) == '}' ? i : -1;
    }

    private static boolean isTemplateOperator(char c) {
        return "+#./;?&=,!@|".indexOf(c) >= 0;
    }

    private static int scanTemplateVarspec(String value, int start) {
        int i = start;
        int len = value.length();
        int varcharCount = 0;
        while (i < len) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 < len && isHexDigit(value.charAt(i + 1)) && isHexDigit(value.charAt(i + 2))) {
                    i += 3;
                    varcharCount++;
                    continue;
                }
                return -1;
            }
            if (isTemplateVarchar(c)) {
                i++;
                varcharCount++;
                continue;
            }
            if (c == '.'
                    && varcharCount > 0
                    && i + 1 < len
                    && (isTemplateVarchar(value.charAt(i + 1)) || value.charAt(i + 1) == '%')) {
                i++;
                continue;
            }
            break;
        }
        if (varcharCount == 0) {
            return -1;
        }
        if (i < len && value.charAt(i) == ':') {
            i++;
            int digitStart = i;
            int digits = 0;
            while (i < len && isAsciiDigit(value.charAt(i)) && digits < 4) {
                i++;
                digits++;
            }
            if (digits == 0) {
                return -1;
            }
            String prefix = value.substring(digitStart, i);
            return prefix.charAt(0) == '0' ? -1 : i;
        }
        if (i < len && value.charAt(i) == '*') {
            return i + 1;
        }
        return i;
    }

    private static boolean isTemplateVarchar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    // =========================================================================================
    // Gate and engine helpers (E4): "the gate's compiled validator" and "the engine's verdict"
    // =========================================================================================

    /**
     * "The gate's compiled validator"'s verdict for {@code format} on {@code instance} (E4).
     *
     * @param format   the format
     * @param instance the instance
     * @return the gate's verdict
     */
    private static boolean gateVerdict(String format, Object instance) {
        Validator validator =
                new PatternInputGuard(MAX_CHARS, MAX_TOTAL_CHARS).compile(schemaFor(format, instance), GATE_OPTIONS);
        return Boolean.TRUE.equals(validator.validate(instance).getValid());
    }

    /**
     * "The engine's verdict" for {@code format} on {@code instance}: the unguarded compilation, with no
     * custom format validator (E4).
     *
     * @param format   the format
     * @param instance the instance
     * @return the engine's verdict
     */
    private static boolean engineVerdict(String format, Object instance) {
        Validator validator = Validator.create(JsonSchema.of(schemaFor(format, instance)), GATE_OPTIONS);
        return Boolean.TRUE.equals(validator.validate(instance).getValid());
    }

    /**
     * Returns {@code {"type": "string", "format": F}} for a string instance, or {@code {"format": F}} for
     * a non-string one (E8): the suite's own non-string schema, under which every instance is valid.
     *
     * @param format   the format
     * @param instance the instance
     * @return the schema
     */
    private static JsonObject schemaFor(String format, Object instance) {
        return instance instanceof String ? formatString(format) : new JsonObject().put("format", format);
    }

    /**
     * Returns {@code {"type": "string", "format": F}}.
     *
     * @param format the format
     * @return the schema
     */
    private static JsonObject formatString(String format) {
        return new JsonObject().put("type", "string").put("format", format);
    }

    // =========================================================================================
    // Suite-file reading (SUITE)
    // =========================================================================================

    /**
     * One JSON-Schema-Test-Suite case.
     *
     * @param description the case's description
     * @param data        the case's data, decoded from JSON (a string, number, boolean, {@code null},
     *                    {@link JsonObject}, or {@link JsonArray})
     * @param valid       the suite's verdict
     */
    private record SuiteCase(String description, Object data, boolean valid) {}

    /**
     * Reads every test case of a copied suite file.
     *
     * @param file the file name under {@code SUITE}
     * @return the cases, in file order
     */
    private static List<SuiteCase> readSuiteCases(String file) {
        String resource = "/json-schema-test-suite/draft2020-12/optional/format/" + file;
        JsonArray groups;
        try (InputStream in = FormatCheckReuseTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing suite file " + resource);
            }
            groups = new JsonArray(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
        List<SuiteCase> cases = new ArrayList<>();
        for (Object groupObj : groups) {
            JsonObject group = (JsonObject) groupObj;
            for (Object testObj : group.getJsonArray("tests")) {
                JsonObject test = (JsonObject) testObj;
                cases.add(
                        new SuiteCase(test.getString("description"), test.getValue("data"), test.getBoolean("valid")));
            }
        }
        return cases;
    }

    /**
     * Renders a value for a row label, abbreviated past 60 characters.
     *
     * @param value the value
     * @return the label
     */
    private static String jsonLabel(Object value) {
        String text = value instanceof String s ? s : String.valueOf(value);
        return text.length() > 60 ? "\"" + text.substring(0, 60) + "...(" + text.length() + ")\"" : "\"" + text + "\"";
    }

    // =========================================================================================
    // Small-stack helper (E9's "onSmallStack"), copied in shape from T004's PatternInputGuardTest
    // =========================================================================================

    /**
     * Runs {@code body} on a new thread created with a 1 MB stack, inside {@code timeout}, and captures the
     * thread's uncaught throwable.
     *
     * @param timeout the preemptive timeout
     * @param body    the work
     * @param <T>     the result type
     * @return the result, or the uncaught throwable
     */
    private static <T> SmallStackResult<T> onSmallStack(Duration timeout, Supplier<T> body) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread thread = new Thread(null, () -> result.set(body.get()), "t021-1mb-stack", ONE_MEGABYTE_STACK);
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((failed, throwable) -> uncaught.set(throwable));
        assertTimeoutPreemptively(timeout, () -> {
            thread.start();
            thread.join();
        });
        return new SmallStackResult<>(result.get(), uncaught.get());
    }

    /**
     * What a small-stack run ended with.
     *
     * @param value    the result, or {@code null} when the thread ended abruptly
     * @param uncaught the thread's uncaught throwable, or {@code null} when it completed normally
     * @param <T>      the result type
     */
    private record SmallStackResult<T>(T value, Throwable uncaught) {}

    /**
     * Runs {@code gate.handle(ctx)} then reads its outcome, for use as an {@link #onSmallStack} body.
     *
     * @param gate the gate
     * @param ctx  the request's routing context
     * @return the outcome
     */
    private static GateOutcome handleOnCallingThread(Handler<RoutingContext> gate, RoutingContext ctx) {
        gate.handle(ctx);
        return outcomeOf(ctx);
    }

    // =========================================================================================
    // Minimal gate/mock builders, copied from T004's PatternInputGuardTest (E5)
    // =========================================================================================

    private static JaxRsConfig defaults() {
        return JaxRsConfig.builder().build();
    }

    private static Handler<RoutingContext> gate(
            JaxRsConfig config,
            JaxRsOperationDescriptor operation,
            OperationSchemas schemas,
            Set<FileContentVerifier> verifiers) {
        return new WebValidationStrategy(config, ConversionContexts.defaultResolver(), verifiers)
                .gateFor(operation, schemas)
                .orElseThrow();
    }

    private static Handler<RoutingContext> bodyGate(JaxRsConfig config, JsonObject bodySchema) {
        return gate(
                config,
                operation(List.of(), true, List.of()),
                OperationSchemas.builder().bodySchema(bodySchema).build(),
                Set.of());
    }

    /**
     * Builds the gate for an operation with one header parameter (R-003, TP-007's {@link #HEADER_MAX}
     * rows: the contract's Given routes them through a header parameter, not the body), copied in shape
     * from T004's {@code PatternInputGuardTest#overLongParameter}.
     *
     * @param config       the configuration
     * @param name         the header's name
     * @param headerSchema the header parameter's schema
     * @return the gate handler
     */
    private static Handler<RoutingContext> headerGate(JaxRsConfig config, String name, JsonObject headerSchema) {
        return gate(
                config,
                operation(List.of(param(name, ParamLocation.HEADER, String.class, null)), false, List.of()),
                OperationSchemas.builder()
                        .parameterSchema(ParamLocation.HEADER, name, headerSchema)
                        .build(),
                Set.of());
    }

    private static JaxRsOperationDescriptor operation(
            List<ParamDescriptor> parameters, boolean body, List<FilePartDescriptor> fileParts) {
        StubDescriptors.Builder builder = StubDescriptors.builder()
                .operationId(OPERATION_ID)
                .httpMethod("POST")
                .routeTemplate("/format-check-reuse")
                .parameters(parameters)
                .fileParts(fileParts);
        if (body) {
            builder.body(new BodyDescriptor(Object.class, null, List.of()));
        }
        return builder.build();
    }

    private static ParamDescriptor param(String name, ParamLocation location, Class<?> type, Class<?> componentType) {
        return new ParamDescriptor(name, location, type, componentType, null, null, List.of());
    }

    /**
     * Handles one request on the calling thread inside a preemptive timeout, for TP-001's oracle
     * computation (short values only, no overflow risk).
     *
     * @param gate    the gate
     * @param ctx     the request's routing context
     * @param timeout the timeout
     * @return the outcome
     */
    private static GateOutcome handle(Handler<RoutingContext> gate, RoutingContext ctx, Duration timeout) {
        assertTimeoutPreemptively(timeout, () -> gate.handle(ctx));
        return outcomeOf(ctx);
    }

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

    private static void assertValueFree(List<ValidationErrorDetail> details, String... fragments) {
        for (ValidationErrorDetail detail : details) {
            String text = String.valueOf(detail);
            for (String fragment : fragments) {
                assertFalse(
                        text.contains(fragment), () -> "no detail may contain '" + fragment + "': " + abbreviate(text));
            }
        }
    }

    private static List<String> types(List<ValidationErrorDetail> details) {
        return details.stream().map(d -> d.type() + "@" + abbreviate(d.path())).toList();
    }

    private static String abbreviate(String text) {
        return text == null || text.length() <= 80 ? text : text.substring(0, 40) + "...(" + text.length() + ")";
    }

    private static String withSpaceAt(String value, int index) {
        return value.substring(0, index) + " " + value.substring(index + 1);
    }

    private static RequestBody requestBody(io.vertx.core.buffer.Buffer raw) {
        RequestBody body = mock(RequestBody.class);
        when(body.buffer()).thenReturn(raw);
        when(body.asJsonObject())
                .thenAnswer(invocation -> Json.decodeValue(raw) instanceof JsonObject object ? object : null);
        when(body.asJsonArray())
                .thenAnswer(invocation -> Json.decodeValue(raw) instanceof JsonArray array ? array : null);
        return body;
    }

    /**
     * How the gate answered one request.
     *
     * @param nextCalls how often it called {@code next()}
     * @param failures  what it passed to {@code fail(...)}
     */
    private record GateOutcome(int nextCalls, List<Throwable> failures) {

        void assertAccepted(String what) {
            assertAll(
                    what + " must be accepted",
                    () -> assertEquals(
                            List.of(),
                            failures.stream()
                                    .map(FormatCheckReuseTest::describe)
                                    .toList(),
                            "no failure"),
                    () -> assertEquals(1, nextCalls, "one next() call"));
        }

        List<ValidationErrorDetail> rejection() {
            assertEquals(0, nextCalls, "a rejected request must never reach the next handler");
            assertEquals(
                    1,
                    failures.size(),
                    () -> "the request must fail exactly once: "
                            + failures.stream()
                                    .map(FormatCheckReuseTest::describe)
                                    .toList());
            return org.junit.jupiter.api.Assertions.assertInstanceOf(
                            RestValidationException.class,
                            failures.get(0),
                            () -> "the failure must be a 400 RestValidationException: " + describe(failures.get(0)))
                    .errors();
        }
    }

    private static String describe(Throwable failure) {
        return failure instanceof RestValidationException validation
                ? "RestValidationException" + types(validation.errors())
                : failure.getClass().getName();
    }

    /** A mocked request: parameters at every location, a JSON body. */
    private static final class GateRequest {

        private final Map<String, String> path = new HashMap<>();
        private final MultiMap query = MultiMap.caseInsensitiveMultiMap();
        private final MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        private final MultiMap form = MultiMap.caseInsensitiveMultiMap();
        private final Set<Cookie> cookies = new HashSet<>();
        private Object body;

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

        GateRequest body(Object json) {
            this.body = json;
            return this;
        }

        RoutingContext context() {
            RoutingContext ctx = mock(RoutingContext.class);
            HttpServerRequest request = mock(HttpServerRequest.class);
            Map<String, Object> data = new HashMap<>();
            RequestBody requestBody = body == null ? null : requestBody(Json.encodeToBuffer(body));
            when(ctx.request()).thenReturn(request);
            when(ctx.pathParams()).thenReturn(path);
            when(ctx.queryParams()).thenReturn(query);
            when(ctx.fileUploads()).thenReturn(List.of());
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
}
