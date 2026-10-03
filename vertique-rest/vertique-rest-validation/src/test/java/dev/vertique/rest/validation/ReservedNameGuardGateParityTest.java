// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.schema.AnnotationJsonSchemaGenerator;
import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.swagger.v3.oas.annotations.media.Schema;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import jakarta.validation.constraints.Size;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/**
 * Pins the {@code web-validation} gate's reported violation lists for the reserved-name guard at the
 * schema-generation baseline: seventeen request bodies, each run once with the default aggregate
 * error collection and once with fail-fast error collection, through the same mocked-{@link
 * RoutingContext} harness {@link WebValidationGateTest} uses. A conforming body must call {@code
 * ctx.next()} and never {@code ctx.fail(...)}; a violating body must call {@code ctx.fail(...)} with a
 * {@link RestValidationException} whose {@link ValidationErrorDetail} list equals its pinned value,
 * entry for entry and in order. The proof exists so that a later change to how the guard's
 * case-insensitive shape is expressed cannot silently change what a client is told went wrong, how
 * many things it is told, or in what order.
 */
class ReservedNameGuardGateParityTest {

    // ---------------------------------------------------------------- shared fixtures

    /**
     * Case-insensitively bound, with a size-bounded published member and a hidden member that is
     * therefore reserved, and extras described through a method-level any-setter over an unannotated
     * private map. This class's own copy, kept local so this proof does not depend on another
     * module's test fixtures.
     */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class GateCaseInsensitiveHiddenDto {

        @Size(max = 3)
        public String name;

        @Schema(hidden = true)
        public String secret;

        private final Map<String, Object> extras = new LinkedHashMap<>();

        @JsonAnySetter
        private void putExtra(String key, Object value) {
            extras.put(key, value);
        }
    }

    /**
     * Case-sensitively bound, with a published member, a hidden member, and an ignored member — both
     * of the latter therefore reserved — and extras described through a method-level any-setter over an
     * unannotated private map. This class's own copy, for the same reason as {@link
     * GateCaseInsensitiveHiddenDto}.
     */
    static final class GateHiddenAndIgnoredDto {

        public String name;

        @Schema(hidden = true)
        public String secretField;

        @JsonIgnore
        public String ignoredField;

        private final Map<String, Object> extras = new LinkedHashMap<>();

        @JsonAnySetter
        private void putExtra(String key, Object value) {
            extras.put(key, value);
        }
    }

    // ---------------------------------------------------------------- awkward key constants

    /**
     * KELVIN SIGN, U+212A: folds to ASCII {@code 'k'} under Java's locale-independent case mapping, so
     * a key spelled with it is confusable for a {@code 'k'}-led word. Built from its code point, never
     * written as a literal character or a source escape: the project's code formatter rewrites either
     * back into a literal, largely invisible character.
     */
    private static final String KELVIN_SIGN = Character.toString(0x212A);

    /** LATIN SMALL LETTER A WITH MACRON, U+0101: a non-ASCII confusable for a plain {@code 'a'}. */
    private static final String LATIN_SMALL_A_WITH_MACRON = Character.toString(0x0101);

    /** LINE FEED, built from its code point for the same reason as {@link #KELVIN_SIGN}. */
    private static final String LINE_FEED = Character.toString(0x0A);

    // ---------------------------------------------------------------- pinned violation literals

    /**
     * The one violation a body carrying a reserved or non-ASCII property name reports, whichever of
     * the two the key is and whichever fixture it is checked against: the gate's own fixed, value-free
     * message for a {@code propertyNames} refusal, naming neither the offending key nor the pattern
     * that refused it.
     */
    private static final ValidationErrorDetail PROPERTY_NAME_REFUSED = new ValidationErrorDetail(
            "#",
            "contains a property name the schema does not allow",
            "body",
            "propertyNames",
            Map.of("propertyNames", true));

    /**
     * The gate's fixed, value-free message for a {@code patternProperties} match whose associated
     * schema the value then fails — reported before the concrete constraint detail below, since the
     * validator visits the {@code patternProperties} match first.
     */
    private static final ValidationErrorDetail PATTERN_PROPERTIES_VALUE_REJECTED = new ValidationErrorDetail(
            "#", "does not satisfy the schema", "body", "patternProperties", Map.of("patternProperties", true));

    /** The concrete {@code maxLength} detail for the published {@code name} member's own size bound. */
    private static final ValidationErrorDetail NAME_TOO_LONG = new ValidationErrorDetail(
            "#", "must have a maximum length of 3", "body", "maxLength", Map.of("maxLength", 3));

    private static List<ValidationErrorDetail> details(ValidationErrorDetail... items) {
        return List.of(items);
    }

    private static final List<ValidationErrorDetail> PASSES = List.of();

    // ---------------------------------------------------------------- schema and gate helpers

    private static JsonMapperProfile profile() {
        return JsonMapperProfiles.of(JsonProfileId.of("reserved-name-guard-gate-parity-test"), new ObjectMapper());
    }

    private static JsonObject canonicalSchema(Class<?> type) {
        return new JsonObject(
                AnnotationJsonSchemaGenerator.forInputProfile(profile()).generateCanonical(type));
    }

    private static JaxRsOperationDescriptor op() {
        return StubDescriptors.builder()
                .httpMethod("POST")
                .routeTemplate("/things")
                .body(new BodyDescriptor(Object.class, null, List.of()))
                .build();
    }

    private static Handler<RoutingContext> gateFor(Class<?> fixture, boolean failFast) {
        JaxRsConfig config = failFast
                ? JaxRsConfig.builder().validationMode("failFast").build()
                : JaxRsConfig.builder().build();
        OperationSchemas schemas =
                OperationSchemas.builder().bodySchema(canonicalSchema(fixture)).build();
        return new WebValidationStrategy(config).gateFor(op(), schemas).orElseThrow();
    }

    // --- RoutingContext mock, mirroring WebValidationGateTest's own harness ---

    private static RoutingContext mockContext(RequestBody body) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        Map<String, Object> data = new HashMap<>();
        when(ctx.request()).thenReturn(request);
        when(ctx.pathParams()).thenReturn(Map.of());
        when(ctx.queryParams()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.cookies()).thenReturn(Set.of());
        when(ctx.body()).thenReturn(body);
        when(ctx.get(anyString())).thenAnswer(inv -> data.get(inv.getArgument(0, String.class)));
        when(ctx.put(anyString(), any())).thenAnswer(inv -> {
            data.put(inv.getArgument(0, String.class), inv.getArgument(1));
            return ctx;
        });
        when(request.getHeader("Content-Type")).thenReturn("application/json");
        return ctx;
    }

    private static RequestBody jsonBody(JsonObject json) {
        // Production always buffers the body bytes; mirror that so the content-type-aware binder can
        // read the body once off the buffer (a null buffer would mean "no body").
        RequestBody body = mock(RequestBody.class);
        when(body.asJsonObject()).thenReturn(json);
        when(body.asJsonArray()).thenReturn(null);
        when(body.buffer()).thenReturn(json.toBuffer());
        return body;
    }

    private static RestValidationException captureFailure(RoutingContext ctx) {
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(ctx).fail(captor.capture());
        return assertInstanceOf(RestValidationException.class, captor.getValue());
    }

    // ---------------------------------------------------------------- the proof

    @ParameterizedTest(name = "{0}")
    @MethodSource("gateParityCorpus")
    @DisplayName("The gate's outcome for each pinned body and mode equals its pinned value")
    void separatedGuardKeepsGateViolationLists(
            String label, Class<?> fixture, boolean failFast, JsonObject body, List<ValidationErrorDetail> expected) {
        Handler<RoutingContext> gate = gateFor(fixture, failFast);
        RoutingContext ctx = mockContext(jsonBody(body));
        gate.handle(ctx);

        if (expected.isEmpty()) {
            verify(ctx, times(1)).next();
            verify(ctx, never()).fail(any(Throwable.class));
        } else {
            verify(ctx, never()).next();
            RestValidationException failure = captureFailure(ctx);
            assertEquals(expected, failure.errors(), () -> label);
        }
    }

    /** Both {@code aggregate} and {@code failFast} report the same outcome for {@code body}. */
    private static Stream<Arguments> bothModes(
            String label, Class<?> fixture, JsonObject body, List<ValidationErrorDetail> expected) {
        return Stream.of(
                Arguments.of(label + " (aggregate)", fixture, false, body, expected),
                Arguments.of(label + " (failFast)", fixture, true, body, expected));
    }

    private static Stream<Arguments> gateParityCorpus() {
        Class<?> ci = GateCaseInsensitiveHiddenDto.class;
        Class<?> cs = GateHiddenAndIgnoredDto.class;
        return Stream.of(
                        // case-insensitive: the reserved name, exact and case-folded
                        bothModes(
                                "the reserved name is refused under its exact spelling",
                                ci,
                                new JsonObject().put("secret", 1),
                                details(PROPERTY_NAME_REFUSED)),
                        bothModes(
                                "the reserved name is refused under its uppercase fold",
                                ci,
                                new JsonObject().put("SECRET", 1),
                                details(PROPERTY_NAME_REFUSED)),
                        bothModes(
                                "the reserved name is refused under a mixed-case fold",
                                ci,
                                new JsonObject().put("sEcReT", 1),
                                details(PROPERTY_NAME_REFUSED)),
                        // case-insensitive: near misses that break the reserved fold, so ordinary extras
                        bothModes(
                                "a trailing line feed breaks the reserved fold, so an ordinary extra",
                                ci,
                                new JsonObject().put("secret" + LINE_FEED, 1),
                                PASSES),
                        bothModes(
                                "a trailing extra character breaks the reserved fold, so an ordinary extra",
                                ci,
                                new JsonObject().put("secrets", 1),
                                PASSES),
                        bothModes(
                                "a leading extra character breaks the reserved fold, so an ordinary extra",
                                ci,
                                new JsonObject().put("xsecret", 1),
                                PASSES),
                        // case-insensitive: non-ASCII confusables, refused outright regardless of what they fold to
                        bothModes(
                                "a Kelvin-sign confusable for 'k' is refused outright as non-ASCII",
                                ci,
                                new JsonObject().put(KELVIN_SIGN + "ey", 1),
                                details(PROPERTY_NAME_REFUSED)),
                        bothModes(
                                "a macron confusable for 'a' is refused outright as non-ASCII",
                                ci,
                                new JsonObject().put("n" + LATIN_SMALL_A_WITH_MACRON + "me", 1),
                                details(PROPERTY_NAME_REFUSED)),
                        // case-insensitive: the published name, within and beyond its size bound
                        bothModes(
                                "the published name within its size bound passes",
                                ci,
                                new JsonObject().put("name", "ab"),
                                PASSES),
                        bothModes(
                                "the published name's uppercase fold within its size bound passes",
                                ci,
                                new JsonObject().put("NAME", "ab"),
                                PASSES),
                        Stream.of(
                                Arguments.of(
                                        "the published name's uppercase fold beyond its size bound reports every"
                                                + " violation (aggregate)",
                                        ci,
                                        false,
                                        new JsonObject().put("NAME", "abcd"),
                                        details(PATTERN_PROPERTIES_VALUE_REJECTED, NAME_TOO_LONG)),
                                Arguments.of(
                                        "the published name's uppercase fold beyond its size bound stops at the"
                                                + " first violation (failFast)",
                                        ci,
                                        true,
                                        new JsonObject().put("NAME", "abcd"),
                                        details(PATTERN_PROPERTIES_VALUE_REJECTED))),
                        bothModes(
                                "an unreserved key is an ordinary extra", ci, new JsonObject().put("extra", 1), PASSES),
                        // case-sensitive: exact-name reservation only
                        bothModes(
                                "a reserved hidden field name is refused",
                                cs,
                                new JsonObject().put("secretField", "x"),
                                details(PROPERTY_NAME_REFUSED)),
                        bothModes(
                                "a reserved ignored field name is refused",
                                cs,
                                new JsonObject().put("ignoredField", "x"),
                                details(PROPERTY_NAME_REFUSED)),
                        bothModes(
                                "a differently-cased spelling is not reserved case-sensitively, so an ordinary extra",
                                cs,
                                new JsonObject().put("SECRETFIELD", "x"),
                                PASSES),
                        bothModes(
                                "an unreserved key is an ordinary extra",
                                cs,
                                new JsonObject().put("other", "x"),
                                PASSES),
                        // a body combining a reserved key, a non-ASCII key, and an over-long value
                        Stream.of(
                                Arguments.of(
                                        "a body combining a reserved key, a non-ASCII key, and an over-long value"
                                                + " reports every violation (aggregate)",
                                        ci,
                                        false,
                                        new JsonObject()
                                                .put("secret", 1)
                                                .put(KELVIN_SIGN + "ey", "z")
                                                .put("NAME", "abcd"),
                                        details(
                                                PROPERTY_NAME_REFUSED,
                                                PROPERTY_NAME_REFUSED,
                                                PATTERN_PROPERTIES_VALUE_REJECTED,
                                                NAME_TOO_LONG)),
                                Arguments.of(
                                        "a body combining a reserved key, a non-ASCII key, and an over-long value"
                                                + " stops at the first violation (failFast)",
                                        ci,
                                        true,
                                        new JsonObject()
                                                .put("secret", 1)
                                                .put(KELVIN_SIGN + "ey", "z")
                                                .put("NAME", "abcd"),
                                        details(PROPERTY_NAME_REFUSED))))
                .flatMap(Function.identity());
    }
}
