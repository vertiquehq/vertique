// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.validation.PropertyPath.Index;
import dev.vertique.core.validation.PropertyPath.Opaque;
import dev.vertique.core.validation.PropertyPath.Property;
import dev.vertique.core.validation.PropertyPath.Segment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Proves that the JSON pointer resolver is bounded and never throws: a pointer holds at most
 * {@link JakartaPathPointers#MAX_SEGMENTS} segments however deep the path and the document are;
 * hostile path strings, a type whose introspection fails and a projection that fails all yield the
 * pointer verified so far; and one session asks the projection about each owner class and indexes
 * each visited document object once, however many violations it resolves, and matches an object
 * holding many case variants of one member name in bounded work.
 *
 * <p>The projection is a counting double with the answers the Jackson-backed projection gives for the
 * plain records here: every member bound, every key its own Java name, or, for a route that matches
 * names case-insensitively, every case variant of a member's name. No validator or Vert.x context is
 * required.
 */
class JakartaPathPointersBoundsTest {

    // --- Fixtures ---

    /** A recursive record: each level is a property and a list position. */
    public record Node(List<Node> children, String name) {}

    /** A record whose projection call fails. */
    public record Fragile(String value) {}

    /** Holds a {@link Fragile} beside a well-behaved member. */
    public record FragileHolder(Fragile fragile, String name) {}

    /** A record whose introspection throws a runtime exception. */
    public record Bomb(String value) {}

    /** A record whose introspection overflows the stack. */
    public record Overflow(String value) {}

    /** Holds the two failing types beside a well-behaved member. */
    public record Armed(Bomb bomb, Overflow overflow, String name) {}

    /** A record with a long member name, sent in many case variants, beside a plain member. */
    public record LongName(String abcdefghijklmnopqrst, String other) {}

    /** The route mapper of the well-behaved rows. */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** A route mapper that matches member names case-insensitively. */
    private static final ObjectMapper FOLDING = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .build();

    /** A mapper whose introspection of {@link Bomb} and {@link Overflow} fails. */
    private static final ObjectMapper FAILING_INTROSPECTION = JsonMapper.builder()
            .annotationIntrospector(new JacksonAnnotationIntrospector() {
                @Override
                public VisibilityChecker<?> findAutoDetectVisibility(AnnotatedClass ac, VisibilityChecker<?> checker) {
                    if (ac.getRawType() == Bomb.class) {
                        throw new IllegalStateException("introspection fails for this type");
                    }
                    if (ac.getRawType() == Overflow.class) {
                        throw new StackOverflowError("introspection overflows for this type");
                    }
                    return super.findAutoDetectVisibility(ac, checker);
                }
            })
            .build();

    /**
     * A projection double that answers like the Jackson-backed projection for the records above and
     * records every call. Its answers for one class fail.
     */
    static final class CountingProjection implements InputFieldNameResolver {

        private final Class<?> failing;
        private final boolean foldsCase;
        final List<String> calls = new ArrayList<>();
        final Map<Class<?>, Integer> boundJavaNamesCalls = new HashMap<>();
        int logicalNameCalls;

        CountingProjection(Class<?> failing) {
            this(failing, false);
        }

        /** A double for a route mapper that matches member names case-insensitively when asked. */
        CountingProjection(Class<?> failing, boolean foldsCase) {
            this.failing = failing;
            this.foldsCase = foldsCase;
        }

        private void record(String method, Class<?> ownerType) {
            calls.add(method + ":" + ownerType.getSimpleName());
            if (ownerType == failing) {
                throw new IllegalStateException("projection cannot be composed");
            }
        }

        @Override
        public String logicalName(Class<?> ownerType, String wireName) {
            logicalNameCalls++;
            record("logicalName", ownerType);
            if (foldsCase && ownerType.isRecord()) {
                for (var component : ownerType.getRecordComponents()) {
                    if (component.getName().equalsIgnoreCase(wireName)) {
                        return component.getName();
                    }
                }
            }
            return wireName;
        }

        @Override
        public Set<String> boundJavaNames(Class<?> ownerType) {
            boundJavaNamesCalls.merge(ownerType, 1, Integer::sum);
            record("boundJavaNames", ownerType);
            List<String> names = new ArrayList<>();
            for (var component : ownerType.getRecordComponents()) {
                names.add(component.getName());
            }
            return Set.copyOf(names);
        }

        @Override
        public Map<String, PromotedField> promotedFields(Class<?> ownerType) {
            record("promotedFields", ownerType);
            return Map.of();
        }

        @Override
        public Set<Class<?>> polymorphicSubtypes(Class<?> ownerType) {
            record("polymorphicSubtypes", ownerType);
            return Set.of();
        }

        /** The calls made about one class, in order. */
        List<String> callsAbout(Class<?> ownerType) {
            String suffix = ":" + ownerType.getSimpleName();
            return calls.stream().filter(call -> call.endsWith(suffix)).toList();
        }
    }

    // --- Builders ---

    /** The path {@code children[0]} repeated {@code levels} times, then {@code name}. */
    private static PropertyPath deepPath(int levels) {
        List<Segment> segments = new ArrayList<>(2 * levels + 1);
        for (int i = 0; i < levels; i++) {
            segments.add(new Property("children", Node.class));
            segments.add(new Index(0));
        }
        segments.add(new Property("name", Node.class));
        return new PropertyPath(segments);
    }

    /** A document holding {@code levels} nested nodes, each the first child of the one above. */
    private static Map<String, Object> deepDocument(int levels) {
        Map<String, Object> leaf = node(List.of());
        Map<String, Object> current = leaf;
        for (int i = 0; i < levels; i++) {
            current = node(List.of(current));
        }
        return current;
    }

    private static Map<String, Object> node(List<Object> children) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("children", children);
        node.put("name", "");
        return node;
    }

    /** The pointer {@code /children/0} repeated {@code levels} times. */
    private static String childrenPointer(int levels) {
        return "/children/0".repeat(levels);
    }

    private static int segmentCount(String pointer) {
        return pointer.isEmpty() ? 0 : pointer.split("/", -1).length - 1;
    }

    private static PropertyPath path(Segment... segments) {
        return new PropertyPath(List.of(segments));
    }

    private static JakartaPathPointers.Resolver projectionSession(ObjectMapper mapper, CountingProjection names) {
        return JakartaPathPointers.resolver(mapper, names);
    }

    // --- Test ---

    @Test
    @DisplayName("resolution is bounded in segments and work, and never throws")
    void resolutionIsBoundedAndNeverThrows() {
        List<Executable> checks = new ArrayList<>();
        segmentCap(checks);
        hostileStrings(checks);
        largeDocuments(checks);
        failingIntrospection(checks);
        failingProjection(checks);
        sessionMemo(checks);
        caseVariants(checks);
        assertAll(checks);
    }

    /** Paths deeper than the cap stop at exactly the cap; paths at or under it resolve in full. */
    private static void segmentCap(List<Executable> checks) {
        // Given: 300-level paths and documents, and paths whose full pointer lands just under or over the cap
        PropertyPath deep = deepPath(300);
        Map<String, Object> deepDocument = deepDocument(300);
        JakartaPathPointers.Resolver projection = projectionSession(MAPPER, new CountingProjection(Void.class));
        JakartaPathPointers.Resolver mapperOnly = JakartaPathPointers.resolver(MAPPER, null);

        // When: they resolve in both naming modes
        String deepWithDocument = projection.toPointer(deep, Node.class, deepDocument);
        String deepWithoutDocument = mapperOnly.toPointer(deep, Node.class, null);
        String underCap = projection.toPointer(deepPath(127), Node.class, deepDocument(127));
        String overCap = projection.toPointer(deepPath(128), Node.class, deepDocument(128));
        String longOpaqueTail = mapperOnly.toPointer(opaqueTail(100_000), Node.class, null);

        // Then: a deep pointer has exactly the cap's segments, verified ones only, and resolves in the document
        String capped = childrenPointer(JakartaPathPointers.MAX_SEGMENTS / 2);
        checks.add(() -> assertEquals(256, JakartaPathPointers.MAX_SEGMENTS, "the segment cap"));
        checks.add(() -> assertEquals(256, segmentCount(deepWithDocument), "segments of the 300-level pointer"));
        checks.add(() -> assertEquals(capped, deepWithDocument, "300-level pointer with the document"));
        checks.add(() -> assertEquals(capped, deepWithoutDocument, "300-level pointer without a document"));
        checks.add(() -> assertResolves(deepDocument, deepWithDocument));
        checks.add(() -> assertEquals(childrenPointer(127) + "/name", underCap, "255 segments resolve in full"));
        checks.add(() -> assertEquals(childrenPointer(128), overCap, "257 segments stop at the cap"));
        checks.add(() -> assertEquals("/children", longOpaqueTail, "an opaque position ends a long path"));
    }

    /** {@code children} followed by {@code count} opaque positions. */
    private static PropertyPath opaqueTail(int count) {
        List<Segment> segments = new ArrayList<>(count + 1);
        segments.add(new Property("children", Node.class));
        for (int i = 0; i < count; i++) {
            segments.add(new Opaque());
        }
        return new PropertyPath(segments);
    }

    /** Malformed path strings return a verified prefix of the path and never throw. */
    private static void hostileStrings(List<Executable> checks) {
        // Given: path strings no validator produces
        List<String> hostile = List.of(
                "[",
                "]]]",
                "<<<",
                ".".repeat(10_000),
                "[".repeat(10_000),
                "children[",
                "children[0",
                "children]0[",
                "children[0]]].name",
                "children[-1].name",
                "children[99999999999].name",
                "[-1]",
                "[99999999999]",
                "children[0].".repeat(10_000) + "name",
                "children<<<[0].name",
                "children[ZZKEY].name",
                "children[0]..ZZKEY].name");

        for (String jakartaPath : hostile) {
            // When: each resolves in mapper-only mode
            // Then: it never throws and yields the root, the list, or a list position below it
            checks.add(() -> {
                String pointer = assertDoesNotThrow(
                        () -> JakartaPathPointers.toPointer(jakartaPath, Node.class, MAPPER),
                        "hostile path " + abbreviate(jakartaPath));
                assertTrue(
                        pointer.isEmpty() || pointer.matches("(/children/0)*(/children)?(/name)?"),
                        "hostile path " + abbreviate(jakartaPath) + " gave '" + abbreviate(pointer) + "'");
                assertFalse(pointer.contains("ZZKEY"), "a client-chosen key reached the pointer");
                assertTrue(segmentCount(pointer) <= JakartaPathPointers.MAX_SEGMENTS, "segment cap");
            });
        }
        checks.add(() -> assertEquals(
                "/children", JakartaPathPointers.toPointer("children[-1].name", Node.class, MAPPER), "negative index"));
        checks.add(() -> assertEquals(
                "/children",
                JakartaPathPointers.toPointer("children[99999999999].name", Node.class, MAPPER),
                "overflowing index"));
    }

    private static String abbreviate(String text) {
        return text.length() <= 40 ? "'" + text + "'" : "'" + text.substring(0, 40) + "…' (" + text.length() + ")";
    }

    /** A 100,000-element list: an existing position resolves, a position past its end stops at the list. */
    private static void largeDocuments(List<Executable> checks) {
        // Given: a document whose root list holds 100,000 nodes
        List<Object> children = new ArrayList<>(100_000);
        for (int i = 0; i < 100_000; i++) {
            children.add(node(List.of()));
        }
        Map<String, Object> wide = new LinkedHashMap<>();
        wide.put("children", children);
        wide.put("name", "");
        CountingProjection names = new CountingProjection(Void.class);
        JakartaPathPointers.Resolver session = projectionSession(MAPPER, names);
        PropertyPath last =
                path(new Property("children", Node.class), new Index(99_999), new Property("name", Node.class));
        PropertyPath pastTheEnd =
                path(new Property("children", Node.class), new Index(100_000), new Property("name", Node.class));

        // When: the last element and the position after it resolve
        String lastPointer = session.toPointer(last, Node.class, wide);
        String pastTheEndPointer = session.toPointer(pastTheEnd, Node.class, wide);

        // Then: the existing position is named; the missing one is not; only the visited objects' keys are read
        checks.add(() -> assertEquals("/children/99999/name", lastPointer, "last element of a 100,000-element list"));
        checks.add(() -> assertEquals("/children", pastTheEndPointer, "a position past the end of the list"));
        checks.add(() -> assertTrue(
                names.logicalNameCalls <= 4,
                "only the root's and the visited element's keys may be matched; logicalName calls: "
                        + names.logicalNameCalls));
    }

    /** A type whose introspection throws or overflows yields the pointer verified before it. */
    private static void failingIntrospection(List<Executable> checks) {
        // Given: a mapper whose introspection of two member types fails, and documents holding both members
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("bomb", new LinkedHashMap<>(Map.of("value", "")));
        document.put("overflow", new LinkedHashMap<>(Map.of("value", "")));
        document.put("name", "");
        PropertyPath bombValue = path(new Property("bomb", Armed.class), new Property("value", Bomb.class));
        PropertyPath overflowValue = path(new Property("overflow", Armed.class), new Property("value", Overflow.class));
        PropertyPath name = path(new Property("name", Armed.class));

        for (boolean withProjection : new boolean[] {true, false}) {
            String mode = withProjection ? "projection mode" : "mapper-only mode";
            JakartaPathPointers.Resolver session = withProjection
                    ? projectionSession(FAILING_INTROSPECTION, new CountingProjection(Void.class))
                    : JakartaPathPointers.resolver(FAILING_INTROSPECTION, null);
            Object doc = withProjection ? document : null;

            // When: paths into the failing types and beside them resolve in one session
            String bomb = assertDoesNotThrow(() -> session.toPointer(bombValue, Armed.class, doc), mode);
            String overflow = assertDoesNotThrow(() -> session.toPointer(overflowValue, Armed.class, doc), mode);
            String sibling = assertDoesNotThrow(() -> session.toPointer(name, Armed.class, doc), mode);

            // Then: each stops at the property holding the failing type; the session keeps working
            checks.add(() -> assertEquals("/bomb", bomb, "runtime exception in introspection, " + mode));
            checks.add(() -> assertEquals("/overflow", overflow, "stack overflow in introspection, " + mode));
            checks.add(() -> assertEquals("/name", sibling, "sibling after the failures, " + mode));
        }
    }

    /** A projection that fails for one class is asked once per session, not once per violation. */
    private static void failingProjection(List<Executable> checks) {
        // Given: a projection that fails for one member type, and 100 violations below that type
        CountingProjection names = new CountingProjection(Fragile.class);
        JakartaPathPointers.Resolver session = projectionSession(MAPPER, names);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("fragile", new LinkedHashMap<>(Map.of("value", "")));
        document.put("name", "");
        PropertyPath below = path(new Property("fragile", FragileHolder.class), new Property("value", Fragile.class));

        // When: all 100 resolve in one session
        List<String> pointers = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            pointers.add(assertDoesNotThrow(() -> session.toPointer(below, FragileHolder.class, document)));
        }
        String sibling =
                session.toPointer(path(new Property("name", FragileHolder.class)), FragileHolder.class, document);

        // Then: every pointer stops at the failing type's property, and the failing call is made once
        List<String> about = names.callsAbout(Fragile.class);
        checks.add(() ->
                assertEquals(List.of("/fragile"), pointers.stream().distinct().toList(), "pointers"));
        checks.add(() -> assertEquals("/name", sibling, "sibling of the failing type"));
        checks.add(() ->
                assertEquals(1, about.size(), "projection calls about the failing class in one session: " + about));
    }

    /** One session asks about each owner class once and indexes each visited object's keys once. */
    private static void sessionMemo(List<Executable> checks) {
        // Given: one document and 100 violations spread over three of its objects
        Map<String, Object> grandchild = node(List.of());
        Map<String, Object> second = node(List.of(grandchild));
        Map<String, Object> first = node(List.of());
        Map<String, Object> root = node(List.of(first, second));
        List<PropertyPath> paths = List.of(
                path(new Property("name", Node.class)),
                path(new Property("children", Node.class), new Index(0), new Property("name", Node.class)),
                path(
                        new Property("children", Node.class),
                        new Index(1),
                        new Property("children", Node.class),
                        new Index(0),
                        new Property("name", Node.class)));
        List<String> expected = List.of("/name", "/children/0/name", "/children/1/children/0/name");
        CountingProjection names = new CountingProjection(Void.class);
        JakartaPathPointers.Resolver session = projectionSession(MAPPER, names);

        // When: the session resolves all 100
        List<String> wrong = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String pointer = session.toPointer(paths.get(i % 3), Node.class, root);
            if (!pointer.equals(expected.get(i % 3))) {
                wrong.add(i + ": " + pointer);
            }
        }

        // Then: every pointer is right; the owner class is enumerated once; at most every key of the four
        // visited objects (root, first, second, grandchild: two keys each) is matched once
        checks.add(() -> assertEquals(List.of(), wrong, "pointers that differ from the expected ones"));
        checks.add(() ->
                assertEquals(Map.of(Node.class, 1), names.boundJavaNamesCalls, "boundJavaNames calls per owner class"));
        checks.add(() -> assertTrue(
                names.logicalNameCalls <= 8,
                "each visited object's keys are matched once per session; logicalName calls: "
                        + names.logicalNameCalls));
    }

    /**
     * Many case variants of one member name in one object cost a bounded amount of matching however
     * many violations pass through the object.
     */
    private static void caseVariants(List<Executable> checks) {
        // Given: one object holding 20,000 case variants of a 20-letter member name beside a plain member,
        // on a route that matches names case-insensitively
        String name = "abcdefghijklmnopqrst";
        Map<String, Object> document = new LinkedHashMap<>();
        for (int variant = 0; variant < 20_000; variant++) {
            document.put(caseVariant(name, variant), "");
        }
        document.put("other", "");
        CountingProjection names = new CountingProjection(Void.class, true);
        JakartaPathPointers.Resolver session = projectionSession(FOLDING, names);
        PropertyPath variants = path(new Property(name, LongName.class));
        PropertyPath other = path(new Property("other", LongName.class));

        // When: 100 violations through that object resolve in one session
        List<String> wrong = new ArrayList<>();
        long started = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            boolean onVariants = i % 2 == 0;
            String pointer = session.toPointer(onVariants ? variants : other, LongName.class, document);
            String expected = onVariants ? "" : "/other";
            if (!pointer.equals(expected)) {
                wrong.add(i + ": '" + abbreviate(pointer) + "'");
            }
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        // Then: the member sent in many spellings stops at the parent, the plain member resolves, and the
        // matching work depends neither on the number of variants nor on the number of violations
        checks.add(() -> assertEquals(List.of(), wrong, "pointers that differ from the expected ones"));
        checks.add(() -> assertTrue(
                names.logicalNameCalls <= 8,
                "matching one visited object must take a bounded number of projection lookups; logicalName calls: "
                        + names.logicalNameCalls));
        checks.add(() -> assertTrue(
                elapsedMillis < 10_000, "100 violations over 20,000 case variants took " + elapsedMillis + " ms"));
    }

    /** The name with the letters whose bit is set in {@code variant} upper-cased. */
    private static String caseVariant(String name, int variant) {
        char[] letters = name.toCharArray();
        for (int i = 0; i < letters.length; i++) {
            if (((variant >> i) & 1) == 1) {
                letters[i] = Character.toUpperCase(letters[i]);
            }
        }
        return new String(letters);
    }

    /** Asserts that a non-root pointer names a value present in the document. */
    private static void assertResolves(Object document, String pointer) {
        if (pointer.isEmpty()) {
            return;
        }
        JsonNode tree = MAPPER.valueToTree(document);
        assertFalse(tree.at(JsonPointer.compile(pointer)).isMissingNode(), "pointer must resolve in the document");
    }
}
