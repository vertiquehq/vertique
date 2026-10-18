// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.cfg.ConfigOverride;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.AnnotatedConstructor;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter;
import com.fasterxml.jackson.databind.introspect.AnnotatedWithParams;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import dev.vertique.core.json.JacksonBindings;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputFieldNameResolver.PromotedField;
import dev.vertique.core.validation.PropertyPath.Index;
import dev.vertique.core.validation.PropertyPath.Opaque;
import dev.vertique.core.validation.PropertyPath.Property;
import dev.vertique.core.validation.PropertyPath.Segment;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.lang.reflect.Member;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
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
 *       {@code Object}, {@code JsonNode}, container or unknown type, and an unknown root type end the
 *       pointer.
 *   <li>A property is emitted only when the mapper writes it: through a setter or a creator parameter
 *       (record components included), or through a field when the document holds a declared spelling
 *       of it (projection mode only). A read-only, getter-only or ignored property, a name the owning
 *       class or the property holding the bean ignores on input, the member that collects unknown keys
 *       ({@code @JsonAnySetter}) and an unwrapped member ({@code @JsonUnwrapped}) end the pointer at
 *       the bean.
 *   <li>A bean the mapper does not bind through its reported properties ends the pointer at the
 *       property holding it, or at the root: a class-level custom deserializer, a builder, a
 *       delegating creator and an array shape ({@code @JsonFormat(shape = ARRAY)} on the class or in a
 *       config override). So does a bean the mapper may build from a scalar through a single-argument
 *       constructor or factory that is not its properties creator, unless the document holds an
 *       object at that position.
 *   <li>A property whose value the mapper binds through a property-level deserializer or converter
 *       ({@code @JsonDeserialize(using, contentUsing, converter, contentConverter)}), a refined type
 *       ({@code @JsonDeserialize(as, contentAs)}) or an array shape is emitted, and the pointer ends
 *       there.
 *   <li>A list or array position is emitted as its index and continues into the element type. A
 *       position on any other type (a map, a set, a bean, a scalar) ends the pointer at that property.
 *       Nested lists give one index per level ({@code /lists/0/1}). A sequence whose bound positions
 *       may differ from the document's ends the pointer at the sequence: null elements skipped
 *       ({@code contentNulls = SKIP} from the mapper's default setter info, a config override or
 *       {@code @JsonSetter}) or a single value read as a one-element sequence
 *       ({@code ACCEPT_SINGLE_VALUE_AS_ARRAY} on the mapper, in a config override or in the property's
 *       {@code @JsonFormat}).
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
 * client sent it. Two or more such keys end the pointer at the parent. A property the projection
 * promotes members out of ends the pointer at its bean. A projection call that throws ends the pointer,
 * and the failure is remembered for the owner class for the rest of the session.
 *
 * <p><strong>Detection limits.</strong> The checks read the mapper's public introspection only:
 * annotations, mix-ins and config overrides the introspection reports. A deserializer a registered
 * Jackson module installs is not visible to it, and a transformation inside a setter or constructor
 * (sorting or filtering a list) cannot be seen, so a position is the one after binding.
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

        private final AnnotationIntrospector introspector;

        /**
         * Whether the mapper itself skips null elements or reads a single value as a one-element
         * sequence for every property, so no list or array position is verifiable.
         */
        private final boolean globallyReordersPositions;

        /** What the introspection says about each owner type and its properties. */
        private final Map<JavaType, OwnerFacts> owners = new HashMap<>();

        /** The projection's bound Java names per owner class, or {@link #UNRESOLVED}. */
        private final Map<Class<?>, Object> boundNames = new HashMap<>();

        /**
         * The Java names of the properties the projection promotes members out of, per owner class,
         * or {@link #UNRESOLVED}.
         */
        private final Map<Class<?>, Object> promotedRoots = new HashMap<>();

        /** Owner classes for which a projection call threw during this session. */
        private final Set<Class<?>> failedProjections = new HashSet<>();

        /** Each visited document object's keys, grouped by their (case-folded) spelling. */
        private final Map<Map<?, ?>, Map<String, List<String>>> keyIndexes = new IdentityHashMap<>();

        private Resolver(ObjectMapper mapper, @Nullable InputFieldNameResolver names) {
            this.mapper = mapper;
            this.config = mapper.getDeserializationConfig();
            this.names = names;
            this.foldsCase = config.isEnabled(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
            this.introspector = config.getAnnotationIntrospector();
            this.globallyReordersPositions = config.isEnabled(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                    || skipsContentNulls(config.getDefaultSetterInfo());
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
                // Set by the property just resolved and kept through the positions below it.
                Set<String> ignoredByHolder = Set.of();
                boolean holderReordersPositions = false;
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
                        Step step = property(current, property, node, ignoredByHolder);
                        if (step == null) {
                            break;
                        }
                        token = escape(step.wireName());
                        current = step.type();
                        node = step.node();
                        ignoredByHolder = step.ignoredBelow();
                        holderReordersPositions = step.reordersPositions();
                    } else if (segment instanceof Index index
                            && isSequence(current)
                            && !holderReordersPositions
                            && !reordersPositions(current)) {
                        token = Integer.toString(index.index());
                        current = current.getContentType();
                        node = element(node, index.index());
                    } else {
                        // An opaque marker, a position on a non-sequence or on a sequence whose bound
                        // positions may differ from the document's, or an unknown segment kind.
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

        /**
         * The verified result of one property step.
         *
         * @param wireName          the name to emit
         * @param type              the type to continue in, or {@code null} when nothing below the
         *                          property can be verified
         * @param node              the document node at the property, or {@code null}
         * @param ignoredBelow      the names the property ignores in the bean it holds
         * @param reordersPositions whether the property's binding may move list positions
         */
        private record Step(
                String wireName,
                @Nullable JavaType type,
                @Nullable Object node,
                Set<String> ignoredBelow,
                boolean reordersPositions) {}

        /**
         * Resolves one property against its owner, or returns {@code null} when it cannot be verified.
         *
         * @param declared        the declared type at this position
         * @param property        the property segment
         * @param node            the document node at this position, or {@code null}
         * @param ignoredByHolder the names the property holding this bean ignores
         */
        @Nullable
        private Step property(
                JavaType declared, Property property, @Nullable Object node, Set<String> ignoredByHolder) {
            JavaType owner = owner(declared, property.ownerType());
            if (owner == null || !isBeanCandidate(owner)) {
                return null;
            }
            OwnerFacts ownerFacts = ownerFacts(owner);
            if (ownerFacts.reshaped()) {
                return null;
            }
            if (ownerFacts.scalarCreator() && (names == null || !(node instanceof Map))) {
                // The mapper may build this bean from a scalar; only an object in the document shows
                // that it read the bean's properties.
                return null;
            }
            BeanPropertyDefinition definition = ownerFacts.properties().get(property.name());
            if (definition == null) {
                return null;
            }
            String primary = definition.getName();
            if (primary == null
                    || isIgnored(definition, ownerFacts.ignored())
                    || isIgnored(definition, ignoredByHolder)) {
                return null;
            }
            PropertyFacts facts = propertyFacts(definition, ownerFacts);
            if (facts.binding() == Binding.NONE || facts.collected()) {
                return null;
            }
            boolean fieldOnly = facts.binding() == Binding.FIELD;
            if (names == null) {
                return fieldOnly ? null : step(primary, definition, facts, null);
            }
            Class<?> ownerClass = owner.getRawClass();
            Set<String> bound = boundJavaNames(ownerClass);
            if (bound == null || !bound.contains(property.name()) || isPromoted(ownerClass, property.name())) {
                return null;
            }
            if (!(node instanceof Map<?, ?> object)) {
                return fieldOnly ? null : step(primary, definition, facts, null);
            }
            Set<String> matches = declaredKeys(ownerClass, definition, object);
            if (matches == null || matches.size() > 1) {
                return null;
            }
            if (matches.isEmpty()) {
                return fieldOnly ? null : step(primary, definition, facts, null);
            }
            String sent = matches.iterator().next();
            return step(sent, definition, facts, normalize(object.get(sent)));
        }

        /** The step for a verified property; a terminal property stops the pointer after itself. */
        private static Step step(
                String wireName, BeanPropertyDefinition definition, PropertyFacts facts, @Nullable Object node) {
            JavaType type = facts.terminal() ? null : definition.getPrimaryType();
            return new Step(
                    wireName, type, facts.terminal() ? null : node, facts.ignoredBelow(), facts.reordersPositions());
        }

        /** Whether a name list holds the property's wire name or its Java name. */
        private boolean isIgnored(BeanPropertyDefinition definition, Set<String> ignored) {
            if (ignored.isEmpty()) {
                return false;
            }
            String wire = definition.getName();
            String internal = definition.getInternalName();
            if (ignored.contains(wire) || ignored.contains(internal)) {
                return true;
            }
            if (foldsCase) {
                for (String name : ignored) {
                    if (name.equalsIgnoreCase(wire) || name.equalsIgnoreCase(internal)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Whether a sequence's bound positions may differ from the document's for a reason the mapper
         * applies to the sequence type itself: null elements skipped, or a single value read as a
         * one-element sequence.
         */
        private boolean reordersPositions(JavaType sequence) {
            if (globallyReordersPositions) {
                return true;
            }
            Class<?> raw = sequence.getRawClass();
            ConfigOverride override = config.getConfigOverride(raw);
            return skipsContentNulls(override.getSetterInfo())
                    || acceptsSingleValue(config.getDefaultPropertyFormat(raw));
        }

        /**
         * Whether the projection promotes members of the named property into its owner, as
         * {@code @JsonUnwrapped} does; a failed projection call answers {@code true}.
         */
        private boolean isPromoted(Class<?> ownerClass, String javaName) {
            Object memo = promotedRoots.get(ownerClass);
            if (memo == null) {
                memo = UNRESOLVED;
                if (names != null && !failedProjections.contains(ownerClass)) {
                    try {
                        Set<String> roots = new HashSet<>();
                        for (PromotedField field :
                                names.promotedFields(ownerClass).values()) {
                            List<String> enclosing = field.enclosingPath();
                            if (!enclosing.isEmpty()) {
                                roots.add(enclosing.get(0));
                            }
                        }
                        memo = roots;
                    } catch (RuntimeException e) {
                        failedProjections.add(ownerClass);
                    }
                }
                promotedRoots.put(ownerClass, memo);
            }
            return !(memo instanceof Set<?> roots) || roots.contains(javaName);
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

        /**
         * What the mapper's introspection says about an owner type, computed once per session. A type
         * that cannot be introspected is treated as reshaped, so nothing below it is emitted.
         */
        private OwnerFacts ownerFacts(JavaType owner) {
            OwnerFacts facts = owners.get(owner);
            if (facts == null) {
                try {
                    facts = computeOwnerFacts(owner);
                } catch (RuntimeException | StackOverflowError e) {
                    facts = OwnerFacts.UNREADABLE;
                }
                owners.put(owner, facts);
            }
            return facts;
        }

        private OwnerFacts computeOwnerFacts(JavaType owner) {
            BeanDescription description = config.introspect(owner);
            Map<String, BeanPropertyDefinition> properties = new HashMap<>();
            for (BeanPropertyDefinition candidate : description.findProperties()) {
                String internal = candidate.getInternalName();
                if (internal != null) {
                    properties.putIfAbsent(internal, candidate);
                }
            }
            Class<?> raw = owner.getRawClass();
            AnnotatedClass classInfo = description.getClassInfo();
            boolean reshaped = !JacksonBindings.bindsThroughItsProperties(config, description)
                    || isArrayShape(introspector.findFormat(classInfo))
                    || isArrayShape(config.getDefaultPropertyFormat(raw));
            Set<String> ignored = new HashSet<>();
            addIgnoredForInput(ignored, introspector.findPropertyIgnoralByName(config, classInfo));
            addIgnoredForInput(ignored, config.getConfigOverride(raw).getIgnorals());
            AnnotatedMember anySetter = description.findAnySetterAccessor();
            return new OwnerFacts(
                    properties,
                    reshaped,
                    hasScalarCreator(description, properties.values()),
                    Set.copyOf(ignored),
                    anySetter == null ? null : anySetter.getName(),
                    new IdentityHashMap<>());
        }

        /**
         * Whether the owner has a single-argument constructor or factory the mapper may build it with
         * from a scalar instead of from its properties. Excluded are a record's canonical constructor,
         * a creator whose parameter backs a property, a creator whose parameter is explicitly named,
         * and a creator declared for properties or disabled.
         */
        private boolean hasScalarCreator(BeanDescription description, Iterable<BeanPropertyDefinition> properties) {
            List<AnnotatedWithParams> creators = new ArrayList<>(description.getConstructors());
            creators.addAll(description.getFactoryMethods());
            for (AnnotatedWithParams creator : creators) {
                if (creator.getParameterCount() != 1) {
                    continue;
                }
                JsonCreator.Mode mode = introspector.findCreatorAnnotation(config, creator);
                if (mode == JsonCreator.Mode.PROPERTIES || mode == JsonCreator.Mode.DISABLED) {
                    continue;
                }
                if (introspector.findNameForDeserialization(creator.getParameter(0)) != null
                        || isRecordCanonical(description, creator)
                        || backsProperty(creator, properties)) {
                    continue;
                }
                return true;
            }
            return false;
        }

        /** Whether a single-argument creator is the canonical constructor of a record. */
        private static boolean isRecordCanonical(BeanDescription description, AnnotatedWithParams creator) {
            Class<?> raw = description.getBeanClass();
            if (!description.isRecordType() || !raw.isRecord() || !(creator instanceof AnnotatedConstructor)) {
                return false;
            }
            RecordComponent[] components = raw.getRecordComponents();
            return components != null
                    && components.length == 1
                    && components[0].getType() == creator.getRawParameterType(0);
        }

        /** Whether a creator's parameter is the constructor parameter of a reported property. */
        private static boolean backsProperty(AnnotatedWithParams creator, Iterable<BeanPropertyDefinition> properties) {
            Member member = creator.getMember();
            for (BeanPropertyDefinition property : properties) {
                Iterator<AnnotatedParameter> parameters = property.getConstructorParameters();
                while (parameters.hasNext()) {
                    AnnotatedParameter parameter = parameters.next();
                    if (parameter != null && member.equals(parameter.getOwner().getMember())) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * What the mapper's introspection says about one property, computed once per session. A
         * property whose facts cannot be read is treated as one the mapper does not write.
         */
        private PropertyFacts propertyFacts(BeanPropertyDefinition definition, OwnerFacts ownerFacts) {
            PropertyFacts facts = ownerFacts.propertyFacts().get(definition);
            if (facts == null) {
                try {
                    facts = computePropertyFacts(definition, ownerFacts);
                } catch (RuntimeException | StackOverflowError e) {
                    facts = PropertyFacts.UNREADABLE;
                }
                ownerFacts.propertyFacts().put(definition, facts);
            }
            return facts;
        }

        private PropertyFacts computePropertyFacts(BeanPropertyDefinition definition, OwnerFacts ownerFacts) {
            Binding binding;
            if (definition.hasSetter() || definition.hasConstructorParameter()) {
                binding = Binding.SETTER_OR_CREATOR;
            } else if (definition.hasField()) {
                binding = Binding.FIELD;
            } else {
                binding = Binding.NONE;
            }
            List<AnnotatedMember> members = new ArrayList<>(4);
            for (AnnotatedMember member : new AnnotatedMember[] {
                definition.getPrimaryMember(),
                definition.getSetter(),
                definition.getField(),
                definition.getConstructorParameter()
            }) {
                if (member != null && !members.contains(member)) {
                    members.add(member);
                }
            }
            JavaType declared = definition.getPrimaryType();
            boolean collected = definition.getInternalName().equals(ownerFacts.anySetterName());
            boolean terminal = false;
            boolean reorders = globallyReordersPositions
                    || definition.getMetadata().getContentNulls() == Nulls.SKIP
                    || skipsContentNulls(
                            config.getConfigOverride(declared.getRawClass()).getSetterInfo())
                    || acceptsSingleValue(config.getDefaultPropertyFormat(declared.getRawClass()));
            Set<String> ignoredBelow = new HashSet<>();
            for (AnnotatedMember member : members) {
                collected |= introspector.findUnwrappingNameTransformer(member) != null;
                JsonFormat.Value format = introspector.findFormat(member);
                terminal |= introspector.findDeserializer(member) != null
                        || introspector.findContentDeserializer(member) != null
                        || introspector.findDeserializationConverter(member) != null
                        || introspector.findDeserializationContentConverter(member) != null
                        || isRefined(member, declared)
                        || isArrayShape(format);
                reorders |= skipsContentNulls(introspector.findSetterInfo(member)) || acceptsSingleValue(format);
                addIgnoredForInput(ignoredBelow, introspector.findPropertyIgnoralByName(config, member));
            }
            return new PropertyFacts(binding, collected, terminal, reorders, Set.copyOf(ignoredBelow));
        }

        /** Whether the member's annotations bind a type other than the declared one. */
        private boolean isRefined(AnnotatedMember member, JavaType declared) {
            try {
                JavaType refined = introspector.refineDeserializationType(config, member, declared);
                return !declared.equals(refined);
            } catch (JsonMappingException e) {
                return true;
            }
        }

        private static void addIgnoredForInput(Set<String> into, @Nullable JsonIgnoreProperties.Value ignoral) {
            if (ignoral != null) {
                into.addAll(ignoral.findIgnoredForDeserialization());
            }
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

    /** How the mapper writes a property. */
    private enum Binding {
        /** Through a setter or a creator parameter, record components included. */
        SETTER_OR_CREATOR,
        /** Through a field only. */
        FIELD,
        /** Not at all: the property is read-only, getter-only or otherwise never written. */
        NONE
    }

    /**
     * What the introspection says about one owner type.
     *
     * @param properties    the reported properties by Java name
     * @param reshaped      whether the mapper binds the type outside its reported properties (custom
     *                      deserializer, builder, delegating creator, array shape)
     * @param scalarCreator whether the mapper may build the type from a scalar
     * @param ignored       the names the type ignores on input
     * @param anySetterName the name of the member that collects unknown keys, or {@code null}
     * @param propertyFacts the memoized facts of the type's properties
     */
    private record OwnerFacts(
            Map<String, BeanPropertyDefinition> properties,
            boolean reshaped,
            boolean scalarCreator,
            Set<String> ignored,
            @Nullable String anySetterName,
            Map<BeanPropertyDefinition, PropertyFacts> propertyFacts) {

        /** The facts of a type that cannot be introspected: nothing below it is verifiable. */
        static final OwnerFacts UNREADABLE = new OwnerFacts(Map.of(), true, false, Set.of(), null, Map.of());
    }

    /**
     * What the introspection says about one property.
     *
     * @param binding           how the mapper writes it
     * @param collected         whether it is an any-setter or unwrapped member, which the document
     *                          never holds under its own name
     * @param terminal          whether its value is bound by a custom deserializer or converter, a
     *                          refined type or an array shape, so nothing below it is verifiable
     * @param reordersPositions whether its binding skips null elements or reads a single value as a
     *                          one-element sequence
     * @param ignoredBelow      the names it ignores in the bean it holds
     */
    private record PropertyFacts(
            Binding binding, boolean collected, boolean terminal, boolean reordersPositions, Set<String> ignoredBelow) {

        /** The facts of a property that cannot be read: never emitted. */
        static final PropertyFacts UNREADABLE = new PropertyFacts(Binding.NONE, true, true, true, Set.of());
    }

    private static boolean isArrayShape(@Nullable JsonFormat.Value format) {
        return format != null && format.getShape() == JsonFormat.Shape.ARRAY;
    }

    private static boolean skipsContentNulls(@Nullable JsonSetter.Value setterInfo) {
        return setterInfo != null && setterInfo.getContentNulls() == Nulls.SKIP;
    }

    private static boolean acceptsSingleValue(@Nullable JsonFormat.Value format) {
        return format != null
                && Boolean.TRUE.equals(format.getFeature(JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY));
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
                || raw.isArray()
                || TreeNode.class.isAssignableFrom(raw)) {
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
