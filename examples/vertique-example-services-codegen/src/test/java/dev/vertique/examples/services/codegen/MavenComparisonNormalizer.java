// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compares the build-time OpenAPI document the Maven plugin writes with the document the running
 * application publishes, after normalizing both, and reconciles the differences with a file of
 * expected differences.
 *
 * <p><b>What is compared.</b> For every operation id found in either document (operations are the
 * Operation Objects under {@code paths}): the request body per media type, the responses per status
 * and media type, and the operation-level parameters per {@code in} and {@code name} (their {@code required} flag and
 * schema). An operation, request body media type, response status, response media type, parameter,
 * or media type schema found in one document only is a {@code presence} difference; the comparison
 * does not descend below it.
 *
 * <p><b>Schema normalization.</b> Each schema is normalized within its own document before it is
 * compared:
 *
 * <ul>
 *   <li>every {@code $ref} is resolved as a fragment-only reference into its document: the fragment
 *       is percent-decoded as UTF-8 and read as an RFC 6901 JSON Pointer ({@code ~1} to {@code /},
 *       then {@code ~0} to {@code ~}), so a component reference ({@code #/components/schemas/X}) and a
 *       rewritten fragment reference into a component ({@code #/components/schemas/K/properties/a},
 *       {@code #/components/schemas/K/$defs/D}) resolve alike; keywords beside a {@code $ref} are laid
 *       over the resolved schema;
 *   <li>OpenAPI 3.0 {@code nullable: true} adds {@code null} to the type set, so it compares equal to
 *       an OpenAPI 3.1 type array that names {@code null};
 *   <li>a reference whose target is already being normalized on the current path is recursion: it is
 *       recorded as a back-reference to that ancestor's pointer and not followed again.
 * </ul>
 *
 * <p>The compared dimensions are {@code property-names} (the set of {@code properties} names),
 * {@code type} (the type set, {@code null} included), {@code required} (the schema's {@code required}
 * names, or a parameter's {@code required} flag), {@code composition} (which of {@code allOf}, {@code
 * anyOf}, {@code oneOf}, {@code not} are present, with their arity), {@code readOnly}, {@code
 * writeOnly}, and {@code recursion}. The comparison descends into {@code properties} present in both
 * schemas, composition branches present in both, {@code items}, and schema-valued {@code
 * additionalProperties}. Nothing else (formats, descriptions, examples, enumerations, request body
 * {@code required}, and a schema-valued {@code additionalProperties} present in one schema only) is
 * compared.
 *
 * <p><b>Output.</b> Each difference names its operation id, a location ({@code operation}, {@code
 * requestBody <media type>}, {@code response <status>}, {@code response <status> <media type>}, or
 * {@code parameter <in> <name>}), a pointer into the normalized schema ({@code #} for its root, then
 * {@code /}-separated unescaped tokens such as {@code #/properties/orderId}), and a dimension, and
 * prints as one line {@code operationId location pointer dimension maven→runtime}. Differences are
 * sorted by operation id, location, pointer, and dimension.
 */
final class MavenComparisonNormalizer {

    /** The closed set of authorities an expected difference may name. */
    static final Set<String> AUTHORITIES = Set.of(
            "maven-model-converter",
            "swagger-core-model",
            "canonical-input-generator",
            "canonical-output-generator",
            "json-profile",
            "rest-validation-parameters",
            "response-inference",
            "security-scheme-handler",
            "apidocs-configuration",
            "apidocs-disclosure");

    private static final List<String> HTTP_METHODS =
            List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    private static final List<String> COMPOSITION_KEYWORDS = List.of("allOf", "anyOf", "oneOf");

    private static final Comparator<Difference> ORDER = Comparator.comparing(Difference::operationId)
            .thenComparing(Difference::location)
            .thenComparing(Difference::pointer)
            .thenComparing(Difference::dimension);

    private static final Comparator<ExpectedDifference> FILE_ORDER = Comparator.comparing(
                    ExpectedDifference::operationId)
            .thenComparing(ExpectedDifference::location)
            .thenComparing(ExpectedDifference::pointer);

    private MavenComparisonNormalizer() {}

    /**
     * One difference between the two documents.
     *
     * @param operationId the operation id
     * @param location where in the operation the difference is
     * @param pointer the pointer into the normalized schema, {@code #} for its root or for no schema
     * @param dimension the compared dimension
     * @param maven the Maven document's value
     * @param runtime the runtime document's value
     */
    record Difference(
            String operationId, String location, String pointer, String dimension, String maven, String runtime) {

        /**
         * Prints the difference as one line.
         *
         * @return {@code operationId location pointer dimension maven→runtime}
         */
        String line() {
            return operationId + " " + location + " " + pointer + " " + dimension + " " + maven + "→" + runtime;
        }

        /** Tells whether an expected entry names this difference. */
        boolean matches(ExpectedDifference expected) {
            return operationId.equals(expected.operationId())
                    && location.equals(expected.location())
                    && pointer.equals(expected.pointer())
                    && dimension.equals(expected.dimension())
                    && (expected.maven() == null || expected.maven().equals(maven))
                    && (expected.runtime() == null || expected.runtime().equals(runtime));
        }
    }

    /**
     * One entry of the expected-differences file.
     *
     * @param operationId the operation id
     * @param location the location
     * @param pointer the pointer
     * @param dimension the dimension
     * @param authority the authority that explains the difference, one of {@link #AUTHORITIES}
     * @param reason why the authority produces the difference
     * @param maven the expected Maven value, or {@code null} to accept any
     * @param runtime the expected runtime value, or {@code null} to accept any
     */
    record ExpectedDifference(
            String operationId,
            String location,
            String pointer,
            String dimension,
            String authority,
            String reason,
            @Nullable String maven,
            @Nullable String runtime) {

        /**
         * Prints the entry as one line in the form of {@link Difference#line()}.
         *
         * @return the entry's line, with {@code *} for an unconstrained value
         */
        String line() {
            return operationId + " " + location + " " + pointer + " " + dimension + " " + (maven == null ? "*" : maven)
                    + "→" + (runtime == null ? "*" : runtime);
        }
    }

    /**
     * The outcome of reconciling observed differences with the expected ones.
     *
     * @param unexplained the observed differences no entry names
     * @param stale the entries no observed difference matches
     */
    record Reconciliation(List<Difference> unexplained, List<ExpectedDifference> stale) {

        /**
         * Tells whether every difference is expected and every entry still occurs.
         *
         * @return whether the reconciliation is clean
         */
        boolean clean() {
            return unexplained.isEmpty() && stale.isEmpty();
        }

        /**
         * Describes the reconciliation, one line per unexplained difference and stale entry.
         *
         * @return the description
         */
        String report() {
            StringBuilder out = new StringBuilder();
            for (Difference difference : unexplained) {
                out.append("\n  difference with no expected entry: ").append(difference.line());
            }
            for (ExpectedDifference entry : stale) {
                out.append("\n  stale expected entry, the difference no longer occurs: ")
                        .append(entry.line());
            }
            return out.toString();
        }
    }

    /**
     * Compares two documents.
     *
     * @param maven the Maven plugin's document
     * @param runtime the runtime document
     * @return every difference, sorted
     * @throws IllegalArgumentException when a document holds a reference that is not fragment-only or
     *     does not resolve, or repeats an operation id
     */
    static List<Difference> compare(JsonNode maven, JsonNode runtime) {
        Map<String, JsonNode> mavenOperations = operations(maven);
        Map<String, JsonNode> runtimeOperations = operations(runtime);
        Set<String> ids = new TreeSet<>(mavenOperations.keySet());
        ids.addAll(runtimeOperations.keySet());
        List<Difference> out = new ArrayList<>();
        for (String id : ids) {
            JsonNode m = mavenOperations.get(id);
            JsonNode r = runtimeOperations.get(id);
            if (m == null || r == null) {
                out.add(new Difference(id, "operation", "#", "presence", presence(m), presence(r)));
                continue;
            }
            Pair docs = new Pair(maven, runtime);
            compareContent(
                    out,
                    docs,
                    id,
                    "requestBody",
                    deref(maven, m.path("requestBody")).path("content"),
                    deref(runtime, r.path("requestBody")).path("content"));
            compareResponses(out, docs, id, m.path("responses"), r.path("responses"));
            compareParameters(out, docs, id, m.path("parameters"), r.path("parameters"));
        }
        out.sort(ORDER);
        return out;
    }

    /**
     * Reads and checks the expected-differences file.
     *
     * @param file the file's tree, an array of entries
     * @return the entries
     * @throws IllegalArgumentException when the file is not an array, an entry lacks a required
     *     member, names an authority outside {@link #AUTHORITIES}, repeats another entry's operation
     *     id, location, pointer, and dimension, or the entries are not sorted by operation id,
     *     location, and pointer
     */
    static List<ExpectedDifference> readExpected(JsonNode file) {
        if (file == null || !file.isArray()) {
            throw new IllegalArgumentException("the expected differences must be a JSON array");
        }
        List<ExpectedDifference> entries = new ArrayList<>();
        int index = 0;
        for (JsonNode node : file) {
            String where = "expected entry " + index;
            ExpectedDifference entry = new ExpectedDifference(
                    text(node, "operationId", where),
                    text(node, "location", where),
                    text(node, "pointer", where),
                    text(node, "dimension", where),
                    text(node, "authority", where),
                    text(node, "reason", where),
                    optionalText(node, "maven", where),
                    optionalText(node, "runtime", where));
            if (!AUTHORITIES.contains(entry.authority())) {
                throw new IllegalArgumentException(where + " names the unknown authority '" + entry.authority()
                        + "'; known authorities: " + new TreeSet<>(AUTHORITIES));
            }
            for (ExpectedDifference earlier : entries) {
                if (earlier.operationId().equals(entry.operationId())
                        && earlier.location().equals(entry.location())
                        && earlier.pointer().equals(entry.pointer())
                        && earlier.dimension().equals(entry.dimension())) {
                    throw new IllegalArgumentException(where + " repeats an earlier entry: " + entry.line());
                }
            }
            if (!entries.isEmpty() && FILE_ORDER.compare(entries.get(entries.size() - 1), entry) > 0) {
                throw new IllegalArgumentException(
                        where + " is out of order; entries are sorted by operationId, location, and pointer: "
                                + entry.line());
            }
            entries.add(entry);
            index++;
        }
        return entries;
    }

    /**
     * Reconciles observed differences with the expected entries.
     *
     * @param observed the observed differences
     * @param expected the expected entries
     * @return the differences no entry names and the entries no difference matches
     */
    static Reconciliation reconcile(List<Difference> observed, List<ExpectedDifference> expected) {
        List<Difference> unexplained = new ArrayList<>();
        for (Difference difference : observed) {
            if (expected.stream().noneMatch(difference::matches)) {
                unexplained.add(difference);
            }
        }
        List<ExpectedDifference> stale = new ArrayList<>();
        for (ExpectedDifference entry : expected) {
            if (observed.stream().noneMatch(d -> d.matches(entry))) {
                stale.add(entry);
            }
        }
        return new Reconciliation(List.copyOf(unexplained), List.copyOf(stale));
    }

    /**
     * Collects the Operation Objects under {@code paths} by operation id.
     *
     * @param document the document
     * @return the operations, sorted by id
     * @throws IllegalArgumentException when an operation id repeats
     */
    static Map<String, JsonNode> operations(JsonNode document) {
        Map<String, JsonNode> out = new TreeMap<>();
        for (Map.Entry<String, JsonNode> path : document.path("paths").properties()) {
            for (String method : HTTP_METHODS) {
                JsonNode operation = path.getValue().get(method);
                if (operation != null && operation.hasNonNull("operationId")) {
                    String id = operation.get("operationId").asText();
                    if (out.put(id, operation) != null) {
                        throw new IllegalArgumentException("operation id '" + id + "' repeats in one document");
                    }
                }
            }
        }
        return out;
    }

    /**
     * Resolves a fragment-only reference in a document.
     *
     * @param document the document
     * @param reference the reference, starting with {@code #}
     * @return the target
     * @throws IllegalArgumentException when the reference is not fragment-only or does not resolve
     */
    static JsonNode resolve(JsonNode document, String reference) {
        if (!reference.startsWith("#")) {
            throw new IllegalArgumentException("the reference '" + reference + "' is not fragment-only");
        }
        String pointer = percentDecode(reference.substring(1), reference);
        if (pointer.isEmpty()) {
            return document;
        }
        if (!pointer.startsWith("/")) {
            throw new IllegalArgumentException("the reference '" + reference + "' is not a JSON Pointer");
        }
        JsonNode node = document;
        for (String escaped : pointer.substring(1).split("/", -1)) {
            String token = escaped.replace("~1", "/").replace("~0", "~");
            if (node.isArray()) {
                node = isIndex(token) ? node.get(Integer.parseInt(token)) : null;
            } else if (node.isObject()) {
                node = node.get(token);
            } else {
                node = null;
            }
            if (node == null) {
                throw new IllegalArgumentException("the reference '" + reference + "' does not resolve");
            }
        }
        return node;
    }

    private record Pair(JsonNode maven, JsonNode runtime) {}

    /** A schema normalized for comparison; {@code backReference} is set for recursion. */
    private record Normalized(
            Set<String> types,
            Set<String> propertyNames,
            Map<String, Normalized> properties,
            Set<String> required,
            Map<String, List<Normalized>> composition,
            @Nullable Normalized not,
            @Nullable Normalized items,
            @Nullable Normalized additionalProperties,
            boolean readOnly,
            boolean writeOnly,
            @Nullable String backReference) {}

    private static void compareResponses(List<Difference> out, Pair docs, String id, JsonNode maven, JsonNode runtime) {
        Set<String> statuses = new TreeSet<>();
        maven.properties().forEach(e -> statuses.add(e.getKey()));
        runtime.properties().forEach(e -> statuses.add(e.getKey()));
        for (String status : statuses) {
            JsonNode m = maven.get(status);
            JsonNode r = runtime.get(status);
            if (m == null || r == null) {
                out.add(new Difference(id, "response " + status, "#", "presence", presence(m), presence(r)));
                continue;
            }
            compareContent(
                    out,
                    docs,
                    id,
                    "response " + status,
                    deref(docs.maven(), m).path("content"),
                    deref(docs.runtime(), r).path("content"));
        }
    }

    private static void compareContent(
            List<Difference> out, Pair docs, String id, String prefix, JsonNode maven, JsonNode runtime) {
        Set<String> mediaTypes = new TreeSet<>();
        maven.properties().forEach(e -> mediaTypes.add(e.getKey()));
        runtime.properties().forEach(e -> mediaTypes.add(e.getKey()));
        for (String mediaType : mediaTypes) {
            String location = prefix + " " + mediaType;
            JsonNode m = maven.get(mediaType);
            JsonNode r = runtime.get(mediaType);
            if (m == null || r == null) {
                out.add(new Difference(id, location, "#", "presence", presence(m), presence(r)));
                continue;
            }
            compareSchemaSlot(out, docs, id, location, m.get("schema"), r.get("schema"));
        }
    }

    private static void compareParameters(
            List<Difference> out, Pair docs, String id, JsonNode maven, JsonNode runtime) {
        Map<String, JsonNode> m = parameters(docs.maven(), maven);
        Map<String, JsonNode> r = parameters(docs.runtime(), runtime);
        Set<String> keys = new TreeSet<>(m.keySet());
        keys.addAll(r.keySet());
        for (String key : keys) {
            String location = "parameter " + key;
            JsonNode mp = m.get(key);
            JsonNode rp = r.get(key);
            if (mp == null || rp == null) {
                out.add(new Difference(id, location, "#", "presence", presence(mp), presence(rp)));
                continue;
            }
            boolean mRequired = mp.path("required").asBoolean(false);
            boolean rRequired = rp.path("required").asBoolean(false);
            if (mRequired != rRequired) {
                out.add(new Difference(
                        id, location, "#", "required", String.valueOf(mRequired), String.valueOf(rRequired)));
            }
            compareSchemaSlot(out, docs, id, location, mp.get("schema"), rp.get("schema"));
        }
    }

    private static Map<String, JsonNode> parameters(JsonNode document, JsonNode parameters) {
        Map<String, JsonNode> out = new TreeMap<>();
        for (JsonNode parameter : parameters) {
            JsonNode resolved = deref(document, parameter);
            out.put(resolved.path("in").asText() + " " + resolved.path("name").asText(), resolved);
        }
        return out;
    }

    private static void compareSchemaSlot(
            List<Difference> out, Pair docs, String id, String location, JsonNode maven, JsonNode runtime) {
        if (maven == null && runtime == null) {
            return;
        }
        if (maven == null || runtime == null) {
            out.add(new Difference(id, location, "#", "presence", presence(maven), presence(runtime)));
            return;
        }
        Normalized m = normalize(docs.maven(), maven, "#", null);
        Normalized r = normalize(docs.runtime(), runtime, "#", null);
        compareNormalized(out, id, location, "#", m, r);
    }

    /**
     * Normalizes a schema.
     *
     * @param document the schema's document
     * @param schema the schema
     * @param pointer the schema's pointer in the normalized tree
     * @param resolving the innermost reference target being normalized on the current path, or {@code
     *     null}
     */
    private static Normalized normalize(JsonNode document, JsonNode schema, String pointer, @Nullable Frame resolving) {
        JsonNode effective = schema;
        JsonNode target = null;
        if (schema.isObject() && schema.has("$ref")) {
            target = resolve(document, schema.get("$ref").asText());
            for (Frame frame = resolving; frame != null; frame = frame.parent()) {
                if (frame.target() == target) {
                    return new Normalized(
                            Set.of(),
                            Set.of(),
                            Map.of(),
                            Set.of(),
                            Map.of(),
                            null,
                            null,
                            null,
                            false,
                            false,
                            frame.pointer());
                }
            }
            if (schema.size() > 1 && target.isObject()) {
                ObjectNode merged = ((ObjectNode) target).deepCopy();
                for (Map.Entry<String, JsonNode> sibling : schema.properties()) {
                    if (!"$ref".equals(sibling.getKey())) {
                        merged.set(sibling.getKey(), sibling.getValue());
                    }
                }
                effective = merged;
            } else {
                effective = target;
            }
            resolving = new Frame(target, pointer, resolving);
        }
        if (effective.isObject() && effective.has("$ref")) {
            return normalize(document, effective, pointer, resolving);
        }
        if (!effective.isObject()) {
            return new Normalized(
                    Set.of(effective.asBoolean(true) ? "(any)" : "(none)"),
                    Set.of(),
                    Map.of(),
                    Set.of(),
                    Map.of(),
                    null,
                    null,
                    null,
                    false,
                    false,
                    null);
        }
        Set<String> types = new TreeSet<>();
        JsonNode type = effective.get("type");
        if (type != null && type.isArray()) {
            type.forEach(t -> types.add(t.asText()));
        } else if (type != null) {
            types.add(type.asText());
        }
        if (effective.path("nullable").asBoolean(false)) {
            types.add("null");
        }
        Map<String, Normalized> properties = new TreeMap<>();
        for (Map.Entry<String, JsonNode> property : effective.path("properties").properties()) {
            properties.put(
                    property.getKey(),
                    normalize(document, property.getValue(), pointer + "/properties/" + property.getKey(), resolving));
        }
        Set<String> required = new TreeSet<>();
        effective.path("required").forEach(r -> required.add(r.asText()));
        Map<String, List<Normalized>> composition = new TreeMap<>();
        for (String keyword : COMPOSITION_KEYWORDS) {
            JsonNode branches = effective.get(keyword);
            if (branches != null && branches.isArray()) {
                List<Normalized> normalized = new ArrayList<>();
                int i = 0;
                for (JsonNode branch : branches) {
                    normalized.add(normalize(document, branch, pointer + "/" + keyword + "/" + i, resolving));
                    i++;
                }
                composition.put(keyword, normalized);
            }
        }
        Normalized not =
                effective.has("not") ? normalize(document, effective.get("not"), pointer + "/not", resolving) : null;
        Normalized items = effective.has("items")
                ? normalize(document, effective.get("items"), pointer + "/items", resolving)
                : null;
        JsonNode additional = effective.get("additionalProperties");
        Normalized additionalProperties = additional != null && additional.isObject()
                ? normalize(document, additional, pointer + "/additionalProperties", resolving)
                : null;
        return new Normalized(
                types,
                properties.keySet(),
                properties,
                required,
                composition,
                not,
                items,
                additionalProperties,
                effective.path("readOnly").asBoolean(false),
                effective.path("writeOnly").asBoolean(false),
                null);
    }

    private static void compareNormalized(
            List<Difference> out, String id, String location, String pointer, Normalized m, Normalized r) {
        if (m.backReference() != null || r.backReference() != null) {
            if (!Objects.equals(m.backReference(), r.backReference())) {
                out.add(new Difference(id, location, pointer, "recursion", backReference(m), backReference(r)));
            }
            return;
        }
        add(out, id, location, pointer, "type", m.types(), r.types());
        add(out, id, location, pointer, "property-names", m.propertyNames(), r.propertyNames());
        add(out, id, location, pointer, "required", m.required(), r.required());
        String mComposition = composition(m);
        String rComposition = composition(r);
        if (!mComposition.equals(rComposition)) {
            out.add(new Difference(id, location, pointer, "composition", mComposition, rComposition));
        }
        if (m.readOnly() != r.readOnly()) {
            out.add(new Difference(
                    id, location, pointer, "readOnly", String.valueOf(m.readOnly()), String.valueOf(r.readOnly())));
        }
        if (m.writeOnly() != r.writeOnly()) {
            out.add(new Difference(
                    id, location, pointer, "writeOnly", String.valueOf(m.writeOnly()), String.valueOf(r.writeOnly())));
        }
        for (Map.Entry<String, Normalized> property : m.properties().entrySet()) {
            Normalized other = r.properties().get(property.getKey());
            if (other != null) {
                compareNormalized(
                        out, id, location, pointer + "/properties/" + property.getKey(), property.getValue(), other);
            }
        }
        for (Map.Entry<String, List<Normalized>> keyword : m.composition().entrySet()) {
            List<Normalized> other = r.composition().get(keyword.getKey());
            if (other != null) {
                for (int i = 0; i < Math.min(keyword.getValue().size(), other.size()); i++) {
                    compareNormalized(
                            out,
                            id,
                            location,
                            pointer + "/" + keyword.getKey() + "/" + i,
                            keyword.getValue().get(i),
                            other.get(i));
                }
            }
        }
        if (m.not() != null && r.not() != null) {
            compareNormalized(out, id, location, pointer + "/not", m.not(), r.not());
        }
        if (m.items() != null && r.items() != null) {
            compareNormalized(out, id, location, pointer + "/items", m.items(), r.items());
        } else if (m.items() != null || r.items() != null) {
            out.add(new Difference(
                    id,
                    location,
                    pointer + "/items",
                    "presence",
                    m.items() == null ? "absent" : "present",
                    r.items() == null ? "absent" : "present"));
        }
        if (m.additionalProperties() != null && r.additionalProperties() != null) {
            compareNormalized(
                    out,
                    id,
                    location,
                    pointer + "/additionalProperties",
                    m.additionalProperties(),
                    r.additionalProperties());
        }
    }

    private static void add(
            List<Difference> out,
            String id,
            String location,
            String pointer,
            String dimension,
            Set<String> maven,
            Set<String> runtime) {
        if (!maven.equals(runtime)) {
            out.add(new Difference(id, location, pointer, dimension, maven.toString(), runtime.toString()));
        }
    }

    private static String composition(Normalized schema) {
        List<String> parts = new ArrayList<>();
        schema.composition().forEach((keyword, branches) -> parts.add(keyword + "[" + branches.size() + "]"));
        if (schema.not() != null) {
            parts.add("not");
        }
        return parts.toString();
    }

    private static String backReference(Normalized schema) {
        return schema.backReference() == null ? "none" : "back-reference to " + schema.backReference();
    }

    private static JsonNode deref(JsonNode document, JsonNode node) {
        JsonNode current = node;
        for (int hops = 0; current.isObject() && current.has("$ref"); hops++) {
            if (hops > 32) {
                throw new IllegalArgumentException(
                        "the reference chain from '" + node.get("$ref").asText() + "' does not end");
            }
            current = resolve(document, current.get("$ref").asText());
        }
        return current;
    }

    private static String presence(@Nullable JsonNode node) {
        return node == null ? "absent" : "present";
    }

    private static String text(JsonNode node, String member, String where) {
        JsonNode value = node.get(member);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(where + " lacks the text member '" + member + "'");
        }
        return value.asText();
    }

    private static @Nullable String optionalText(JsonNode node, String member, String where) {
        JsonNode value = node.get(member);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(where + " has a non-text member '" + member + "'");
        }
        return value.asText();
    }

    private static boolean isIndex(String token) {
        return !token.isEmpty()
                && token.chars().allMatch(Character::isDigit)
                && (token.length() == 1 || token.charAt(0) != '0');
    }

    private static String percentDecode(String fragment, String reference) {
        if (fragment.indexOf('%') < 0) {
            return fragment;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < fragment.length(); i++) {
            char c = fragment.charAt(i);
            if (c == '%') {
                if (i + 2 >= fragment.length()) {
                    throw new IllegalArgumentException("the reference '" + reference + "' is malformed");
                }
                int hi = Character.digit(fragment.charAt(i + 1), 16);
                int lo = Character.digit(fragment.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new IllegalArgumentException("the reference '" + reference + "' is malformed");
                }
                bytes.write(hi * 16 + lo);
                i += 2;
            } else {
                byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /**
     * One reference target on the current normalization path. Targets are compared by identity,
     * because two equal schemas in different places are not recursion.
     *
     * @param target the resolved target
     * @param pointer the pointer in the normalized tree at which the target was entered
     * @param parent the enclosing frame, or {@code null}
     */
    private record Frame(
            JsonNode target, String pointer, @Nullable Frame parent) {}
}
