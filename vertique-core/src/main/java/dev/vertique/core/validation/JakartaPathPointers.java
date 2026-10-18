// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.validation.PropertyPath.Index;
import dev.vertique.core.validation.PropertyPath.Opaque;
import dev.vertique.core.validation.PropertyPath.Property;
import dev.vertique.core.validation.PropertyPath.Segment;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the Java-side path of a constraint violation into an RFC 6901 JSON pointer over the wire
 * names of the mapper that bound the validated value, and renders violation paths for log lines.
 *
 * <p>INTERNAL framework seam for sibling framework modules that render constraint violations as JSON
 * pointers. It is not an application extension point and may change without a deprecation cycle.
 *
 * <p><strong>Verified segments only.</strong> A pointer emits a segment only when the resolver can
 * verify it; otherwise it stops at the deepest verified ancestor, so a pointer is shorter than the
 * violated location, never different. The root is {@code ""}. In particular:
 *
 * <ul>
 *   <li>A property is named by its primary wire name from the mapper's deserialization introspection
 *       of the owning type. A property the introspection does not report, a property below a scalar,
 *       {@code Object}, container or unknown type, and an unknown root type end the pointer.
 *   <li>A list or array position is emitted as its index and continues into the element type. A
 *       position on any other type (a map, a set, a bean, a scalar) ends the pointer at that property.
 *       Nested lists give one index per level ({@code /lists/0/1}).
 *   <li>A map entry or set member ends the pointer at the map or set property; no key text is ever
 *       emitted. A segment kind the resolver does not recognize ends the pointer the same way.
 *   <li>{@code Optional} and other reference types are transparent.
 *   <li>The runtime owner class recorded on a property is used only when it is assignable to the
 *       declared type; otherwise the declared type decides.
 *   <li>Wire names are escaped {@code ~} to {@code ~0}, then {@code /} to {@code ~1}, with no percent
 *       encoding; a member whose wire name is empty gives the segment {@code ""} (the pointer
 *       {@code /}).
 * </ul>
 *
 * <p><strong>Naming modes.</strong> A session opened with {@link #resolver(ObjectMapper,
 * InputFieldNameResolver)} and no name projection resolves in <em>mapper-only</em> mode: the primary
 * wire name names every property and any document is ignored. With the route's name projection it
 * resolves in <em>projection</em> mode: a property must also be among the projection's bound Java names
 * of its owner (a type whose bindings the projection cannot enumerate ends the pointer at the property
 * holding it), and when the document object at that position holds exactly one key that is a declared
 * spelling of the property (its primary wire name or an alias, case-folded when the mapper matches keys
 * case-insensitively) that the projection maps back to the same property, that key is emitted as the
 * client sent it. Two or more such keys end the pointer at the parent. A projection call that throws
 * ends the pointer, and the failure is remembered for the owner class for the rest of the session.
 *
 * <p><strong>Path strings.</strong> {@link #toPointer(String, Type, ObjectMapper)} and
 * {@link #loggablePath(String)} parse the text produced for a constraint violation: nodes joined by
 * {@code .}, each node a property name with an optional {@code <...>} marker and optional
 * {@code [index]} or {@code [key]} suffixes. The string is scanned rather than split, because a map key
 * may itself hold a dot or a closing bracket: a bracket closes only at a {@code ]} that is followed by
 * {@code .}, {@code [} or the end of the string, and a bracket that cannot be closed ends the parse. A
 * bracket of digits is a position; a negative position or one that overflows an {@code int} ends the
 * parse; any other bracket content, including an empty bracket, is an opaque marker that never keeps
 * the key. A node that starts with a {@code <...>} marker is a container-element node: it contributes
 * its bracket position, if any, and no name.
 *
 * <p><strong>Bounds.</strong> A pointer holds at most {@link #MAX_SEGMENTS} segments. Resolution is
 * iterative and never throws: any failure, including a stack overflow inside the mapper's
 * introspection, yields the pointer verified so far. No mapper or projection is held statically; a
 * session keeps its per-class lookups only as long as the session object itself.
 */
public final class JakartaPathPointers {

    /** Maximum segments in one pointer; deeper paths stop at this depth. */
    public static final int MAX_SEGMENTS = 256;

    /** Maximum length of a rendered log path, in characters. */
    private static final int MAX_LOG_CHARS = 512;

    /**
     * Segments rendered for a log path. Every segment after the first renders at least one character
     * after neutralization, so this many segments always fill {@link #MAX_LOG_CHARS} when the path is
     * long enough.
     */
    private static final int MAX_LOG_SEGMENTS = MAX_LOG_CHARS + 1;

    /** U+2028, removed from log paths. */
    private static final char LINE_SEPARATOR = (char) 0x2028;

    /** U+2029, removed from log paths. */
    private static final char PARAGRAPH_SEPARATOR = (char) 0x2029;

    /** Bound on unwrapping nested reference types at one position; a self-referencing type stops. */
    private static final int MAX_REFERENCE_UNWRAPS = 16;

    private JakartaPathPointers() {}

    /**
     * Converts a property-path string into a JSON pointer in mapper-only mode.
     *
     * <p>The string is parsed with the grammar described on this class into a {@link PropertyPath}
     * without owner types and resolved by a fresh mapper-only session without a document, so the same
     * rules decide the result as for a structured path.
     *
     * @param jakartaPath the property-path string of a constraint violation; may be empty or
     *                    {@code null}
     * @param rootType    the type the path starts at; {@code null} when unknown
     * @param mapper      the mapper whose property naming decides the wire names
     * @return the RFC 6901 pointer of the deepest verified position; {@code ""} for the root; never
     *         {@code null}
     */
    public static String toPointer(@Nullable String jakartaPath, @Nullable Type rootType, ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (jakartaPath == null || jakartaPath.isEmpty()) {
            return "";
        }
        PropertyPath path;
        try {
            path = new PropertyPath(parse(jakartaPath, MAX_SEGMENTS, false));
        } catch (RuntimeException | StackOverflowError e) {
            return "";
        }
        return resolver(mapper, null).toPointer(path, rootType, null);
    }

    /**
     * Opens a resolution session for one batch of violations, such as one error response or one
     * payload rejection.
     *
     * <p>The session memoizes per-owner-class lookups for its own lifetime only; it is not thread-safe
     * and must not be kept beyond the call that created it.
     *
     * @param mapper the mapper that bound the root value; its deserialization introspection supplies the
     *               primary wire names
     * @param names  the route's wire-to-Java name projection for that mapper (aliases, case folding,
     *               bound Java names), or {@code null} for mapper-only resolution
     * @return a new session; never {@code null}
     */
    public static Resolver resolver(ObjectMapper mapper, @Nullable InputFieldNameResolver names) {
        Objects.requireNonNull(mapper, "mapper");
        return new Resolver(mapper, names);
    }

    /**
     * Renders a property-path string for a log line.
     *
     * <p>The string is parsed with the grammar described on this class and rendered in the Java path
     * form of {@link PropertyPath#toJavaPath()}. Rendering stops at the first opaque segment, which
     * renders {@code []}: after a map key the rest of the string is unreliable, because the key may hold
     * {@code ].}. A bracket that cannot be closed ends the rendering. Every
     * {@link Character#isISOControl ISO control character} and U+2028/U+2029 is removed and the result
     * is cut to 512 characters. {@code byKey[ZZKEY].name} and {@code byKey[a].ZZKEY].name} both render
     * {@code byKey[]}.
     *
     * @param jakartaPath the property-path string; may be {@code null}
     * @return the neutralized rendering; {@code ""} for a {@code null} or empty path; never {@code null}
     */
    public static String loggablePath(@Nullable String jakartaPath) {
        if (jakartaPath == null || jakartaPath.isEmpty()) {
            return "";
        }
        try {
            return neutralize(new PropertyPath(parse(jakartaPath, MAX_LOG_SEGMENTS, true)).toJavaPath());
        } catch (RuntimeException | StackOverflowError e) {
            return "";
        }
    }

    /**
     * Renders a structured path for a log line.
     *
     * <p>A structured path holds no key text, so an opaque segment renders {@code []} and rendering
     * continues past it. The result is neutralized and cut like {@link #loggablePath(String)}.
     *
     * @param path the structured path; may be {@code null}
     * @return the neutralized rendering; {@code ""} for a {@code null} or root path; never {@code null}
     */
    public static String loggablePath(@Nullable PropertyPath path) {
        if (path == null) {
            return "";
        }
        try {
            List<Segment> segments = path.segments();
            if (segments.size() > MAX_LOG_SEGMENTS) {
                segments = segments.subList(0, MAX_LOG_SEGMENTS);
            }
            return neutralize(new PropertyPath(segments).toJavaPath());
        } catch (RuntimeException | StackOverflowError e) {
            return "";
        }
    }

    /**
     * One resolution session; see {@link #resolver(ObjectMapper, InputFieldNameResolver)}.
     *
     * <p>Not thread-safe. Its memoized lookups live exactly as long as this object.
     */
    public static final class Resolver {

        /** Memo marker for a projection lookup that failed or could not enumerate the owner. */
        private static final Object UNRESOLVED = new Object();

        private final ObjectMapper mapper;
        private final DeserializationConfig config;

        @Nullable
        private final InputFieldNameResolver names;

        private final boolean foldsCase;

        /** Introspected properties per owner type, by internal name; an absent owner maps to an empty map. */
        private final Map<JavaType, Map<String, BeanPropertyDefinition>> introspected = new HashMap<>();

        /** The projection's bound Java names per owner class, or {@link #UNRESOLVED}. */
        private final Map<Class<?>, Object> boundNames = new HashMap<>();

        /** Owner classes for which a projection call threw during this session. */
        private final Set<Class<?>> failedProjections = new HashSet<>();

        /** Each visited document object's keys, grouped by their (case-folded) spelling. */
        private final Map<Map<?, ?>, Map<String, List<String>>> keyIndexes = new IdentityHashMap<>();

        private Resolver(ObjectMapper mapper, @Nullable InputFieldNameResolver names) {
            this.mapper = mapper;
            this.config = mapper.getDeserializationConfig();
            this.names = names;
            this.foldsCase = config.isEnabled(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
        }

        /**
         * Resolves a structured path into a JSON pointer.
         *
         * @param path     the Java-side path relative to the root value
         * @param rootType the declared type of the root value; {@code null} when unknown
         * @param document the document the root value was bound from ({@code JsonObject},
         *                 {@code JsonArray}, {@code Map}, {@code List} or a scalar), or {@code null};
         *                 ignored in mapper-only mode
         * @return the RFC 6901 pointer of the deepest verified position; {@code ""} for the root; never
         *         {@code null}
         */
        public String toPointer(@Nullable PropertyPath path, @Nullable Type rootType, @Nullable Object document) {
            StringBuilder pointer = new StringBuilder();
            if (path == null) {
                return "";
            }
            try {
                JavaType current = rootType == null ? null : mapper.constructType(rootType);
                Object node = names == null ? null : normalize(document);
                int emitted = 0;
                for (Segment segment : path.segments()) {
                    if (emitted >= MAX_SEGMENTS) {
                        break;
                    }
                    current = unwrapReferences(current);
                    if (current == null) {
                        break;
                    }
                    String token;
                    if (segment instanceof Property property) {
                        Step step = property(current, property, node);
                        if (step == null) {
                            break;
                        }
                        token = escape(step.wireName());
                        current = step.type();
                        node = step.node();
                    } else if (segment instanceof Index index && isSequence(current)) {
                        token = Integer.toString(index.index());
                        current = current.getContentType();
                        node = element(node, index.index());
                    } else {
                        // An opaque marker, a position on a non-sequence, or an unknown segment kind.
                        break;
                    }
                    pointer.append('/').append(token);
                    emitted++;
                }
            } catch (RuntimeException | StackOverflowError e) {
                // An unintrospectable type or a failing projection: keep the pointer verified so far.
            }
            return pointer.toString();
        }

        /** The verified result of one property step. */
        private record Step(
                String wireName,
                @Nullable JavaType type,
                @Nullable Object node) {}

        /**
         * Resolves one property against its owner, or returns {@code null} when it cannot be verified.
         */
        @Nullable
        private Step property(JavaType declared, Property property, @Nullable Object node) {
            JavaType owner = owner(declared, property.ownerType());
            if (owner == null || !isBeanCandidate(owner)) {
                return null;
            }
            BeanPropertyDefinition definition = introspect(owner).get(property.name());
            if (definition == null) {
                return null;
            }
            String primary = definition.getName();
            if (primary == null) {
                return null;
            }
            JavaType type = definition.getPrimaryType();
            if (names == null) {
                return new Step(primary, type, null);
            }
            Class<?> ownerClass = owner.getRawClass();
            Set<String> bound = boundJavaNames(ownerClass);
            if (bound == null || !bound.contains(property.name())) {
                return null;
            }
            if (!(node instanceof Map<?, ?> object)) {
                return new Step(primary, type, null);
            }
            Set<String> matches = declaredKeys(ownerClass, definition, object);
            if (matches == null || matches.size() > 1) {
                return null;
            }
            if (matches.isEmpty()) {
                return new Step(primary, type, null);
            }
            String sent = matches.iterator().next();
            return new Step(sent, type, normalize(object.get(sent)));
        }

        /**
         * The keys of a document object that are declared spellings of a property and that the
         * projection maps back to that property, or {@code null} when the projection failed.
         */
        @Nullable
        private Set<String> declaredKeys(Class<?> ownerClass, BeanPropertyDefinition definition, Map<?, ?> object) {
            Map<String, List<String>> index = keyIndexes.computeIfAbsent(object, this::indexKeys);
            List<String> spellings = new ArrayList<>(2);
            spellings.add(definition.getName());
            List<PropertyName> aliases = definition.findAliases();
            if (aliases != null) {
                for (PropertyName alias : aliases) {
                    if (alias != null && alias.getSimpleName() != null) {
                        spellings.add(alias.getSimpleName());
                    }
                }
            }
            Set<String> matches = new LinkedHashSet<>();
            for (String spelling : spellings) {
                List<String> keys = index.get(foldsCase ? fold(spelling) : spelling);
                if (keys == null) {
                    continue;
                }
                for (String key : keys) {
                    if (matches.contains(key)) {
                        continue;
                    }
                    String logical = logicalName(ownerClass, key);
                    if (logical == null) {
                        return null;
                    }
                    if (logical.equals(definition.getInternalName())) {
                        matches.add(key);
                    }
                }
            }
            return matches;
        }

        /** Groups a document object's string keys by their spelling, case-folded when the mapper folds. */
        private Map<String, List<String>> indexKeys(Map<?, ?> object) {
            Map<String, List<String>> index = new HashMap<>();
            for (Object key : object.keySet()) {
                if (key instanceof String text) {
                    index.computeIfAbsent(foldsCase ? fold(text) : text, ignored -> new ArrayList<>(1))
                            .add(text);
                }
            }
            return index;
        }

        /** The projection's logical name for a key, or {@code null} when the projection failed. */
        @Nullable
        private String logicalName(Class<?> ownerClass, String key) {
            if (names == null || failedProjections.contains(ownerClass)) {
                return null;
            }
            try {
                return names.logicalName(ownerClass, key);
            } catch (RuntimeException e) {
                failedProjections.add(ownerClass);
                return null;
            }
        }

        /** The projection's bound Java names, or {@code null} when it cannot enumerate them or failed. */
        @Nullable
        @SuppressWarnings("unchecked")
        private Set<String> boundJavaNames(Class<?> ownerClass) {
            Object memo = boundNames.get(ownerClass);
            if (memo == null) {
                memo = UNRESOLVED;
                if (names != null && !failedProjections.contains(ownerClass)) {
                    try {
                        Set<String> bound = names.boundJavaNames(ownerClass);
                        if (bound != null) {
                            memo = bound;
                        }
                    } catch (RuntimeException e) {
                        failedProjections.add(ownerClass);
                    }
                }
                boundNames.put(ownerClass, memo);
            }
            return memo == UNRESOLVED ? null : (Set<String>) memo;
        }

        /** The owner's introspected properties by internal name; empty when it cannot be introspected. */
        private Map<String, BeanPropertyDefinition> introspect(JavaType owner) {
            Map<String, BeanPropertyDefinition> properties = introspected.get(owner);
            if (properties == null) {
                properties = new HashMap<>();
                try {
                    for (BeanPropertyDefinition candidate :
                            config.introspect(owner).findProperties()) {
                        String internal = candidate.getInternalName();
                        if (internal != null) {
                            properties.putIfAbsent(internal, candidate);
                        }
                    }
                } catch (RuntimeException | StackOverflowError e) {
                    properties = Map.of();
                }
                introspected.put(owner, properties);
            }
            return properties;
        }

        /**
         * The type that hosts a property: the runtime owner class when it is assignable to the declared
         * type, otherwise the declared type.
         */
        @Nullable
        private JavaType owner(JavaType declared, @Nullable Class<?> ownerType) {
            Class<?> raw = declared.getRawClass();
            if (ownerType == null || ownerType == raw || !raw.isAssignableFrom(ownerType)) {
                return declared;
            }
            return mapper.getTypeFactory().constructSpecializedType(declared, ownerType);
        }
    }

    /**
     * Parses a property-path string into segments with the grammar described on this class.
     *
     * @param path          the property-path string
     * @param maxSegments   the most segments to produce
     * @param stopAtOpaque  whether to end the parse after the first opaque segment
     * @return the parsed segments, in order
     */
    private static List<Segment> parse(String path, int maxSegments, boolean stopAtOpaque) {
        List<Segment> segments = new ArrayList<>();
        int length = path.length();
        int pos = 0;
        while (pos < length && segments.size() < maxSegments) {
            char first = path.charAt(pos);
            if (first == '.') {
                pos++;
                continue;
            }
            if (first == '<') {
                // A container-element node: its marker names no property.
                pos = skipMarker(path, pos);
            } else if (first != '[') {
                int nameEnd = pos;
                while (nameEnd < length && !isNameTerminator(path.charAt(nameEnd))) {
                    nameEnd++;
                }
                segments.add(new Property(path.substring(pos, nameEnd), null));
                pos = nameEnd;
                if (pos < length && path.charAt(pos) == '<') {
                    pos = skipMarker(path, pos);
                }
            }
            while (pos < length && path.charAt(pos) == '[' && segments.size() < maxSegments) {
                int close = findClose(path, pos + 1);
                if (close < 0) {
                    return segments;
                }
                Segment bracket = bracket(path, pos + 1, close);
                if (bracket == null) {
                    return segments;
                }
                segments.add(bracket);
                if (stopAtOpaque && bracket instanceof Opaque) {
                    return segments;
                }
                pos = close + 1;
            }
            if (pos < length && path.charAt(pos) != '.') {
                return segments;
            }
        }
        return segments;
    }

    /**
     * Reads the content of one bracket: an index for digits, an opaque marker for anything else, or
     * {@code null} for a negative or overflowing index, which ends the parse.
     */
    @Nullable
    private static Segment bracket(String path, int from, int to) {
        int start = from;
        boolean negative = false;
        if (start < to && path.charAt(start) == '-') {
            negative = true;
            start++;
        }
        if (start == to) {
            return new Opaque();
        }
        long value = 0;
        for (int i = start; i < to; i++) {
            char c = path.charAt(i);
            if (c < '0' || c > '9') {
                return new Opaque();
            }
            value = value * 10 + (c - '0');
            if (value > Integer.MAX_VALUE) {
                return null;
            }
        }
        return negative ? null : new Index((int) value);
    }

    private static boolean isNameTerminator(char c) {
        return c == '.' || c == '[' || c == '<';
    }

    /** Returns the position after the {@code >} that closes the marker at {@code pos}, or the end. */
    private static int skipMarker(String path, int pos) {
        int close = path.indexOf('>', pos);
        return close < 0 ? path.length() : close + 1;
    }

    /** Returns the index of the {@code ]} closing a bracket that opened before {@code from}, or -1. */
    private static int findClose(String path, int from) {
        int length = path.length();
        for (int i = from; i < length; i++) {
            if (path.charAt(i) == ']') {
                int after = i + 1;
                if (after == length || path.charAt(after) == '.' || path.charAt(after) == '[') {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Unwraps {@code Optional} and other reference types, which bind transparently; {@code null} when
     * the type is unknown or keeps unwrapping into itself.
     */
    @Nullable
    private static JavaType unwrapReferences(@Nullable JavaType type) {
        JavaType current = type;
        for (int i = 0; current != null && i < MAX_REFERENCE_UNWRAPS; i++) {
            if (current.isReferenceType()) {
                current = current.getContentType();
            } else if (current.getRawClass() == Optional.class) {
                current = current.containedType(0);
            } else {
                return current;
            }
        }
        return null;
    }

    /** Returns whether the type is a list or an array: the only types whose positions are stable. */
    private static boolean isSequence(JavaType type) {
        if (type.isArrayType()) {
            return true;
        }
        return type.isCollectionLikeType() && !type.isMapLikeType() && List.class.isAssignableFrom(type.getRawClass());
    }

    /** Returns whether a property of the type may be a bean property the mapper binds. */
    private static boolean isBeanCandidate(JavaType type) {
        Class<?> raw = type.getRawClass();
        if (type.isContainerType()
                || type.isReferenceType()
                || type.isPrimitive()
                || type.isEnumType()
                || raw == Object.class
                || raw.isPrimitive()
                || raw.isArray()) {
            return false;
        }
        String name = raw.getName();
        return !(name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk."));
    }

    /** The document node in its plain form: a {@code Map} for an object, a {@code List} for an array. */
    @Nullable
    private static Object normalize(@Nullable Object node) {
        if (node instanceof JsonObject object) {
            return object.getMap();
        }
        if (node instanceof JsonArray array) {
            return array.getList();
        }
        return node;
    }

    /** The element at a position of a document array, or {@code null} when there is none. */
    @Nullable
    private static Object element(@Nullable Object node, int index) {
        if (node instanceof List<?> list && index < list.size()) {
            return normalize(list.get(index));
        }
        return null;
    }

    private static String fold(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** Escapes one wire name as a pointer segment: {@code ~} first, then {@code /}. */
    private static String escape(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }

    /** Removes control and line-separator characters and cuts to the log length. */
    private static String neutralize(String rendered) {
        StringBuilder out = new StringBuilder(Math.min(rendered.length(), MAX_LOG_CHARS));
        for (int i = 0; i < rendered.length() && out.length() < MAX_LOG_CHARS; i++) {
            char c = rendered.charAt(i);
            if (!Character.isISOControl(c) && c != LINE_SEPARATOR && c != PARAGRAPH_SEPARATOR) {
                out.append(c);
            }
        }
        if (out.length() == MAX_LOG_CHARS && Character.isHighSurrogate(out.charAt(MAX_LOG_CHARS - 1))) {
            out.setLength(MAX_LOG_CHARS - 1);
        }
        return out.toString();
    }
}
