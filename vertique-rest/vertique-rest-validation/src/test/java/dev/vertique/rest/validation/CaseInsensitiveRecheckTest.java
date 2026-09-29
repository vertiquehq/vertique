// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonFormatValidator;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.Validator;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Proves how the {@code web-validation} gate treats a case-insensitively bound member, whatever the
 * casing of its key: the member's value is checked against its own resolved schema, exactly once per
 * case-insensitive level of the body.
 *
 * <p>Every check runs the generated input schema through the same mocked-{@link RoutingContext} gate
 * harness {@link ReservedNameGuardGateParityTest} uses, or through the guard's own compiled validator
 * with a format validator that counts what it is asked. The counts are asserted, never the elapsed
 * time: a count is exact and independent of the machine, so a repeated check cannot hide behind a fast
 * run.
 *
 * <p>Several checks compare the generated schema with {@link #legacyFoldKeys(JsonObject)}, a rebuild
 * of the schema in which every case-insensitive object also validates its members a second time, under
 * an unrestricted case-folded copy of each member, so a member spelled exactly is validated by both
 * entries. The rebuild is what the generator published before its case-folded copies excluded the exact
 * spelling. It is only faithful for members described inline, which is why the fixtures compared
 * against it are made of inline object members alone.
 */
class CaseInsensitiveRecheckTest {

    // ---------------------------------------------------------------- shared fixtures

    /** A case-insensitively bound object whose only member is a size-bounded name. */
    public static final class Leaf {

        @Size(max = 3)
        public String name;
    }

    /** A holder whose member is a list of {@link Leaf}s. */
    public static final class ListHolder {

        public List<Leaf> items;
    }

    /** A holder whose member is an array of {@link Leaf}s. */
    public static final class ArrHolder {

        public Leaf[] arr;
    }

    /** A holder whose member is a map of {@link Leaf}s. */
    public static final class MapHolder {

        public Map<String, Leaf> byKey;
    }

    /** A holder whose member is an optional {@link Leaf}. */
    public static final class OptHolder {

        public Optional<Leaf> opt;
    }

    /** A holder whose member is an enum. */
    public static final class EnumHolder {

        public DayOfWeek day;
    }

    /** A polymorphic base with two subtypes, discriminated by {@code kind}, and a size-bounded name. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = SubtypeA.class, name = "a"),
        @JsonSubTypes.Type(value = SubtypeB.class, name = "b")
    })
    public abstract static class PBase {

        @Size(max = 3)
        public String name;
    }

    /** The first subtype of {@link PBase}. */
    public static final class SubtypeA extends PBase {}

    /** The second subtype of {@link PBase}. */
    public static final class SubtypeB extends PBase {}

    /** A holder whose member is a polymorphic {@link PBase}. */
    public static final class PolyHolder {

        public PBase item;
    }

    /** A holder whose member is a list of polymorphic {@link PBase}s. */
    public static final class PolyListHolder {

        public List<PBase> items;
    }

    /** An object that refers to itself through a list. */
    public static final class SelfRef {

        @Size(max = 3)
        public String name;

        public List<SelfRef> kids;
    }

    /** A holder whose two members share one type. */
    public static final class SharedHolder {

        public Leaf one;
        public Leaf two;
    }

    /** A plain, case-sensitive nested type, used under a case-insensitive holder. */
    public static final class NestedDto {

        @Size(max = 3)
        public String name;
    }

    /** A case-insensitive holder with one case-sensitive nested member. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    public static final class NestedHolder {

        public NestedDto nested;
    }

    /** A case-insensitive holder with two members of one shared case-sensitive nested type. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    public static final class SharedNestedHolder {

        public NestedDto one;
        public NestedDto two;
    }

    /** A case-sensitive nested type with a reserved name and an unconstrained any-setter. */
    public static final class ReservedNested {

        public String name;

        @Schema(hidden = true)
        public String secret;

        @JsonAnySetter
        private void putExtra(String key, Object value) {}
    }

    /** A case-insensitive holder with one nested member whose type reserves a name. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    public static final class ReservedNestedHolder {

        public ReservedNested nested;
    }

    // Distinct inline levels: a case-insensitive type may not re-enter itself inline. Each level has the
    // members below, a hidden member, an any-setter, and a child of the next level.

    public static final class Level0 {
        @Size(max = 3)
        public String name;

        @JsonProperty("a.b")
        @Size(max = 3)
        public String dotted;

        @JsonProperty("in/out")
        @Size(max = 3)
        public String io;

        @JsonProperty("x")
        @Size(max = 3)
        public String single;

        @JsonProperty("123")
        @Size(max = 3)
        public String digits;

        @Schema(hidden = true)
        public String secret;

        public Level1 child;

        @JsonAnySetter
        private void putExtra(String key, Object value) {}
    }

    public static final class Level1 {
        @Size(max = 3)
        public String name;

        @JsonProperty("a.b")
        @Size(max = 3)
        public String dotted;

        @JsonProperty("in/out")
        @Size(max = 3)
        public String io;

        @JsonProperty("x")
        @Size(max = 3)
        public String single;

        @JsonProperty("123")
        @Size(max = 3)
        public String digits;

        @Schema(hidden = true)
        public String secret;

        public Level2 child;

        @JsonAnySetter
        private void putExtra(String key, Object value) {}
    }

    public static final class Level2 {
        @Size(max = 3)
        public String name;

        @JsonProperty("a.b")
        @Size(max = 3)
        public String dotted;

        @JsonProperty("in/out")
        @Size(max = 3)
        public String io;

        @JsonProperty("x")
        @Size(max = 3)
        public String single;

        @JsonProperty("123")
        @Size(max = 3)
        public String digits;

        @Schema(hidden = true)
        public String secret;

        public Level3 child;

        @JsonAnySetter
        private void putExtra(String key, Object value) {}
    }

    public static final class Level3 {
        @Size(max = 3)
        public String name;

        @JsonProperty("a.b")
        @Size(max = 3)
        public String dotted;

        @JsonProperty("in/out")
        @Size(max = 3)
        public String io;

        @JsonProperty("x")
        @Size(max = 3)
        public String single;

        @JsonProperty("123")
        @Size(max = 3)
        public String digits;

        @Schema(hidden = true)
        public String secret;

        public Level4 child;

        @JsonAnySetter
        private void putExtra(String key, Object value) {}
    }

    public static final class Level4 {
        @Size(max = 3)
        public String name;

        @JsonProperty("a.b")
        @Size(max = 3)
        public String dotted;

        @JsonProperty("in/out")
        @Size(max = 3)
        public String io;

        @JsonProperty("x")
        @Size(max = 3)
        public String single;

        @JsonProperty("123")
        @Size(max = 3)
        public String digits;

        @Schema(hidden = true)
        public String secret;

        @JsonAnySetter
        private void putExtra(String key, Object value) {}
    }

    // Closed levels: a size-bounded name and a child, no reserved names, no extras.

    public static final class Closed0 {
        @Size(max = 3)
        public String name;

        public Closed1 child;
    }

    public static final class Closed1 {
        @Size(max = 3)
        public String name;

        public Closed2 child;
    }

    public static final class Closed2 {
        @Size(max = 3)
        public String name;

        public Closed3 child;
    }

    public static final class Closed3 {
        @Size(max = 3)
        public String name;

        public Closed4 child;
    }

    public static final class Closed4 {
        @Size(max = 3)
        public String name;
    }

    // Uri levels: a member with a format the gate checks itself, and a child of the next level.

    public static final class UriLevel0 {
        @Schema(format = "uri")
        public String link;

        public UriLevel1 c;
    }

    public static final class UriLevel1 {
        @Schema(format = "uri")
        public String link;

        public UriLevel2 c;
    }

    public static final class UriLevel2 {
        @Schema(format = "uri")
        public String link;

        public UriLevel3 c;
    }

    public static final class UriLevel3 {
        @Schema(format = "uri")
        public String link;

        public UriLevel4 c;
    }

    public static final class UriLevel4 {
        @Schema(format = "uri")
        public String link;

        public UriLevel5 c;
    }

    public static final class UriLevel5 {
        @Schema(format = "uri")
        public String link;

        public UriLevel6 c;
    }

    public static final class UriLevel6 {
        @Schema(format = "uri")
        public String link;
    }

    // ---------------------------------------------------------------- key constants

    /** KELVIN SIGN, U+212A, built from its code point so the formatter cannot rewrite a literal. */
    private static final String KELVIN_SIGN = Character.toString(0x212A);

    /** LINE FEED, built from its code point for the same reason. */
    private static final String LINE_FEED = Character.toString(0x0A);

    // ---------------------------------------------------------------- gate literals

    private static final Map<String, Object> MAX_LENGTH_3 = Map.of("maxLength", 3);

    private static final String MAX_LENGTH_3_MESSAGE = "must have a maximum length of 3";

    private static ValidationErrorDetail patternPropertiesRejected(String path) {
        return new ValidationErrorDetail(
                path, "does not satisfy the schema", "body", "patternProperties", Map.of("patternProperties", true));
    }

    private static ValidationErrorDetail nameTooLong(String path) {
        return new ValidationErrorDetail(path, MAX_LENGTH_3_MESSAGE, "body", "maxLength", MAX_LENGTH_3);
    }

    // ---------------------------------------------------------------- schema generation

    private static final JsonSchemaOptions GATE_OPTIONS = new JsonSchemaOptions()
            .setDraft(Draft.DRAFT202012)
            .setBaseUri("https://vertique.local/")
            .setOutputFormat(OutputFormat.Basic);

    /** The mapper-wide case-insensitive profile. */
    private static JsonMapperProfile mapperWideProfile() {
        ObjectMapper mapper = JsonMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                .build();
        return JsonMapperProfiles.of(JsonProfileId.of("case-insensitive-recheck-test-mapper-wide"), mapper);
    }

    /** A plain profile: case-insensitivity comes only from a class-level annotation. */
    private static JsonMapperProfile plainProfile() {
        return JsonMapperProfiles.of(JsonProfileId.of("case-insensitive-recheck-test-plain"), new ObjectMapper());
    }

    /**
     * The holders whose case-insensitivity is a class-level annotation on the holder alone, so that
     * their nested types are case-sensitive and described through a definition reference.
     */
    private static final Set<Class<?>> HOLDER_ONLY_SHAPES =
            Set.of(NestedHolder.class, SharedNestedHolder.class, ReservedNestedHolder.class);

    private static final Map<Class<?>, JsonObject> SCHEMAS = new ConcurrentHashMap<>();

    private static JsonObject schemaOf(Class<?> shape) {
        return SCHEMAS.computeIfAbsent(shape, type -> {
            JsonMapperProfile profile = HOLDER_ONLY_SHAPES.contains(type) ? plainProfile() : mapperWideProfile();
            return new JsonObject(
                    AnnotationJsonSchemaGenerator.forInputProfile(profile).generateCanonical(type));
        });
    }

    // ---------------------------------------------------------------- the pre-change schema rebuild

    /** Whether {@code c} is an ASCII letter, the only kind of character a property-name fold covers. */
    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * The plain ASCII case-folding pattern of a property name: each letter as a two-character class,
     * every other character escaped literally, anchored with a leading caret and a trailing
     * end-of-input lookahead. Rebuilt here from the anchor rule, independently of the generator.
     */
    private static String asciiFoldPattern(String name) {
        StringBuilder pattern = new StringBuilder("^");
        for (char c : name.toCharArray()) {
            if (isAsciiLetter(c)) {
                pattern.append('[')
                        .append(Character.toLowerCase(c))
                        .append(Character.toUpperCase(c))
                        .append(']');
            } else {
                if (".^$|?*+()[]{}\\".indexOf(c) >= 0) {
                    pattern.append('\\');
                }
                pattern.append(c);
            }
        }
        return pattern.append("(?![\\s\\S])").toString();
    }

    /**
     * Returns a copy of {@code schema} rebuilt to the shape it had when each case-folded copy was keyed
     * by the plain fold of its member: at every object node that refuses non-ASCII property names and
     * has {@code properties}, {@code patternProperties} holds one entry per published name, letterless
     * names included, keyed by the plain fold and valued by a copy of that member's schema. Members
     * are rebuilt before their parents, so a copy carries the rebuilt keys of its own members.
     */
    private static JsonObject legacyFoldKeys(JsonObject schema) {
        JsonObject copy = schema.copy();
        rebuild(copy);
        return copy;
    }

    private static void rebuild(JsonObject node) {
        JsonObject properties = node.getJsonObject("properties");
        if (properties == null) {
            return;
        }
        for (String name : properties.fieldNames()) {
            Object member = properties.getValue(name);
            if (member instanceof JsonObject memberSchema) {
                rebuild(memberSchema);
            }
        }
        if (!refusesNonAsciiNames(node)) {
            return;
        }
        JsonObject folded = new JsonObject();
        for (String name : properties.fieldNames()) {
            folded.put(asciiFoldPattern(name), properties.getJsonObject(name).copy());
        }
        node.put("patternProperties", folded);
    }

    private static boolean refusesNonAsciiNames(JsonObject node) {
        JsonObject propertyNames = node.getJsonObject("propertyNames");
        return propertyNames != null && propertyNames.encode().contains(NON_ASCII_REFUSAL_PATTERN);
    }

    /** The pattern, as it appears inside encoded JSON, that the non-ASCII refusal of a property name carries. */
    private static final String NON_ASCII_REFUSAL_PATTERN = "[^\\\\x00-\\\\x7F]";

    // ---------------------------------------------------------------- gate harness

    private static JaxRsOperationDescriptor op() {
        return StubDescriptors.builder()
                .httpMethod("POST")
                .routeTemplate("/things")
                .body(new BodyDescriptor(Object.class, null, List.of()))
                .build();
    }

    /** Builds the gate for a body schema, in {@code failFast} or the default {@code aggregate} mode. */
    private static Handler<RoutingContext> gateFor(JsonObject schema, boolean failFast) {
        JaxRsConfig config = failFast
                ? JaxRsConfig.builder().validationMode("failFast").build()
                : JaxRsConfig.builder().build();
        OperationSchemas schemas = OperationSchemas.builder().bodySchema(schema).build();
        return new WebValidationStrategy(config).gateFor(op(), schemas).orElseThrow();
    }

    private static final Map<Class<?>, Handler<RoutingContext>> AGGREGATE_GATES = new ConcurrentHashMap<>();

    /** The aggregate-mode gate of a shape's generated schema, built once per shape. */
    private static Handler<RoutingContext> aggregateGateOf(Class<?> shape) {
        return AGGREGATE_GATES.computeIfAbsent(shape, type -> gateFor(schemaOf(type), false));
    }

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
        RequestBody body = mock(RequestBody.class);
        when(body.asJsonObject()).thenReturn(json);
        when(body.asJsonArray()).thenReturn(null);
        when(body.buffer()).thenReturn(json.toBuffer());
        return body;
    }

    /**
     * Runs {@code body} through {@code gate} and returns the violations it reported, in order; an empty
     * list means the gate let the body through.
     */
    private static List<ValidationErrorDetail> violations(Handler<RoutingContext> gate, JsonObject body) {
        RoutingContext ctx = mockContext(jsonBody(body));
        gate.handle(ctx);
        ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
        Mockito.verify(ctx, Mockito.atMost(1)).fail(failure.capture());
        if (failure.getAllValues().isEmpty()) {
            Mockito.verify(ctx, Mockito.times(1)).next();
            return List.of();
        }
        return assertInstanceOf(
                        RestValidationException.class, failure.getValue(), "the gate's failure is a validation failure")
                .errors();
    }

    // ---------------------------------------------------------------- counting validator

    /** The per-string and per-request limits of the guard the counting runs use: far above any corpus value. */
    private static final int GUARD_MAX_CHARS = 4096;

    private static final int GUARD_MAX_TOTAL_CHARS = 262_144;

    /** The format name the guard renames a {@code uri} format to before compiling. */
    private static final String URI_FORMAT = "x-vertique-format-uri";

    /**
     * A format validator that tallies every call whose format is {@code countedFormat} and delegates every
     * call to the guard. It tallies by the instance string, or by the format name when {@code byInstance}
     * is false.
     */
    private static final class CountingFormats implements JsonFormatValidator {

        private final PatternInputGuard guard;
        private final String countedFormat;
        private final boolean byInstance;
        final Map<String, Integer> counts = new HashMap<>();

        CountingFormats(PatternInputGuard guard, String countedFormat, boolean byInstance) {
            this.guard = guard;
            this.countedFormat = countedFormat;
            this.byInstance = byInstance;
        }

        @Override
        public String validateFormat(String instanceType, String format, Object instance) {
            if (countedFormat.equals(format)) {
                counts.merge(byInstance ? String.valueOf(instance) : format, 1, Integer::sum);
            }
            return guard.validateFormat(instanceType, format, instance);
        }
    }

    /** A guard-rewritten schema compiled with a counting format validator, ready to validate bodies. */
    private static final class CountingRun {

        private final PatternInputGuard guard = new PatternInputGuard(GUARD_MAX_CHARS, GUARD_MAX_TOTAL_CHARS);
        private final CountingFormats formats;
        private final Validator validator;

        CountingRun(JsonObject schema, String countedFormat, boolean byInstance) {
            formats = new CountingFormats(guard, countedFormat, byInstance);
            validator = Validator.create(JsonSchema.of(PatternInputGuard.rewrite(schema)), GATE_OPTIONS, formats);
        }

        /** Validates {@code body} inside the guard's window, with the tally starting at zero. */
        boolean validate(JsonObject body) {
            formats.counts.clear();
            try (PatternInputGuard.Window window = guard.openWindow()) {
                return Boolean.TRUE.equals(validator.validate(body).getValid());
            }
        }

        int count(String key) {
            return formats.counts.getOrDefault(key, 0);
        }
    }

    /** Wraps {@code inner} in {@code depth} objects, each holding the previous under {@code key}. */
    private static JsonObject nestUnder(String key, int depth, JsonObject inner) {
        JsonObject body = inner;
        for (int level = 0; level < depth; level++) {
            body = new JsonObject().put(key, body);
        }
        return body;
    }

    // ---------------------------------------------------------------- the proofs

    private static final List<String> EXACT_MEMBER_NAMES = List.of("name", "a.b", "in/out", "x", "123");

    /** One body of the verdict corpus: where it sits, its leaf, and whether an exact member spelling is on its path. */
    private record CorpusBody(String label, JsonObject body, boolean exactOnPath) {}

    private static String printable(String key) {
        StringBuilder text = new StringBuilder();
        for (char c : key.toCharArray()) {
            if (c == '\n') {
                text.append("\\n");
            } else if (c > 0x7E) {
                text.append("\\u").append(Integer.toHexString(c));
            } else {
                text.append(c);
            }
        }
        return text.toString();
    }

    /** The 476 bodies: 17 keys and 4 values at each of 7 paths. */
    private static List<CorpusBody> verdictCorpus() {
        List<String> keys = List.of(
                "name",
                "NAME",
                "Name",
                "name" + LINE_FEED,
                "xname",
                "a.b",
                "A.B",
                "AxB",
                "in/out",
                "IN/OUT",
                "123",
                "x",
                "X",
                "secret",
                "SECRET",
                "extra",
                KELVIN_SIGN + "ey");
        List<Object> values = new ArrayList<>();
        values.add("ab");
        values.add("abcd");
        values.add(1);
        values.add(null);
        List<List<String>> paths = List.of(
                List.of(),
                List.of("child"),
                List.of("CHILD"),
                List.of("child", "child"),
                List.of("child", "CHILD"),
                List.of("CHILD", "child"),
                List.of("CHILD", "CHILD"));
        List<CorpusBody> corpus = new ArrayList<>();
        for (List<String> path : paths) {
            for (String key : keys) {
                for (Object value : values) {
                    JsonObject body = new JsonObject().put(key, value);
                    for (int index = path.size() - 1; index >= 0; index--) {
                        body = new JsonObject().put(path.get(index), body);
                    }
                    boolean exactOnPath = path.contains("child") || EXACT_MEMBER_NAMES.contains(key);
                    corpus.add(new CorpusBody(
                            "/" + String.join("/", path) + " " + printable(key) + "=" + value, body, exactOnPath));
                }
            }
        }
        return corpus;
    }

    /** True when every element of {@code part} appears in {@code whole} in the same order. */
    private static boolean isOrderPreservingSubsequence(
            List<ValidationErrorDetail> part, List<ValidationErrorDetail> whole) {
        int next = 0;
        for (ValidationErrorDetail detail : whole) {
            if (next < part.size() && part.get(next).equals(detail)) {
                next++;
            }
        }
        return next == part.size();
    }

    @Test
    @DisplayName("Verdicts and fail-fast lists are unchanged; aggregate lists only lose repeats")
    void verdictsAndFailFastListsAreUnchanged() {
        // Given: the generated schema and its rebuild with the plain fold keys, one gate per schema and mode
        JsonObject generated = schemaOf(Level0.class);
        JsonObject legacy = legacyFoldKeys(generated);
        Handler<RoutingContext> generatedAggregate = gateFor(generated, false);
        Handler<RoutingContext> legacyAggregate = gateFor(legacy, false);
        Handler<RoutingContext> generatedFailFast = gateFor(generated, true);
        Handler<RoutingContext> legacyFailFast = gateFor(legacy, true);
        List<CorpusBody> corpus = verdictCorpus();
        assertEquals(476, corpus.size(), "the corpus is 17 keys x 4 values x 7 paths");

        // When: every body goes through each gate
        List<String> verdictDifferences = new ArrayList<>();
        List<String> failFastDifferences = new ArrayList<>();
        List<String> aggregateDifferences = new ArrayList<>();
        int invalidBodies = 0;
        for (CorpusBody row : corpus) {
            List<ValidationErrorDetail> generatedList = violations(generatedAggregate, row.body());
            List<ValidationErrorDetail> legacyList = violations(legacyAggregate, row.body());
            if (generatedList.isEmpty() != legacyList.isEmpty()) {
                verdictDifferences.add(row.label());
            }
            if (!legacyList.isEmpty()) {
                invalidBodies++;
            }
            if (!violations(generatedFailFast, row.body()).equals(violations(legacyFailFast, row.body()))) {
                failFastDifferences.add(row.label());
            }
            boolean aggregateAllowed = row.exactOnPath()
                    ? isOrderPreservingSubsequence(generatedList, legacyList)
                    : generatedList.equals(legacyList);
            if (!aggregateAllowed) {
                aggregateDifferences.add(row.label());
            }
        }

        // Then: verdicts and fail-fast lists are equal; aggregate lists are subsequences, equal off exact paths
        assertTrue(invalidBodies > 100, "the corpus must exercise many rejected bodies, saw " + invalidBodies);
        assertEquals(List.of(), verdictDifferences, "bodies whose verdict changed");
        assertEquals(List.of(), failFastDifferences, "bodies whose fail-fast list changed");
        assertEquals(List.of(), aggregateDifferences, "bodies whose aggregate list is not an allowed reduction");
    }

    @Test
    @DisplayName("The reserved name is refused, spelled exactly or folded, at every level of the guarded corpus")
    void theReservedNameIsRefusedAtEveryLevelOfTheGuardedCorpus() {
        // Given: the generated schema, whose every case-insensitive level carries the reserved-name guard,
        // and one gate per mode. The verdicts are compared with fixed expectations, not with the rebuild:
        // the rebuild copies each level's guard, so it cannot tell a guard that stopped refusing the exact
        // spelling from one that never did.
        JsonObject generated = schemaOf(Level0.class);
        Handler<RoutingContext> aggregate = gateFor(generated, false);
        Handler<RoutingContext> failFast = gateFor(generated, true);
        List<List<String>> paths = List.of(
                List.of(),
                List.of("child"),
                List.of("CHILD"),
                List.of("child", "child"),
                List.of("child", "CHILD"),
                List.of("CHILD", "child"),
                List.of("CHILD", "CHILD"));
        List<Object> values = new ArrayList<>();
        values.add("ab");
        values.add("abcd");
        values.add(1);
        values.add(null);

        // When: the reserved name, in each spelling, carrying each value, sits at each path
        List<String> notRefused = new ArrayList<>();
        int bodies = 0;
        for (List<String> path : paths) {
            for (String key : List.of("secret", "SECRET")) {
                for (Object value : values) {
                    JsonObject body = new JsonObject().put(key, value);
                    for (int index = path.size() - 1; index >= 0; index--) {
                        body = new JsonObject().put(path.get(index), body);
                    }
                    bodies++;
                    String label = "/" + String.join("/", path) + " " + key + "=" + value;
                    long refusals = violations(aggregate, body).stream()
                            .filter(detail -> "propertyNames".equals(detail.type()))
                            .count();
                    if (refusals != 1) {
                        notRefused.add(label + " (aggregate, " + refusals + " refusals)");
                    }
                    if (violations(failFast, body).isEmpty()) {
                        notRefused.add(label + " (failFast, accepted)");
                    }
                }
            }
        }

        // Then: every body is refused, by the reserved-name guard of the level it sits at, exactly once
        assertEquals(56, bodies, "the corpus is 2 spellings x 4 values x 7 paths");
        assertEquals(List.of(), notRefused, "bodies the reserved-name guard did not refuse");
    }

    @Test
    @DisplayName("A violation at an exactly spelled member is reported once")
    void aggregateListsNoLongerRepeatViolations() {
        // Given: the nested schema and the gate in aggregate mode
        Handler<RoutingContext> gate = aggregateGateOf(Level0.class);

        // When / Then: the body's violations equal the literal list, entry for entry
        assertAll(
                () -> assertEquals(
                        List.of(nameTooLong("#/name")),
                        violations(gate, new JsonObject().put("name", "abcd")),
                        "exact name at the root"),
                () -> assertEquals(
                        List.of(nameTooLong("#/child/name")),
                        violations(gate, new JsonObject().put("child", new JsonObject().put("name", "abcd"))),
                        "exact name under the exact child"),
                () -> assertEquals(
                        List.of(patternPropertiesRejected("#/child"), nameTooLong("#/child")),
                        violations(gate, new JsonObject().put("child", new JsonObject().put("NAME", "abcd"))),
                        "upper-case name under the exact child"),
                () -> assertEquals(
                        List.of(patternPropertiesRejected("#"), nameTooLong("#")),
                        violations(gate, new JsonObject().put("NAME", "abcd")),
                        "upper-case name at the root"));
    }

    private static Stream<Arguments> keyCountRows() {
        List<Arguments> rows = new ArrayList<>();
        for (Class<?> root : List.of(Level0.class, Closed0.class)) {
            int perKey = root == Level0.class ? 3 : 2;
            for (String spelling : List.of("child", "CHILD")) {
                for (int depth = 0; depth <= 4; depth++) {
                    rows.add(Arguments.of(
                            root.getSimpleName() + " generated, " + spelling + " x" + depth,
                            root,
                            false,
                            spelling,
                            depth,
                            perKey,
                            perKey * depth));
                }
            }
            for (int depth = 0; depth <= 4; depth++) {
                rows.add(Arguments.of(
                        root.getSimpleName() + " plain-fold keys, child x" + depth,
                        root,
                        true,
                        "child",
                        depth,
                        perKey * (1 << depth),
                        -1));
            }
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keyCountRows")
    @DisplayName("Nesting no longer multiplies a key's count")
    void keyCountsStayConstantWithDepth(
            String label,
            Class<?> root,
            boolean plainFoldKeys,
            String spelling,
            int depth,
            int expectedNameCount,
            int expectedChildCount) {
        // Given: the schema (or its plain-fold rebuild), rewritten by the guard, with counting formats
        JsonObject schema = plainFoldKeys ? legacyFoldKeys(schemaOf(root)) : schemaOf(root);
        CountingRun run = new CountingRun(schema, PatternInputGuard.BOUND_FORMAT, true);

        // When: a body with the name at the innermost of {@code depth} nested levels is validated
        boolean valid = run.validate(nestUnder(spelling, depth, new JsonObject().put("name", "ab")));

        // Then: the body is valid and each key is counted the expected number of times
        assertTrue(valid, label + ": the body is valid");
        assertEquals(expectedNameCount, run.count("name"), label + ": count of name");
        if (expectedChildCount >= 0) {
            assertEquals(expectedChildCount, run.count(spelling), label + ": count of " + spelling);
        }
    }

    /** A well-formed URI far longer than a single string check should ever be repeated on. */
    private static final String LONG_URI = "http://a.com/" + "a".repeat(10_000 - "http://a.com/".length());

    private static Stream<Arguments> uriCheckRows() {
        List<Arguments> rows = new ArrayList<>();
        for (int depth : new int[] {0, 2, 4, 6}) {
            rows.add(Arguments.of("generated, depth " + depth, false, depth, 1));
            rows.add(Arguments.of("plain-fold keys, depth " + depth, true, depth, 1 << (depth + 1)));
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("uriCheckRows")
    @DisplayName("A nested reused-format value is checked once")
    void aNestedReusedFormatValueIsCheckedOnce(String label, boolean plainFoldKeys, int depth, int expectedChecks) {
        // Given: the schema (or its plain-fold rebuild), rewritten by the guard, counting uri checks.
        // Only the number of checks is asserted, never how long they take: a count is exact on any
        // machine, and a repeated check shows up in it whatever the speed.
        JsonObject schema = plainFoldKeys ? legacyFoldKeys(schemaOf(UriLevel0.class)) : schemaOf(UriLevel0.class);
        CountingRun run = new CountingRun(schema, URI_FORMAT, false);

        // When: the URI sits at the innermost of {@code depth} nested levels
        boolean valid = run.validate(nestUnder("c", depth, new JsonObject().put("link", LONG_URI)));

        // Then: the body is valid and the URI check ran the expected number of times
        assertTrue(valid, label + ": the body is valid");
        assertEquals(expectedChecks, run.count(URI_FORMAT), label + ": uri checks");
    }

    /** One member of a shape and one value for it, with the verdict and the key counts of its valid values. */
    private record MemberRow(
            String label,
            Class<?> shape,
            String member,
            Object value,
            boolean valid,
            int holderKeyCount,
            Map<String, Integer> innerKeyCounts) {}

    private static MemberRow invalid(String label, Class<?> shape, String member, Object value) {
        return new MemberRow(label, shape, member, value, false, 0, Map.of());
    }

    private static MemberRow valid(
            String label, Class<?> shape, String member, Object value, Map<String, Integer> innerKeyCounts) {
        return new MemberRow(label, shape, member, value, true, 2, innerKeyCounts);
    }

    private static JsonObject leafBody(String name) {
        return new JsonObject().put("name", name);
    }

    private static JsonObject polyBody(String kind, String name) {
        JsonObject body = new JsonObject().put("kind", kind);
        return name == null ? body : body.put("name", name);
    }

    private static Stream<Arguments> memberRows() {
        return Stream.of(
                        invalid(
                                "list of leaves, name too long",
                                ListHolder.class,
                                "items",
                                new JsonArray().add(leafBody("abcdef"))),
                        invalid(
                                "array of leaves, name too long",
                                ArrHolder.class,
                                "arr",
                                new JsonArray().add(leafBody("abcdef"))),
                        invalid(
                                "map of leaves, name too long",
                                MapHolder.class,
                                "byKey",
                                new JsonObject().put("k", leafBody("abcdef"))),
                        invalid("map of leaves, not an object", MapHolder.class, "byKey", "x"),
                        invalid("optional leaf, name too long", OptHolder.class, "opt", leafBody("abcdef")),
                        invalid("optional leaf, not an object", OptHolder.class, "opt", "x"),
                        invalid("enum, unknown constant", EnumHolder.class, "day", "FUNDAY"),
                        invalid("enum, not a string", EnumHolder.class, "day", 3),
                        invalid("polymorphic, name too long", PolyHolder.class, "item", polyBody("a", "abcdef")),
                        invalid("polymorphic, unknown kind", PolyHolder.class, "item", polyBody("z", null)),
                        invalid(
                                "polymorphic list, name too long",
                                PolyListHolder.class,
                                "items",
                                new JsonArray().add(polyBody("b", "abcdef"))),
                        invalid(
                                "self reference, name too long",
                                SelfRef.class,
                                "kids",
                                new JsonArray().add(leafBody("abcdef"))),
                        invalid(
                                "self reference, name too long two levels down",
                                SelfRef.class,
                                "kids",
                                new JsonArray()
                                        .add(new JsonObject().put("kids", new JsonArray().add(leafBody("abcdef"))))),
                        invalid("nested type, name too long", NestedHolder.class, "nested", leafBody("abcdef")),
                        invalid(
                                "shared nested type, first member, name too long",
                                SharedNestedHolder.class,
                                "one",
                                leafBody("abcdef")),
                        invalid(
                                "shared nested type, second member, name too long",
                                SharedNestedHolder.class,
                                "two",
                                leafBody("abcdef")),
                        invalid(
                                "nested type with a reserved name, reserved name used",
                                ReservedNestedHolder.class,
                                "nested",
                                new JsonObject().put("secret", 1)),
                        valid(
                                "list of leaves",
                                ListHolder.class,
                                "items",
                                new JsonArray().add(leafBody("ab")),
                                Map.of("name", 2)),
                        valid(
                                "array of leaves",
                                ArrHolder.class,
                                "arr",
                                new JsonArray().add(leafBody("ab")),
                                Map.of("name", 2)),
                        valid(
                                "map of leaves",
                                MapHolder.class,
                                "byKey",
                                new JsonObject().put("k", leafBody("ab")),
                                Map.of("name", 2)),
                        valid("optional leaf", OptHolder.class, "opt", leafBody("ab"), Map.of("name", 2)),
                        valid("enum", EnumHolder.class, "day", "MONDAY", Map.of()),
                        valid(
                                "polymorphic",
                                PolyHolder.class,
                                "item",
                                polyBody("a", "ab"),
                                Map.of("kind", 4, "name", 4)),
                        valid(
                                "polymorphic list",
                                PolyListHolder.class,
                                "items",
                                new JsonArray().add(polyBody("a", "ab")),
                                Map.of("kind", 4, "name", 4)),
                        valid(
                                "self reference",
                                SelfRef.class,
                                "kids",
                                new JsonArray().add(leafBody("ab")),
                                Map.of("name", 2)),
                        valid("nested type", NestedHolder.class, "nested", leafBody("ab"), Map.of()),
                        valid(
                                "shared nested type, first member",
                                SharedNestedHolder.class,
                                "one",
                                leafBody("ab"),
                                Map.of()),
                        valid(
                                "shared nested type, second member",
                                SharedNestedHolder.class,
                                "two",
                                leafBody("ab"),
                                Map.of()),
                        valid(
                                "nested type with a reserved name, ordinary name",
                                ReservedNestedHolder.class,
                                "nested",
                                leafBody("ab"),
                                Map.of()),
                        valid(
                                "nested type with a reserved name, upper-case spelling of the reserved name",
                                ReservedNestedHolder.class,
                                "nested",
                                new JsonObject().put("SECRET", 1),
                                Map.of()))
                .map(row -> Arguments.of(row.label(), row));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memberRows")
    @DisplayName("A non-exact casing is validated like the exact spelling, once per level")
    void aNonExactCasingIsValidatedLikeTheExactSpelling(String label, MemberRow row) {
        // Given: the shape's generated schema, its aggregate gate, and a counting run
        Handler<RoutingContext> gate = aggregateGateOf(row.shape());
        CountingRun run = new CountingRun(schemaOf(row.shape()), PatternInputGuard.BOUND_FORMAT, true);

        for (String key : List.of(row.member(), row.member().toUpperCase(Locale.ROOT))) {
            JsonObject body = new JsonObject().put(key, row.value());

            // When: the body goes through the gate
            List<ValidationErrorDetail> reported = violations(gate, body);

            // Then: the verdict is the same under both spellings
            assertEquals(row.valid(), reported.isEmpty(), label + ", key " + key + ": verdict, reported " + reported);

            if (row.valid()) {
                // And: each key is counted once per level, whichever spelling the member has
                assertTrue(run.validate(body), label + ", key " + key + ": the counting run accepts the body");
                assertEquals(row.holderKeyCount(), run.count(key), label + ", key " + key + ": holder key count");
                for (Map.Entry<String, Integer> inner : row.innerKeyCounts().entrySet()) {
                    assertEquals(
                            inner.getValue().intValue(),
                            run.count(inner.getKey()),
                            label + ", key " + key + ": count of inner key " + inner.getKey());
                }
            }
        }
    }
}
