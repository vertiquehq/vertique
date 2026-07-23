// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.config;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds a nested {@link JsonObject} configuration tree from a flat map of dotted keys.
 *
 * <p>This is the inverse of the nested-tree traversal performed by {@link JsonConfigPaths}: it turns
 * the flat key/value pairs that the Spring/Quarkus host bridges expose ({@code Environment} property
 * names, SmallRye/MicroProfile config keys) into the nested {@link JsonObject} the framework's
 * {@link ConfigParser} and {@link JsonConfigPaths} consume. The bridges are the primary callers; the
 * builder lets both produce the <em>identical</em> nested tree from their respective flat-key shapes.
 *
 * <p><strong>The builder is purely syntactic — it does not know target types.</strong> Type coercion
 * (string → number/boolean/enum) is {@link ConfigParser}'s job downstream, so every <em>leaf value is
 * stored as a {@link String}</em>. The grammar below decides only the <em>shape</em> of the tree
 * (object vs. array vs. literal map key), never the type of a value.
 *
 * <h2>Key grammar (frozen)</h2>
 *
 * <p>A flat key is a path of segments. Segments are separated by {@code '.'} <em>except inside
 * brackets</em>. Each segment is classified as:
 * <ul>
 *   <li><strong>Bare segment</strong> → an <em>object key</em>. Bare numerics (e.g. {@code 2026}) are
 *       object keys, <em>never</em> array indices — config legitimately has open-map keys like a year
 *       or a host label.</li>
 *   <li><strong>{@code [N]}</strong> where {@code N} is a non-negative integer → an <em>array
 *       index</em>.</li>
 *   <li><strong>{@code [content]}</strong> where {@code content} is quoted ({@code ["http.server"]})
 *       <em>or</em> contains a dot / is a non-integer ({@code [http.server]}) → a <em>literal map
 *       key</em>, preserving inner dots. Surrounding quotes (single or double) are stripped.</li>
 * </ul>
 *
 * <h2>Examples</h2>
 *
 * <pre>{@code
 * {a.b=1, a.c=2}                                  -> {"a":{"b":"1","c":"2"}}
 * {kafka.consumers.foo.topic=t}                   -> {"kafka":{"consumers":{"foo":{"topic":"t"}}}}
 * {audit.capture.bindings[http.server].dimensions[0]=x}
 *                                                 -> {"audit":{"capture":{"bindings":
 *                                                       {"http.server":{"dimensions":["x"]}}}}}
 * {years.2026.total=5}                            -> {"years":{"2026":{"total":"5"}}}
 * {servers[0].host=h, servers[1].host=k}          -> {"servers":[{"host":"h"},{"host":"k"}]}
 * {tags[0]=a, tags[1]=b}                          -> {"tags":["a","b"]}
 * }</pre>
 *
 * <h2>Fail-fast rules (frozen)</h2>
 * <ul>
 *   <li><strong>Array indices</strong> for a given array must be present and <em>contiguous from
 *       {@code 0}</em>. A gap, a duplicate index, or mixing array-index {@code [N]} with
 *       object/map keys at the same node is a fail-fast {@link ConfigurationException} (the builder
 *       never silently pads with {@code null}s).</li>
 *   <li><strong>Leaf/parent collision</strong> — a path used as both a leaf and an object/array
 *       parent ({@code a.b=1} together with {@code a.b.c=2}) is a fail-fast
 *       {@link ConfigurationException}.</li>
 * </ul>
 *
 * <p><strong>Secret non-leakage.</strong> Every error message names the offending <em>keys/paths
 * only, never the values</em> — a config value can be a password or token, so values must never reach
 * a log or exception string.
 *
 * <p><strong>Order independence.</strong> The result does not depend on the input {@link Map}'s
 * iteration order: keys are processed in a stable sorted order so a collision such as
 * {@code {a.b=1, a.b.c=2}} is detected regardless of which key the map yields first.
 */
public final class ConfigTreeBuilder {

    private ConfigTreeBuilder() {}

    /**
     * Builds a nested {@link JsonObject} from a flat map of dotted/bracketed keys.
     *
     * <p>An empty (or empty-after-iteration) input yields an empty {@link JsonObject}. Leaf values are
     * stored verbatim as {@link String}s; a {@code null} value is stored as JSON {@code null}.
     *
     * @param flatKeys the flat key/value pairs (e.g. from a Spring {@code Environment} or a SmallRye
     *     config source); must not be {@code null}
     * @return the nested configuration tree
     * @throws ConfigurationException if a key is malformed, array indices are non-contiguous or
     *     conflict with map/object keys at the same node, or a path is used as both a leaf and a
     *     parent. The message names keys/paths only — never values.
     */
    public static JsonObject build(Map<String, String> flatKeys) {
        if (flatKeys == null) {
            throw new ConfigurationException("Flat config key map must not be null");
        }
        // Process keys in a stable sorted order so collision detection is independent of the input
        // map's iteration order (e.g. "a.b" vs "a.b.c" must collide regardless of which arrives first).
        Map<String, String> sorted = new TreeMap<>(flatKeys);

        TreeNode root = new TreeNode();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            String key = entry.getKey();
            List<Segment> segments = parseKey(key);
            insert(root, segments, entry.getValue(), key);
        }
        // An empty input leaves the root with no role markers set; materialize it as an empty object.
        return (JsonObject) materialize(root, "");
    }

    // --- Segment parsing ---

    /**
     * Classification of a single path segment.
     */
    private enum SegmentKind {
        /** A bare or bracketed literal key → object/map key. */
        KEY,
        /** A {@code [N]} bracketed non-negative integer → array index. */
        INDEX
    }

    /**
     * One parsed path segment.
     *
     * @param kind whether the segment addresses an object/map key or an array index
     * @param name the literal map/object key when {@code kind == KEY}; otherwise {@code null}
     * @param index the array index when {@code kind == INDEX}; otherwise {@code -1}
     */
    private record Segment(SegmentKind kind, String name, int index) {
        static Segment key(String name) {
            return new Segment(SegmentKind.KEY, name, -1);
        }

        static Segment index(int index) {
            return new Segment(SegmentKind.INDEX, null, index);
        }
    }

    /**
     * Parses a flat key into its ordered segments, splitting on {@code '.'} except inside brackets.
     *
     * <p>A bracketed group {@code [...]} is treated atomically: an enclosed dot does not split the
     * key. After splitting, each raw segment is classified into an object/map key or an array index.
     *
     * @param key the flat key
     * @return the ordered segments
     * @throws ConfigurationException if the key is blank, has an empty (dot-only) segment, or contains
     *     malformed brackets. The message names the key only.
     */
    private static List<Segment> parseKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ConfigurationException("Config key must not be blank");
        }
        List<Segment> segments = new ArrayList<>();
        int i = 0;
        int n = key.length();
        StringBuilder bare = new StringBuilder();
        // True immediately after a top-level '.' — the next char must begin a new segment, so a
        // trailing or doubled dot (which leaves this true with nothing following) is malformed.
        boolean pendingSegment = false;
        // True when the previous atom was a bracket group: a following '[' may then start the next
        // segment with no separating dot (e.g. "bindings[a][0]"), which is the only dot-optional case.
        boolean afterBracket = false;
        while (i < n) {
            char c = key.charAt(i);
            if (c == '.') {
                if (bare.length() > 0) {
                    segments.add(classifyBare(bare.toString(), key));
                    bare.setLength(0);
                } else if (!afterBracket) {
                    // A leading dot, a doubled dot, or a dot not preceded by an atom is an empty
                    // segment. (A dot right after a bracket — "[0].next" — is the benign boundary.)
                    throw new ConfigurationException("Config key '" + key + "' has an empty path segment");
                }
                pendingSegment = true;
                afterBracket = false;
                i++;
            } else if (c == '[') {
                // A bare prefix immediately before the bracket is its own object/map key segment.
                if (bare.length() > 0) {
                    segments.add(classifyBare(bare.toString(), key));
                    bare.setLength(0);
                }
                int close = key.indexOf(']', i);
                if (close < 0) {
                    throw new ConfigurationException("Config key '" + key + "' has an unclosed '[' bracket");
                }
                String content = key.substring(i + 1, close);
                segments.add(classifyBracket(content, key));
                i = close + 1;
                pendingSegment = false;
                afterBracket = true;
                // A bracket may be followed directly by a '.' (segment boundary) or another '[';
                // anything else (e.g. "[0]x") is malformed.
                if (i < n && key.charAt(i) != '.' && key.charAt(i) != '[') {
                    throw new ConfigurationException(
                            "Config key '" + key + "' has unexpected text after a ']' bracket");
                }
            } else if (c == ']') {
                throw new ConfigurationException("Config key '" + key + "' has an unmatched ']' bracket");
            } else {
                bare.append(c);
                pendingSegment = false;
                afterBracket = false;
                i++;
            }
        }
        if (bare.length() > 0) {
            segments.add(classifyBare(bare.toString(), key));
        } else if (pendingSegment) {
            // The key ended on a dangling '.' (e.g. "a." or "a.b.") — a trailing empty segment.
            throw new ConfigurationException("Config key '" + key + "' has an empty path segment");
        }
        if (segments.isEmpty()) {
            throw new ConfigurationException("Config key '" + key + "' has no path segments");
        }
        return segments;
    }

    /**
     * Classifies a bare (non-bracketed) segment. A bare segment is always an object key, even when it
     * is all-digits (e.g. a year {@code 2026}).
     *
     * @param raw the bare segment text
     * @param key the full key, for error context
     * @return the object-key segment
     * @throws ConfigurationException if the bare segment is empty
     */
    private static Segment classifyBare(String raw, String key) {
        if (raw.isEmpty()) {
            throw new ConfigurationException("Config key '" + key + "' has an empty path segment");
        }
        return Segment.key(raw);
    }

    /**
     * Classifies the content of a bracketed segment {@code [content]} as either an array index (a bare
     * non-negative integer) or a literal map key (quoted, dotted, or otherwise non-integer).
     *
     * @param content the text between the brackets (without the brackets)
     * @param key the full key, for error context
     * @return the index or literal-key segment
     * @throws ConfigurationException if the bracket content is empty
     */
    private static Segment classifyBracket(String content, String key) {
        if (content.isEmpty()) {
            throw new ConfigurationException("Config key '" + key + "' has an empty '[]' bracket");
        }
        // Quoted content is always a literal map key (quotes stripped), even if the inner text is
        // all-digits — quoting is the explicit "treat as key" signal.
        if (isQuoted(content)) {
            return Segment.key(content.substring(1, content.length() - 1));
        }
        // Unquoted, all-ASCII-digits, no leading-zero ambiguity beyond "0" → array index.
        if (isNonNegativeInteger(content)) {
            return Segment.index(Integer.parseInt(content));
        }
        // Otherwise (contains a dot, or is any non-integer text) → literal map key, dots preserved.
        return Segment.key(content);
    }

    /**
     * Returns whether the bracket content is wrapped in matching single or double quotes.
     *
     * @param content the bracket content
     * @return {@code true} if quoted on both ends with the same quote character
     */
    private static boolean isQuoted(String content) {
        if (content.length() < 2) {
            return false;
        }
        char first = content.charAt(0);
        char last = content.charAt(content.length() - 1);
        return (first == '"' || first == '\'') && first == last;
    }

    /**
     * Returns whether the text is a non-negative decimal integer that fits in an {@code int}. A
     * leading {@code '+'}, leading zeros (other than the single {@code "0"}), or overflow all disqualify
     * it — such bracket content is treated as a literal map key instead.
     *
     * @param text the candidate text
     * @return {@code true} if {@code text} is a canonical non-negative {@code int} literal
     */
    private static boolean isNonNegativeInteger(String text) {
        int len = text.length();
        for (int j = 0; j < len; j++) {
            char ch = text.charAt(j);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        // Reject leading zeros ("01") so they round-trip as the literal key they were written as.
        if (len > 1 && text.charAt(0) == '0') {
            return false;
        }
        try {
            Integer.parseInt(text);
            return true;
        } catch (NumberFormatException overflow) {
            return false;
        }
    }

    // --- Tree insertion ---

    /**
     * A mutable intermediate tree node. Exactly one of {@code object}/{@code array}/{@code leaf} is
     * populated once the node's role is decided by the segment that addresses it; an attempt to use it
     * in two conflicting roles raises a {@link ConfigurationException} (the collision rule).
     */
    private static final class TreeNode {
        // Object/map children, keyed by literal map/object key.
        private final Map<String, TreeNode> object = new TreeMap<>();
        // Array children, keyed by index (sparse during build; contiguity checked at the end).
        private final Map<Integer, TreeNode> array = new TreeMap<>();
        // Leaf value (a String, or null for an explicit-null value).
        private String leaf;
        // Role markers; a node may only ever take one role.
        private boolean isObject;
        private boolean isArray;
        private boolean isLeaf;
    }

    /**
     * Inserts one flat entry's value at the path described by {@code segments}.
     *
     * @param root the tree root
     * @param segments the parsed path segments (non-empty)
     * @param value the leaf value to store (stored verbatim as a {@link String}; {@code null} allowed)
     * @param key the full flat key, for error context
     * @throws ConfigurationException on a leaf/parent collision or an object/array role conflict at any
     *     node along the path. The message names keys/paths only — never values.
     */
    private static void insert(TreeNode root, List<Segment> segments, String value, String key) {
        TreeNode current = root;
        for (int s = 0; s < segments.size(); s++) {
            Segment seg = segments.get(s);
            boolean last = s == segments.size() - 1;
            current = descend(current, seg, last, key);
            if (last) {
                if (current.isObject || current.isArray) {
                    // The leaf path is already an object/array parent (a sibling key extended past it).
                    throw collision(key);
                }
                current.isLeaf = true;
                current.leaf = value;
            }
        }
    }

    /**
     * Descends one level into the tree for {@code seg}, materializing the child node and asserting the
     * parent's role (object vs. array) is consistent with the segment kind.
     *
     * @param parent the current node
     * @param seg the segment addressing the child
     * @param leafSegment whether this segment is the last in the path (so the child is a leaf)
     * @param key the full flat key, for error context
     * @return the child node addressed by {@code seg}
     * @throws ConfigurationException if {@code parent} is already a leaf, or its array/object role
     *     conflicts with {@code seg}'s kind. The message names keys/paths only.
     */
    private static TreeNode descend(TreeNode parent, Segment seg, boolean leafSegment, String key) {
        if (parent.isLeaf) {
            // The parent path was already assigned a scalar leaf by a shorter sibling key.
            throw collision(key);
        }
        if (seg.kind() == SegmentKind.KEY) {
            if (parent.isArray) {
                // A map/object key cannot share a node with array indices.
                throw mixedNode(key);
            }
            parent.isObject = true;
            return parent.object.computeIfAbsent(seg.name(), k -> new TreeNode());
        } else { // INDEX
            if (parent.isObject) {
                // An array index cannot share a node with map/object keys.
                throw mixedNode(key);
            }
            parent.isArray = true;
            return parent.array.computeIfAbsent(seg.index(), k -> new TreeNode());
        }
    }

    // --- Materialization ---

    /**
     * Materializes an intermediate node into its final JSON form: a {@link JsonObject}, a
     * {@link JsonArray}, or a leaf {@link String}/{@code null}.
     *
     * @param node the node to materialize
     * @param path the path to this node, for array-contiguity error context (keys only — never values)
     * @return the JSON value for this node
     * @throws ConfigurationException if an array's indices are not contiguous from {@code 0}
     */
    private static Object materialize(TreeNode node, String path) {
        if (node.isLeaf) {
            return node.leaf;
        }
        if (node.isArray) {
            return toArray(node, path);
        }
        // Object (including the empty-root case).
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, TreeNode> e : node.object.entrySet()) {
            String childPath = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
            obj.put(e.getKey(), materialize(e.getValue(), childPath));
        }
        return obj;
    }

    /**
     * Materializes an array node, validating that its indices are present and contiguous from
     * {@code 0}.
     *
     * @param node the array node
     * @param path the path to the array, for error context
     * @return the {@link JsonArray}
     * @throws ConfigurationException if an index is missing (a gap) so the array would need
     *     {@code null} padding
     */
    private static JsonArray toArray(TreeNode node, String path) {
        JsonArray arr = new JsonArray();
        int expected = 0;
        for (Map.Entry<Integer, TreeNode> e : node.array.entrySet()) {
            int idx = e.getKey();
            if (idx != expected) {
                // A gap or out-of-order index; never silently pad with nulls.
                throw new ConfigurationException("Config array at path '" + path
                        + "' has non-contiguous indices: expected [" + expected + "] but found [" + idx + "]");
            }
            arr.add(materialize(e.getValue(), path + "[" + idx + "]"));
            expected++;
        }
        return arr;
    }

    // --- Error helpers (keys/paths only — never values) ---

    /**
     * Builds the leaf/parent collision exception, naming the offending key only.
     *
     * @param key the flat key whose path collides with an existing leaf/parent
     * @return the exception
     */
    private static ConfigurationException collision(String key) {
        return new ConfigurationException("Config key '" + key
                + "' collides with another key: a path is used as both a leaf value and an object/array parent");
    }

    /**
     * Builds the mixed-node exception (array index and map/object key at the same node), naming the
     * offending key only.
     *
     * @param key the flat key that mixes an array index with a map/object key at one node
     * @return the exception
     */
    private static ConfigurationException mixedNode(String key) {
        return new ConfigurationException(
                "Config key '" + key + "' mixes array indices and object/map keys at the same node");
    }
}
