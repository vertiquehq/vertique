// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.validation.ParameterViolation;
import dev.vertique.core.validation.PropertyPath;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.json.JacksonFieldNameResolver;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

/**
 * Tests the DEBUG event {@link ConstraintViolationMapper} emits for each BODY violation it locates:
 * which violations are logged, in which order, how many, and that no client-chosen text, message,
 * argument or value reaches the log.
 *
 * <p>Characters that a source file or an editor may rewrite are built at run time, never written as
 * literals.
 */
class ConstraintViolationMapperLoggingTest {

    /** Escape. */
    private static final String ESC = String.valueOf((char) 0x1b);

    /** Next line, an ISO control character outside ASCII. */
    private static final String NEL = String.valueOf((char) 0x85);

    /** Line separator, not an ISO control character. */
    private static final String LINE_SEPARATOR = new String(Character.toChars(0x2028));

    /** Paragraph separator, not an ISO control character. */
    private static final String PARAGRAPH_SEPARATOR = new String(Character.toChars(0x2029));

    /** A declared wire name that holds a control character. */
    private static final String CONTROL_WIRE_NAME = "we" + (char) 0x1b + "ird";

    /** Logged in place of a pointer for a violation that has only the validator's path text. */
    private static final String UNSTRUCTURED = "<omitted:unstructured>";

    private static final Pattern EVENT =
            Pattern.compile("^body violation path=(.*?) pointer=(.*) rootType=(.*)$", Pattern.DOTALL);

    /** A body with a map, a list and a member whose wire name holds a control character. */
    record Doc(
            Map<String, Item> byKey,
            List<Item> items,
            @JsonProperty(CONTROL_WIRE_NAME) String odd) {}

    /** A list or map value with one member. */
    record Item(String name) {}

    private final ObjectMapper routeMapper = new ObjectMapper();

    private Logger mapperLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureMapperLogs() {
        mapperLogger = (Logger) LoggerFactory.getLogger(ConstraintViolationMapper.class);
        previousLevel = mapperLogger.getLevel();
        mapperLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        mapperLogger.addAppender(appender);
    }

    @AfterEach
    void releaseMapperLogs() {
        mapperLogger.detachAppender(appender);
        appender.stop();
        mapperLogger.setLevel(previousLevel);
    }

    // --- Fixtures ---

    /** One parsed {@code body violation} event. */
    private record Logged(String path, String pointer, String rootType) {}

    /** Runs {@code action} and returns the events the mapper's logger received while it ran. */
    private List<ILoggingEvent> captureEvents(Runnable action) {
        appender.list.clear();
        action.run();
        return List.copyOf(appender.list);
    }

    private static Logged parse(ILoggingEvent event) {
        Matcher matcher = EVENT.matcher(event.getFormattedMessage());
        assertTrue(matcher.matches(), () -> "not a body violation event: " + event.getFormattedMessage());
        return new Logged(matcher.group(1), matcher.group(2), matcher.group(3));
    }

    private static List<Logged> parsed(List<ILoggingEvent> events) {
        return events.stream().map(ConstraintViolationMapperLoggingTest::parse).toList();
    }

    private static RoutingContext context(Map<String, Object> data) {
        RoutingContext ctx = mock(RoutingContext.class);
        when(ctx.get(anyString())).thenAnswer(inv -> data.get(inv.<String>getArgument(0)));
        when(ctx.get(anyString(), any())).thenAnswer(inv -> {
            Object value = data.get(inv.<String>getArgument(0));
            return value != null ? value : inv.getArgument(1);
        });
        when(ctx.put(anyString(), any())).thenAnswer(inv -> {
            data.put(inv.getArgument(0), inv.getArgument(1));
            return ctx;
        });
        when(ctx.data()).thenReturn(data);
        return ctx;
    }

    /** A request whose JSON body was bound with the route mapper. */
    private RoutingContext markedRequest(Object document) {
        return request(document, routeMapper, routeMapper);
    }

    /**
     * A request with the given document and route mapper in the context; {@code markedWith} is the
     * mapper the binder recorded, or {@code null} when no binder materialized the body.
     */
    private static RoutingContext request(Object document, ObjectMapper routeMapperInContext, ObjectMapper markedWith) {
        Map<String, Object> data = new HashMap<>();
        RoutingContext ctx = context(data);
        BoundRequest bound = mock(BoundRequest.class);
        when(bound.body()).thenReturn(RequestValue.of(document));
        data.put(BoundRequest.KEY_META_DATA_BOUND_REQUEST, bound);
        data.put(BoundRequest.KEY_RESOLVED_BODY_MAPPER, routeMapperInContext);
        if (markedWith != null) {
            BodyBindingMark.record(ctx, BodyBindingMark.Kind.JSON, markedWith);
        }
        return ctx;
    }

    private static ResourceMethodMeta metaWith(ResourceMethodMeta.ParamMeta param) {
        try {
            return new ResourceMethodMeta(
                    new Object(),
                    Object.class.getMethod("toString"),
                    "testOp",
                    "POST",
                    "/test",
                    List.of(param),
                    Void.class,
                    false,
                    true,
                    new SecurityPolicy.None(),
                    ResourceMethodMeta.MediaTypes.EMPTY,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private void map(Class<?> bodyType, RoutingContext ctx, List<ParameterViolation> violations) {
        ResourceMethodMeta meta =
                metaWith(new ResourceMethodMeta.ParamMeta(null, ResourceMethodMeta.ParamSource.BODY, bodyType));
        ConstraintViolationMapper.toRestValidationException(
                meta, violations, ctx, JacksonFieldNameResolver.forMapper(routeMapper), routeMapper);
    }

    private static PropertyPath.Segment prop(String name) {
        return new PropertyPath.Property(name, null);
    }

    private static PropertyPath.Segment idx(int index) {
        return new PropertyPath.Index(index);
    }

    private static PropertyPath path(PropertyPath.Segment... segments) {
        return new PropertyPath(List.of(segments));
    }

    private static final Map<String, Object> ARGS = Map.of("value", "ZZVALUE");

    /** A violation with only the Java path string, as a validator without structured paths yields. */
    private static ParameterViolation stringPath(String javaPath) {
        return new ParameterViolation(0, new ViolationDetail(javaPath, "ZZMESSAGE", "required", ARGS), null);
    }

    private static ParameterViolation structured(PropertyPath path) {
        return new ParameterViolation(0, new ViolationDetail(path.toJavaPath(), "ZZMESSAGE", "required", ARGS), path);
    }

    private static JsonObject docWithOneItem() {
        return new JsonObject()
                .put("byKey", new JsonObject())
                .put("items", new JsonArray().add(new JsonObject().put("name", "")))
                .put(CONTROL_WIRE_NAME, "");
    }

    private static void assertNeutral(String text, String what) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int at = i;
            assertFalse(
                    Character.isISOControl(c) || c == 0x2028 || c == 0x2029,
                    () -> what + " holds control character U+" + Integer.toHexString(c) + " at " + at);
        }
    }

    /** Everything an event could carry: the formatted line, its template and each argument. */
    private static List<String> textOf(ILoggingEvent event) {
        List<String> texts = new ArrayList<>();
        texts.add(event.getFormattedMessage());
        texts.add(event.getMessage());
        if (event.getArgumentArray() != null) {
            for (Object arg : event.getArgumentArray()) {
                texts.add(String.valueOf(arg));
            }
        }
        assertNull(event.getThrowableProxy(), "a DEBUG event carries no throwable");
        return texts;
    }

    private static void assertNeverCarries(String text, List<ILoggingEvent> events) {
        for (ILoggingEvent event : events) {
            for (String part : textOf(event)) {
                assertFalse(part.contains(text), () -> text + " leaked into: " + part);
            }
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("The DEBUG event is neutralized, ordered and capped at 100")
    void debugEventIsNeutralizedAndCapped() {
        // Given string-path rows whose map keys hold ZZKEY, a newline and a closing bracket
        List<ParameterViolation> violations = new ArrayList<>();
        violations.add(stringPath("byKey[ZZKEY\nX].name"));
        violations.add(stringPath("byKey[a]ZZKEY].name"));
        // And string-path rows whose first bracket comes before the key
        violations.add(stringPath("x[a].ZZKEY"));
        violations.add(stringPath("x[a][0].ZZKEY"));
        violations.add(stringPath("x[a].x"));
        // And structured rows whose property name holds ESC, NEL, U+2028 and U+2029
        violations.add(structured(path(prop("items"), idx(0), prop("na" + ESC + "me"))));
        violations.add(structured(path(prop("items"), idx(0), prop("na" + NEL + "me"))));
        violations.add(structured(path(prop("items"), idx(0), prop("na" + LINE_SEPARATOR + "me"))));
        violations.add(structured(path(prop("items"), idx(0), prop("na" + PARAGRAPH_SEPARATOR + "me"))));
        // And a clean structured row, and one on a member whose declared wire name holds ESC
        violations.add(structured(path(prop("items"), idx(0), prop("name"))));
        violations.add(structured(path(prop("odd"))));
        // And 150 further violations that sort after all of the above
        IntStream.range(0, 150).forEach(i -> violations.add(stringPath("zz%03d".formatted(i))));
        Collections.shuffle(violations, new Random(11));
        RoutingContext ctx = markedRequest(docWithOneItem());

        // When the mapper runs
        List<ILoggingEvent> events = captureEvents(() -> map(Doc.class, ctx, violations));

        // Then exactly 100 events are logged, in sorted order of the rendered Java path
        List<Logged> logged = parsed(events);
        assertEquals(100, logged.size());
        List<String> expectedPaths = new ArrayList<>(List.of(
                "byKey[]",
                "byKey[]",
                "items[0].name",
                "items[0].name",
                "items[0].name",
                "items[0].name",
                "items[0].name",
                "odd",
                "x[]",
                "x[]",
                "x[]"));
        IntStream.range(0, 89).forEach(i -> expectedPaths.add("zz%03d".formatted(i)));
        assertEquals(expectedPaths, logged.stream().map(Logged::path).toList());

        // And string map keys render as the empty bracket, never the key
        assertEquals("byKey[]", logged.get(0).path());
        // And a violation with only the path text logs the unstructured marker, never a pointer
        assertEquals(UNSTRUCTURED, logged.get(0).pointer());
        assertEquals(UNSTRUCTURED, logged.get(1).pointer());
        // And the clean structured row logs its path and the pointer to it
        long clean = logged.stream()
                .filter(l -> l.path().equals("items[0].name") && l.pointer().equals("/items/0/name"))
                .count();
        assertEquals(1, clean);
        // And the four control-character rows log the property name without the control character,
        // the pointer stopping at the last verified element
        long stripped = logged.stream()
                .filter(l -> l.path().equals("items[0].name") && l.pointer().equals("/items/0(truncated)"))
                .count();
        assertEquals(4, stripped);
        // And a declared wire name holding a control character is logged without it
        long weird = logged.stream()
                .filter(l -> l.path().equals("odd") && l.pointer().equals("/weird"))
                .count();
        assertEquals(1, weird);

        // And exactly the 94 logged string-path violations carry the unstructured marker, the six
        // structured ones a pointer, and no other omitted marker appears
        assertEquals(
                94,
                logged.stream().filter(l -> l.pointer().equals(UNSTRUCTURED)).count());
        assertEquals(
                0,
                logged.stream()
                        .filter(l -> l.pointer().startsWith("<omitted:")
                                && !l.pointer().equals(UNSTRUCTURED))
                        .count());
        assertEquals(6, logged.stream().filter(l -> l.pointer().startsWith("/")).count());

        // And nothing client-chosen, no message, argument or value, and no control character appears
        assertNeverCarries("ZZKEY", events);
        assertNeverCarries("ZZMESSAGE", events);
        assertNeverCarries("ZZVALUE", events);
        assertNeverCarries("required", events);
        for (ILoggingEvent event : events) {
            for (String part : textOf(event)) {
                assertNeutral(part, "event text");
            }
        }
    }

    @ParameterizedTest(name = "{0} violations log {1} events")
    @CsvSource({"1,1", "99,99", "100,100", "101,100", "300,100"})
    @DisplayName("At most 100 events are logged per call")
    void eventCountStopsAtOneHundred(int violationCount, int expectedEvents) {
        // Given a number of located BODY violations
        List<ParameterViolation> violations = IntStream.range(0, violationCount)
                .mapToObj(i -> stringPath("zz%03d".formatted(i)))
                .toList();

        // When the mapper runs
        List<ILoggingEvent> events = captureEvents(() -> map(Doc.class, markedRequest(docWithOneItem()), violations));

        // Then the events are the first ones in sorted order
        assertEquals(expectedEvents, events.size());
        assertEquals(
                IntStream.range(0, expectedEvents)
                        .mapToObj(i -> "zz%03d".formatted(i))
                        .toList(),
                parsed(events).stream().map(Logged::path).toList());
        // And each of them, having only the path text, carries the unstructured marker
        assertEquals(
                expectedEvents,
                parsed(events).stream()
                        .filter(l -> l.pointer().equals(UNSTRUCTURED))
                        .count());
    }

    @Test
    @DisplayName("Events follow the sorted order, not the order the violations arrived in")
    void eventsFollowTheSortedOrder() {
        // Given violations delivered in reverse of their sorted order
        List<ParameterViolation> violations = new ArrayList<>(
                List.of(stringPath("zeta"), stringPath("mid"), structured(path(prop("items"), idx(0), prop("name")))));

        // When the mapper runs
        List<ILoggingEvent> events = captureEvents(() -> map(Doc.class, markedRequest(docWithOneItem()), violations));

        // Then the events are in ascending order of the rendered Java path
        assertEquals(
                List.of("items[0].name", "mid", "zeta"),
                parsed(events).stream().map(Logged::path).toList());
    }

    @Test
    @DisplayName("An event logs the Java path and the pointer, and nothing else the request carried")
    void eventLogsOnlyPathPointerAndRootType() {
        // Given one resolvable violation with a message, an argument value and a type
        List<ParameterViolation> violations = List.of(structured(path(prop("items"), idx(0), prop("name"))));

        // When the mapper runs
        List<ILoggingEvent> events = captureEvents(() -> map(Doc.class, markedRequest(docWithOneItem()), violations));

        // Then the event reads path, pointer and root type only
        assertEquals(1, events.size());
        ILoggingEvent event = events.get(0);
        assertEquals(Level.DEBUG, event.getLevel());
        assertEquals(ConstraintViolationMapper.class.getName(), event.getLoggerName());
        Logged logged = parse(event);
        assertEquals("items[0].name", logged.path());
        assertEquals("/items/0/name", logged.pointer());
        assertFalse(logged.rootType().isEmpty());
        assertNeverCarries("ZZMESSAGE", events);
        assertNeverCarries("ZZVALUE", events);
        assertNeverCarries("required", events);
    }

    @Test
    @DisplayName("A body no framework binder materialized logs the unbound marker")
    void unboundBodyLogsTheUnboundMarker() {
        // Given a body with no mark in the context: an application decoder bound it
        RoutingContext ctx = request(docWithOneItem(), routeMapper, null);
        List<ParameterViolation> violations =
                List.of(structured(path(prop("items"), idx(0), prop("name"))), stringPath("byKey[ZZKEY].name"));

        // When the mapper runs
        List<ILoggingEvent> events = captureEvents(() -> map(Doc.class, ctx, violations));

        // Then each body violation logs its path and the marker, and never a pointer
        assertEquals(
                List.of("byKey[] <omitted:unbound>", "items[0].name <omitted:unbound>"),
                parsed(events).stream().map(l -> l.path() + " " + l.pointer()).toList());
        assertNeverCarries("ZZKEY", events);
    }

    @Test
    @DisplayName("A mapper mismatch logs the mismatch marker")
    void mapperMismatchLogsTheMismatchMarker() {
        // Given a body bound with a mapper other than the one in force for the request
        RoutingContext ctx = request(docWithOneItem(), routeMapper, new ObjectMapper());
        List<ParameterViolation> violations = List.of(structured(path(prop("items"), idx(0), prop("name"))));

        // When the mapper runs
        List<ILoggingEvent> events = captureEvents(() -> map(Doc.class, ctx, violations));

        // Then the event carries the marker in place of a pointer
        assertEquals(1, events.size());
        Logged logged = parse(events.get(0));
        assertEquals("items[0].name", logged.path());
        assertEquals("<omitted:mismatch>", logged.pointer());
    }

    @Test
    @DisplayName("A body violation with only the path text logs the unstructured marker and its loggable path")
    void unstructuredViolationLogsTheUnstructuredMarker() {
        // Given a marked JSON body and a violation that carries only the validator's path text
        RoutingContext ctx = markedRequest(docWithOneItem());
        List<ParameterViolation> violations = List.of(stringPath("na" + ESC + "me"));

        // When the mapper runs
        List<ILoggingEvent> events = captureEvents(() -> map(Doc.class, ctx, violations));

        // Then the event logs the neutralized path text and the marker, and no pointer
        assertEquals(1, events.size());
        Logged logged = parse(events.get(0));
        assertEquals("name", logged.path());
        assertEquals(UNSTRUCTURED, logged.pointer());
        assertNeverCarries("ZZMESSAGE", events);
        assertNeverCarries("ZZVALUE", events);
    }

    @Test
    @DisplayName("Violations with only the path text count toward the 100-event cap")
    void unstructuredViolationsCountTowardTheEventCap() {
        // Given 100 path-text violations that sort first and a structured one that sorts after them
        List<ParameterViolation> violations = new ArrayList<>(IntStream.range(0, 100)
                .mapToObj(i -> stringPath("a%03d".formatted(i)))
                .toList());
        violations.add(structured(path(prop("items"), idx(0), prop("name"))));

        // When the mapper runs
        List<ILoggingEvent> events = captureEvents(() -> map(Doc.class, markedRequest(docWithOneItem()), violations));

        // Then the structured violation is the 101st and is not logged
        assertEquals(100, events.size());
        for (Logged entry : parsed(events)) {
            assertEquals(UNSTRUCTURED, entry.pointer(), entry.toString());
            assertFalse(entry.path().startsWith("items"), entry.toString());
        }
    }

    @Test
    @DisplayName("A marked JSON body with no readable document logs the unbound marker")
    void markedBodyWithoutAReadableDocumentLogsTheUnboundMarker() {
        // Given JSON marks whose document cannot be read: none stashed, a scalar, a failing read
        Map<String, Object> noDocument = new HashMap<>();
        RoutingContext withoutBoundRequest = context(noDocument);
        noDocument.put(BoundRequest.KEY_RESOLVED_BODY_MAPPER, routeMapper);
        BodyBindingMark.record(withoutBoundRequest, BodyBindingMark.Kind.JSON, routeMapper);
        RoutingContext scalarBody = markedRequest("not a document");
        Map<String, Object> failingData = new HashMap<>();
        RoutingContext failingRead = context(failingData);
        BoundRequest failing = mock(BoundRequest.class);
        when(failing.body()).thenThrow(new IllegalStateException("unreadable"));
        failingData.put(BoundRequest.KEY_META_DATA_BOUND_REQUEST, failing);
        failingData.put(BoundRequest.KEY_RESOLVED_BODY_MAPPER, routeMapper);
        BodyBindingMark.record(failingRead, BodyBindingMark.Kind.JSON, routeMapper);
        List<ParameterViolation> violations = List.of(structured(path(prop("items"), idx(0), prop("name"))));

        // When the mapper runs for each
        for (RoutingContext ctx : List.of(withoutBoundRequest, scalarBody, failingRead)) {
            List<ILoggingEvent> events = captureEvents(() -> map(Doc.class, ctx, violations));

            // Then the event carries the unbound marker in place of a pointer
            assertEquals(1, events.size());
            Logged logged = parse(events.get(0));
            assertEquals("items[0].name", logged.path());
            assertEquals("<omitted:unbound>", logged.pointer());
        }
    }

    @Test
    @DisplayName("A form body violation logs no body violation event")
    void formBodyViolationLogsNothing() {
        // Given a form-urlencoded body and violations on it
        Map<String, Object> data = new HashMap<>();
        RoutingContext ctx = context(data);
        data.put(BoundRequest.KEY_RESOLVED_BODY_MAPPER, routeMapper);
        BodyBindingMark.record(ctx, BodyBindingMark.Kind.FORM, routeMapper);
        List<ParameterViolation> violations = new ArrayList<>(IntStream.range(0, 150)
                .mapToObj(i -> structured(path(prop("items"), idx(i))))
                .toList());
        violations.add(stringPath("ZZPATH"));

        // When the mapper runs
        List<ILoggingEvent> events = captureEvents(() -> map(Doc.class, ctx, violations));

        // Then no event is logged, however many violations there are
        assertEquals(List.of(), events);
    }

    @Test
    @DisplayName("A violation that is not on the body logs nothing")
    void nonBodyViolationsLogNothing() {
        // Given a query parameter violation
        ResourceMethodMeta meta =
                metaWith(new ResourceMethodMeta.ParamMeta("page", ResourceMethodMeta.ParamSource.QUERY, int.class));
        RoutingContext ctx = markedRequest(docWithOneItem());

        // When the mapper runs
        List<ILoggingEvent> events = captureEvents(() -> ConstraintViolationMapper.toRestValidationException(
                meta,
                List.of(new ParameterViolation(0, new ViolationDetail("", "ZZMESSAGE", "min", ARGS))),
                ctx,
                InputFieldNameResolver.IDENTITY,
                routeMapper));

        // Then no body violation event is logged
        assertEquals(List.of(), events);
    }

    @Test
    @DisplayName("A truncated resolution is marked in the event")
    void truncatedResolutionIsMarked() {
        // Given a position the one-item document does not hold
        List<ParameterViolation> violations = List.of(structured(path(prop("items"), idx(5), prop("name"))));

        // When the mapper runs
        List<ILoggingEvent> events = captureEvents(() -> map(Doc.class, markedRequest(docWithOneItem()), violations));

        // Then the pointer stops at the verified list and says so
        assertEquals(1, events.size());
        Logged logged = parse(events.get(0));
        assertEquals("items[5].name", logged.path());
        assertEquals("/items(truncated)", logged.pointer());
    }

    @Test
    @DisplayName("A 2,000-character path is logged at 512 characters")
    void longPathIsLoggedAtFiveHundredTwelveCharacters() {
        // Given a structured path and a string path of 2,000 characters
        String longName = "a".repeat(2000);
        List<ParameterViolation> structuredViolation = List.of(structured(path(prop(longName))));
        List<ParameterViolation> stringViolation = List.of(stringPath(longName));

        // When the mapper runs for each
        List<ILoggingEvent> fromStructured =
                captureEvents(() -> map(Doc.class, markedRequest(docWithOneItem()), structuredViolation));
        List<ILoggingEvent> fromString =
                captureEvents(() -> map(Doc.class, markedRequest(docWithOneItem()), stringViolation));

        // Then each path is cut to 512 characters
        assertEquals(1, fromStructured.size());
        assertEquals(1, fromString.size());
        assertEquals(512, parse(fromStructured.get(0)).path().length());
        assertEquals(512, parse(fromString.get(0)).path().length());
        assertEquals(UNSTRUCTURED, parse(fromString.get(0)).pointer());
    }

    @Test
    @DisplayName("Nothing is logged when DEBUG is off")
    void nothingIsLoggedAtInfo() {
        // Given the logger at INFO
        mapperLogger.setLevel(Level.INFO);

        // When the mapper runs
        List<ILoggingEvent> events =
                captureEvents(() -> map(Doc.class, markedRequest(docWithOneItem()), List.of(stringPath("zz000"))));

        // Then no event is captured
        assertEquals(List.of(), events);
    }
}
