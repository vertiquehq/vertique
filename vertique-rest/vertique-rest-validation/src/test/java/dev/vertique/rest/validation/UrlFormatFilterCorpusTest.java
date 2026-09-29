// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.Validator;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proof for the {@code web-validation} gate's {@code url} format check (rest-022 T021, TP-004, FR-020):
 * on a corpus of at most 512-character values taken from the vertx-json-schema 5.1.6 {@code url}
 * expression, the gate's verdict equals the engine's on every filtering-class row (the (a) rows, grouped
 * by class below: schemes, dotted quads, IPv6 literals, non-dotted and malformed hosts, ports, and
 * userinfo, plus {@code http://example.com:/}, which the engine rejects too, E15), and equals C-FORMAT's
 * frozen rule on every other row (the (b) rows), whose difference from the engine's verdict is exactly
 * {@link #RECORDED_DIFFERENCES}.
 *
 * <p>"The gate's compiled validator" is {@code new PatternInputGuard(4096, 262_144).compile(schema,
 * GATE_OPTIONS)} (E4); {@link #GATE_OPTIONS} is copied literally from {@code
 * WebValidationStrategy.SCHEMA_OPTIONS}. "The engine's verdict" is {@code
 * Validator.create(JsonSchema.of(schema.copy()), GATE_OPTIONS)}, the unguarded compilation, with no
 * custom format validator, so vertx-json-schema's own {@code url} regular expression decides. C-FORMAT's
 * {@code url} rule (plan C-FORMAT; coordinator rulings, round B; S3-008) is applied directly with {@link
 * URI} in {@link #cFormatVerdict(String)}, never through a new production class (E5): an absolute URI
 * whose scheme is {@code http}, {@code https}, or {@code ftp} (case-insensitively); whose host is
 * present and is not an IPv6 literal; whose host, after one trailing dot is removed, is either a dotted
 * quad outside the rejected private and reserved bands ({@code 0/8}, {@code 10/8}, {@code 127/8}, {@code
 * 169.254/16}, {@code 192.168/16}, {@code 172.16/12}, {@code 224/4} and above) or ends in an alphabetic
 * label of at least two characters; and whose port, when the raw authority carries one after the host,
 * has 2 to 5 digits (an empty port fails).
 *
 * <p>At this task's baseline the gate still delegates {@code url} to the engine's own expression (T021's
 * rename has not landed), so every (a) row is green — the gate's verdict equals the engine's trivially —
 * and every (b) row on {@link #RECORDED_DIFFERENCES} is red, since the gate still gives the engine's
 * verdict there rather than C-FORMAT's; the remaining (b) row, where C-FORMAT and the engine already
 * agree, is green too.
 *
 * <p>Review round 1 finding R-001 (ruling E23, 2026-09-28): a dotted-quad octet label longer than one
 * character that starts with {@code 0} is not an octet under C-FORMAT's refined rule, so the host fails
 * the dotted-quad test and, having no alphabetic last label, is rejected — the engine reads such a label
 * as octal (WHATWG URL, glibc {@code inet_aton}), which would let {@code 0177.0.0.1} address the loopback
 * range the filtering promises to reject. Six such hosts join the (a) rows below, all rejected by the
 * engine's regular expression too; {@code http://8.08.8.8/}, which the engine's expression accepts, joins
 * the (b) corpus and {@link #RECORDED_DIFFERENCES} as a documented stricter difference.
 */
class UrlFormatFilterCorpusTest {

    /**
     * The gate's compile options, copied literally from {@code WebValidationStrategy.SCHEMA_OPTIONS}
     * (E4), so this proof characterizes the engine exactly as the gate configures it.
     */
    private static final JsonSchemaOptions GATE_OPTIONS = new JsonSchemaOptions()
            .setDraft(Draft.DRAFT202012)
            .setBaseUri("https://vertique.local/")
            .setOutputFormat(OutputFormat.Basic);

    /** T004's default per-string limit; irrelevant here since every value is far shorter. */
    private static final int MAX_CHARS = 4096;

    /** T004's default per-request limit; irrelevant here since no window is opened. */
    private static final int MAX_TOTAL_CHARS = 262_144;

    /** The body schema every row is judged against. */
    private static final JsonObject URL_SCHEMA =
            new JsonObject().put("type", "string").put("format", "url");

    /** "The gate's compiled validator" (E4). */
    private static final Validator GATE_VALIDATOR =
            new PatternInputGuard(MAX_CHARS, MAX_TOTAL_CHARS).compile(URL_SCHEMA, GATE_OPTIONS);

    /** "The engine's verdict", the unguarded compilation with no custom format validator (E4). */
    private static final Validator ENGINE_VALIDATOR = Validator.create(JsonSchema.of(URL_SCHEMA.copy()), GATE_OPTIONS);

    /**
     * The (b) rows whose C-FORMAT verdict differs from the engine's 5.1.6 {@code url} expression, keyed
     * by input; derived at step 3 in a scratch harness and recorded in {@code evidence/T021.md} § TP-004.
     * The round-B ruling names four of these classes (non-ASCII hosts, since {@link URI} yields no host
     * for one; {@code --} inside a label; a last octet of {@code 0} or {@code 255}; and {@code ?} or
     * {@code #} directly after the host); the remaining three ({@code http://a.com./}, whose trailing dot
     * C-FORMAT strips and the engine does not, S3-008; a pipe and a malformed percent escape in the path,
     * which {@link URI} rejects and the engine's expression does not reach; and a double-quoted userinfo,
     * which {@link URI} also rejects) are recorded the same way. R-001 (E23) adds an eighth class:
     * {@code http://8.08.8.8/}, whose second octet label {@code 08} is longer than one character and
     * starts with {@code 0}; C-FORMAT no longer treats it as an octet (E23), so the host has no
     * four-octet reading and no alphabetic last label either, and is rejected, while the engine's
     * expression still reads {@code 08} as decimal {@code 8} and accepts it. Review round 2 finding
     * F-001 pins two more: an empty userinfo before {@code @} ({@code http://@example.com/}), which
     * {@link URI} parses to a userinfo-less host that C-FORMAT then accepts while the engine's expression
     * rejects it; and a trailing line feed after the path ({@code http://foo.bar/\n}), which the engine's
     * expression accepts (its {@code $} matches before a final line terminator) while {@link URI}'s
     * strict parse rejects it. Published in rest-validation's {@code module.md} once T021 lands (INV-8).
     */
    private static final Set<String> RECORDED_DIFFERENCES = Set.of(
            "http://bücher.de/",
            "http://例え.テスト/",
            "http://a--b.com/",
            "http://xn--bcher-kva.de/",
            "http://a.com./",
            "http://1.2.3.0/",
            "http://1.2.3.255/",
            "http://example.com?x=1",
            "http://example.com#f",
            "http://example.com/a|b",
            "http://example.com/%zz",
            "http://us\"er@example.com/",
            "http://8.08.8.8/",
            "http://@example.com/",
            "http://foo.bar/\n");

    @DisplayName("TP-004: url keeps the engine's filtering, and its other differences are recorded by input")
    @ParameterizedTest(name = "{0}")
    @MethodSource("urlKeepsTheEngineFilteringAndRecordsOtherDifferencesRows")
    void urlKeepsTheEngineFilteringAndRecordsOtherDifferences(Executable row) throws Throwable {
        row.execute();
    }

    /**
     * TP-004's rows: the (a) filtering-class rows, then the (b) rows outside them.
     *
     * @return the rows
     */
    static Stream<Named<Executable>> urlKeepsTheEngineFilteringAndRecordsOtherDifferencesRows() {
        return Stream.concat(filteringClassRows(), differenceCorpusRows());
    }

    // --- (a) filtering-class rows: the gate's verdict must equal the engine's, an oracle computed here,
    // --- never hand-written ---

    /**
     * The (a) rows, grouped by filtering class.
     *
     * @return the rows
     */
    private static Stream<Named<Executable>> filteringClassRows() {
        return Stream.of(
                        filteringClass(
                                "schemes",
                                List.of(
                                        "http://example.com/",
                                        "https://example.com/",
                                        "ftp://example.com/",
                                        "HTTP://EXAMPLE.COM/",
                                        "file:///etc/passwd",
                                        "jar:file:/a.jar!/x",
                                        "mailto:a@example.com",
                                        "gopher://example.com/")),
                        filteringClass(
                                "dotted quads",
                                List.of(
                                        "http://0.0.0.0/",
                                        "http://0.1.2.3/",
                                        "http://10.0.0.1/",
                                        "http://127.0.0.1/admin",
                                        "http://169.254.169.254/latest/meta-data/",
                                        "http://172.16.0.1/",
                                        "http://172.31.255.254/",
                                        "http://192.168.0.1/",
                                        "http://224.0.0.1/",
                                        "http://255.255.255.254/",
                                        "http://172.15.0.1/",
                                        "http://172.32.0.1/",
                                        "http://8.8.8.8/",
                                        "http://223.1.2.3/",
                                        "http://169.1.2.3/",
                                        "http://192.169.0.1/")),
                        filteringClass("ipv6 literals", List.of("http://[::1]/", "http://[2001:db8::1]/")),
                        filteringClass(
                                "non-dotted and malformed hosts",
                                List.of(
                                        "http://localhost/",
                                        "http://intranet/",
                                        "http://localhost./",
                                        "http://intranet./",
                                        "http://127.0.0.1./",
                                        "http://a.c/",
                                        "http://a.co1/",
                                        "http://-a.com/",
                                        "http://a-.com/",
                                        "http://123.com/",
                                        "https://a.b.example.co.uk/p",
                                        "http://example.com",
                                        "http://1.2.3.4.5.com/",
                                        "http://www.example.co.uk/",
                                        "http://example.az/",
                                        "http://EXAMPLE.AZ/")),
                        filteringClass(
                                "ports",
                                List.of(
                                        "http://example.com:8/",
                                        "http://example.com:80/",
                                        "http://example.com:99999/",
                                        "http://example.com:123456/",
                                        "http://example.com:/")),
                        filteringClass("userinfo", List.of("http://user@example.com/", "http://user:pw@example.com/")),
                        filteringClass(
                                "dotted quads with leading-zero octets (R-001, E23)",
                                List.of(
                                        "http://0177.0.0.1/",
                                        "http://012.0.0.1/",
                                        "http://01.2.3.4/",
                                        "http://1.2.3.08/",
                                        "http://8.010.8.8/",
                                        "http://0177.0.0.01/",
                                        "http://8.8.8.0000000008/")))
                .flatMap(s -> s);
    }

    /**
     * One filtering class's rows, each proving the gate's verdict equals the engine's.
     *
     * @param className the class name, used only in the row label
     * @param values    the class's values
     * @return the rows
     */
    private static Stream<Named<Executable>> filteringClass(String className, List<String> values) {
        return values.stream()
                .map(value -> Named.<Executable>of("(a-" + className + ") " + value, () -> assertFilteringRow(value)));
    }

    /**
     * Asserts that the gate's verdict for {@code value} equals the engine's, both computed at assertion
     * time.
     *
     * @param value the filtering-class row's value
     */
    private static void assertFilteringRow(String value) {
        assertEquals(
                verdict(ENGINE_VALIDATOR, value),
                verdict(GATE_VALIDATOR, value),
                () -> "the gate's url verdict must equal the engine's filtering verdict for " + value);
    }

    // --- (b) rows outside the filtering classes: the gate's verdict must equal C-FORMAT's, and the
    // --- difference from the engine's verdict must be exactly RECORDED_DIFFERENCES ---

    /**
     * The (b) rows.
     *
     * @return the rows
     */
    private static Stream<Named<Executable>> differenceCorpusRows() {
        return Stream.of(
                        "http://bücher.de/",
                        "http://例え.テスト/",
                        "http://a--b.com/",
                        "http://xn--bcher-kva.de/",
                        "http://a.com./",
                        "http://1.2.3.0/",
                        "http://1.2.3.255/",
                        "http://example.com?x=1",
                        "http://example.com#f",
                        "http://example.com/?x=1",
                        "http://example.com/a|b",
                        "http://example.com/%zz",
                        "http://us\"er@example.com/",
                        "http://8.08.8.8/",
                        "http://@example.com/",
                        "http://foo.bar/\n")
                .map(value -> Named.<Executable>of("(b) " + value, () -> assertDifferenceRow(value)));
    }

    /**
     * Asserts that the gate's verdict for {@code value} equals C-FORMAT's, and that {@code value} is on
     * {@link #RECORDED_DIFFERENCES} exactly when C-FORMAT's verdict differs from the engine's.
     *
     * @param value the (b) row's value
     */
    private static void assertDifferenceRow(String value) {
        boolean gate = verdict(GATE_VALIDATOR, value);
        boolean cFormat = cFormatVerdict(value);
        boolean engine = verdict(ENGINE_VALIDATOR, value);
        boolean expectedDifference = RECORDED_DIFFERENCES.contains(value);
        assertAll(
                () -> assertEquals(
                        cFormat, gate, () -> "the gate's url verdict must equal C-FORMAT's verdict for " + value),
                () -> assertEquals(
                        expectedDifference,
                        cFormat != engine,
                        () -> "the recorded-difference list must name exactly the rows where C-FORMAT's verdict"
                                + " differs from the engine's: " + value));
    }

    /**
     * Runs {@code validator} on {@code value} and returns its verdict.
     *
     * @param validator the validator
     * @param value     the value
     * @return the verdict
     */
    private static boolean verdict(Validator validator, String value) {
        return validator.validate(value).getValid();
    }

    /**
     * C-FORMAT's frozen {@code url} rule, applied directly with {@link URI}.
     *
     * @param value the candidate value
     * @return C-FORMAT's verdict
     */
    private static boolean cFormatVerdict(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
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
        if (labels.length == 4 && Arrays.stream(labels).allMatch(UrlFormatFilterCorpusTest::isOctetLabel)) {
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
     * Checks the port, present when the raw authority carries a {@code :} after the host: 2 to 5 digits,
     * an empty port failing.
     *
     * @param uri  the parsed URI
     * @param host the parsed host
     * @return whether the port, if any, is acceptable
     */
    private static boolean portDigitsOk(URI uri, String host) {
        String rawAuthority = uri.getRawAuthority();
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

    /**
     * Returns whether {@code value} is non-empty and every character is an ASCII digit.
     *
     * @param value the string
     * @return whether it is all digits
     */
    private static boolean isDigits(String value) {
        return !value.isEmpty() && value.chars().allMatch(Character::isDigit);
    }

    /**
     * Returns whether {@code label} is a dotted-quad octet under C-FORMAT's rule as refined by R-001
     * (ruling E23): all ASCII digits, and, when longer than one character, not starting with {@code 0}
     * (a label like {@code 08} or {@code 0177} is not an octet, so the engine's octal reading of it can
     * never widen the filtering the four-octet test performs).
     *
     * @param label one dot-separated host label
     * @return whether it is an octet
     */
    private static boolean isOctetLabel(String label) {
        return isDigits(label) && !(label.length() > 1 && label.charAt(0) == '0');
    }

    /**
     * Returns whether a four-label all-digit host falls in a rejected private or reserved band: {@code
     * 0/8}, {@code 10/8}, {@code 127/8}, {@code 169.254/16}, {@code 192.168/16}, {@code 172.16/12}, or
     * {@code 224/4} and above.
     *
     * @param labels the host's four all-digit labels
     * @return whether the host is rejected
     */
    private static boolean isRejectedDottedQuad(String[] labels) {
        int[] octets = new int[4];
        for (int index = 0; index < 4; index++) {
            octets[index] = Integer.parseInt(labels[index]);
        }
        return octets[0] == 0
                || octets[0] == 10
                || octets[0] == 127
                || (octets[0] == 169 && octets[1] == 254)
                || (octets[0] == 192 && octets[1] == 168)
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || octets[0] >= 224;
    }
}
