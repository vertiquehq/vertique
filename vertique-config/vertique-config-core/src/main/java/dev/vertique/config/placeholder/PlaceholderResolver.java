// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.placeholder;

import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.config.source.ConfigPropertySourceException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Progressive placeholder resolution engine for the vertique-config bootstrap pipeline.
 *
 * <h2>Entry points</h2>
 * <ul>
 *   <li>{@link #resolveAgainstTree(JsonObject, JsonObject)} — tree-only mode used for the
 *       {@code config.propertySources} bootstrap subtree (pass 1). Only tree references are
 *       permitted; any unresolved reference throws {@link PlaceholderResolutionException} whose
 *       message explains that bootstrap subtrees may use tree references only.</li>
 *   <li>{@link #resolveAgainstTree(JsonArray, JsonObject)} — tree-only mode for an array subtree;
 *       same failure semantics as the object variant.</li>
 *   <li>{@link #resolveTree(JsonObject, List)} — full-chain mode for the whole config tree (pass
 *       3). Each string value is resolved against the tree first, then declared sources in order,
 *       first hit wins.</li>
 * </ul>
 *
 * <h2>Algorithm summary</h2>
 * <ol>
 *   <li><strong>Deep copy</strong> — the input tree is never mutated; the method returns a new
 *       {@link JsonObject}.</li>
 *   <li><strong>Tree walk</strong> — every {@link String} value in the tree is resolved; nested
 *       {@link JsonObject} and {@link JsonArray} are recursed in insertion / index order;
 *       non-string types pass through unchanged.</li>
 *   <li><strong>WHOLE-VALUE mode vs CONCAT mode</strong> — when a string value parses to
 *       exactly one {@link PlaceholderParser.Placeholder} segment (nothing else), the resolved
 *       typed value is preserved (an {@link Integer} from the tree stays {@link Integer}). When
 *       there are multiple segments or surrounding literals, every resolved part is stringified
 *       via {@link TreeLookup#stringify(Object)} and the parts are concatenated. When the
 *       resolved typed value is itself a {@link JsonObject} or {@link JsonArray} (a <em>container
 *       ref</em>), the container is walked recursively — resolving any nested placeholders — and
 *       a copy is returned rather than the original input object.</li>
 *   <li><strong>Resolution chain per key</strong> — see {@link ResolutionContext#resolve}.</li>
 *   <li><strong>Aggregate failures</strong> — all unresolved references are collected; the walk
 *       does not stop at the first failure. At the end, if any failures exist a
 *       {@link PlaceholderResolutionException} is thrown with a sorted, de-duplicated list.</li>
 * </ol>
 *
 * <h2>NFR-CONF-002</h2>
 * <p>No resolved config <em>value</em> appears in any log message, exception message,
 * {@code toString()}, or other observable output produced by this class. Only keys and chain
 * renderings (using {@code ->} notation) are ever exposed.
 *
 * @see PlaceholderParser
 * @see TreeLookup
 * @see PlaceholderResolutionException
 */
public final class PlaceholderResolver {

    /** Private constructor — utility class with no instances. */
    private PlaceholderResolver() {}

    // --- Public constants ---

    /**
     * Maximum nested-resolution depth backstop.
     *
     * <p>When the resolution stack depth exceeds this value while resolving a string placeholder
     * chain in the config tree, the reference is recorded as an unresolved failure and the raw
     * {@code ${...}} text is used in place of the resolved value. This prevents infinite recursion
     * on deep or cyclic (but not immediately detectable) chains.
     */
    public static final int MAX_DEPTH = 5;

    // --- Public API ---

    /**
     * Resolves all {@code ${...}} placeholders in {@code value} against {@code tree} alone,
     * returning a new {@link JsonObject} with all resolvable references substituted.
     *
     * <p>This is the <em>bootstrap-subtree</em> variant used for pass 1 resolution (e.g.,
     * {@code config.propertySources} declarations). No external {@link ConfigPropertySource}
     * instances are consulted — the tree is the only source. Any reference that cannot be
     * resolved from the tree is recorded as a failure and will cause a
     * {@link PlaceholderResolutionException} whose message states that bootstrap subtrees may
     * use tree references only.
     *
     * @param value the subtree to resolve; must not be {@code null}; not mutated
     * @param tree  the full config tree to resolve against; must not be {@code null}
     * @return a new {@link JsonObject} with all resolvable placeholders substituted
     * @throws PlaceholderResolutionException if any placeholder reference could not be resolved
     *                                        from the tree
     */
    public static JsonObject resolveAgainstTree(JsonObject value, JsonObject tree) {
        ResolutionContext ctx = new ResolutionContext(tree, List.of(), true);
        JsonObject result = ctx.walkObject(value, 0);
        ctx.throwIfFailures();
        return result;
    }

    /**
     * Resolves all {@code ${...}} placeholders in {@code value} against {@code tree} alone,
     * returning a new {@link JsonArray} with all resolvable references substituted.
     *
     * <p>This is the array-subtree variant of
     * {@link #resolveAgainstTree(JsonObject, JsonObject)}: it resolves an array directly without
     * requiring the caller to wrap and unwrap it in a single-key object. The failure semantics are
     * identical — any reference that cannot be resolved from the tree throws a
     * {@link PlaceholderResolutionException} with the "tree references only" message.
     *
     * @param value the array to resolve; must not be {@code null}; not mutated
     * @param tree  the full config tree to resolve against; must not be {@code null}
     * @return a new {@link JsonArray} with all resolvable placeholders substituted
     * @throws PlaceholderResolutionException if any placeholder reference could not be resolved
     *                                        from the tree
     */
    public static JsonArray resolveAgainstTree(JsonArray value, JsonObject tree) {
        ResolutionContext ctx = new ResolutionContext(tree, List.of(), true);
        JsonArray result = ctx.walkArray(value, 0);
        ctx.throwIfFailures();
        return result;
    }

    /**
     * Resolves all {@code ${...}} placeholders in {@code tree} using the full resolution chain:
     * tree first (flat-key-first probe per {@link TreeLookup}), then each {@link ConfigPropertySource}
     * in declared order, first non-empty hit wins.
     *
     * <p>Returns a <strong>new</strong> {@link JsonObject}; the input {@code tree} is never
     * mutated. Every {@link String} value is resolved; nested {@link JsonObject} and
     * {@link JsonArray} are recursed; other types pass through unchanged.
     *
     * <p>Failures are accumulated across the whole walk and thrown as a single
     * {@link PlaceholderResolutionException} at the end.
     *
     * @param tree    the config tree to resolve; must not be {@code null}; not mutated
     * @param sources the ordered list of property sources to consult after the tree probe;
     *                must not be {@code null}
     * @return a new {@link JsonObject} with all resolvable placeholders substituted
     * @throws PlaceholderResolutionException    if any reference could not be resolved after
     *                                           exhausting all sources
     * @throws ConfigPropertySourceException if a source signals an unrecoverable lookup error;
     *                                        propagated immediately without aggregation
     */
    public static JsonObject resolveTree(JsonObject tree, List<ConfigPropertySource> sources) {
        ResolutionContext ctx = new ResolutionContext(tree, sources, false);
        JsonObject result = ctx.walkObject(tree, 0);
        ctx.throwIfFailures();
        return result;
    }

    // --- Package-private resolution context ---

    /**
     * Holds all mutable state for a single resolution pass: the config tree, the ordered source
     * list, per-(source,key) memoization, the resolution stack (for cycle detection), and the
     * failure accumulator.
     *
     * <p>All method calls are single-threaded; no synchronization is required.
     *
     * <h2>NFR-CONF-002</h2>
     * <p>Resolved values are never stored in, or retrieved from, the failure set or the
     * resolution stack. The stack contains only key names; failures contain only key names or
     * chain renderings.
     */
    private static final class ResolutionContext {

        /** The full config tree (frozen for the lifetime of this walk). */
        private final JsonObject tree;

        /** Ordered list of property sources to consult after the tree probe. */
        private final List<ConfigPropertySource> sources;

        /**
         * Whether this context operates in bootstrap (tree-only) mode.
         *
         * <p>When {@code true}, the resolution stops at the tree and unresolved references are
         * annotated with the "bootstrap subtrees may use tree references only" message.
         */
        private final boolean bootstrapMode;

        /**
         * Memoization table: {@code (source-index, key) → resolved-string-or-absent}.
         *
         * <p>A {@link Optional#empty()} entry means the source was consulted and returned
         * not-found. A non-empty entry is the memoized string value. A missing entry means the
         * source has not yet been consulted for this key.
         */
        private final Map<MemoKey, Optional<String>> sourceMemo = new HashMap<>();

        /**
         * Resolution stack — keys currently being resolved.
         *
         * <p>Used for self-reference and cycle detection. A key on the stack means we are
         * currently in the middle of resolving it, so a recursive probe for the same key is a
         * cycle and must fall through to sources instead of looping. When resolving a whole-value
         * container ref, the referencing key is also pushed while the container is being walked,
         * so that any placeholder inside the container that refers back to the same key is
         * detected as a cycle.
         */
        private final ArrayDeque<String> stack = new ArrayDeque<>();

        /**
         * Accumulated failure set — reference renderings only (keys, chain strings).
         *
         * <p>A {@link TreeSet} ensures sorted, de-duplicated iteration at exception time.
         */
        private final TreeSet<String> failures = new TreeSet<>();

        /**
         * Constructs a new context for a single resolution pass.
         *
         * @param tree          the config tree; never {@code null}
         * @param sources       the ordered source list; never {@code null}
         * @param bootstrapMode {@code true} to restrict resolution to tree-only
         */
        ResolutionContext(JsonObject tree, List<ConfigPropertySource> sources, boolean bootstrapMode) {
            this.tree = tree;
            this.sources = sources;
            this.bootstrapMode = bootstrapMode;
        }

        // --- Tree walking ---

        /**
         * Produces a new {@link JsonObject} with every {@link String} value resolved.
         * Nested objects and arrays are recursed. Non-string types are copied as-is.
         *
         * @param obj   the object to walk; must not be {@code null}
         * @param depth the current recursion depth for string resolution
         * @return a new {@link JsonObject} with resolved values
         */
        JsonObject walkObject(JsonObject obj, int depth) {
            JsonObject result = new JsonObject();
            for (String key : obj.fieldNames()) {
                Object raw = obj.getValue(key);
                result.put(key, walkValue(raw, depth));
            }
            return result;
        }

        /**
         * Produces a new {@link JsonArray} with every element resolved.
         *
         * @param arr   the array to walk; must not be {@code null}
         * @param depth the current recursion depth (passed through for string resolution)
         * @return a new {@link JsonArray} with resolved elements
         */
        JsonArray walkArray(JsonArray arr, int depth) {
            JsonArray result = new JsonArray();
            for (int i = 0; i < arr.size(); i++) {
                result.add(walkValue(arr.getValue(i), depth));
            }
            return result;
        }

        /**
         * Dispatches resolution for a single config value.
         *
         * <ul>
         *   <li>{@link String} — resolved via {@link #resolveString}.</li>
         *   <li>{@link JsonObject} — recursed via {@link #walkObject}.</li>
         *   <li>{@link JsonArray} — recursed via {@link #walkArray}.</li>
         *   <li>Other types (numerics, booleans, {@code null}) — returned unchanged.</li>
         * </ul>
         *
         * @param value the raw value from the config tree; may be {@code null}
         * @param depth the current nesting depth for string resolution
         * @return the resolved / recursed value
         */
        private Object walkValue(Object value, int depth) {
            if (value instanceof String s) {
                return resolveString(s, depth);
            }
            if (value instanceof JsonObject obj) {
                return walkObject(obj, depth);
            }
            if (value instanceof JsonArray arr) {
                return walkArray(arr, depth);
            }
            // Integer, Long, Double, Boolean, null — pass through
            return value;
        }

        // --- String resolution ---

        /**
         * Resolves all placeholders in a single string value.
         *
         * <p>If the string has no placeholders (no {@link PlaceholderParser.Placeholder} or
         * {@link PlaceholderParser.Malformed} segments), the original string is returned
         * unchanged.
         *
         * <p>If the parsed result is exactly one {@link PlaceholderParser.Placeholder} segment
         * and nothing else (WHOLE-VALUE mode), the resolved typed value is returned directly —
         * allowing a tree {@link Integer} or {@link JsonObject} to survive the placeholder
         * without being stringified.
         *
         * <p>Otherwise (CONCAT mode) every segment is resolved and stringified, then
         * concatenated into a single {@link String}.
         *
         * @param value the config string to process; must not be {@code null}
         * @param depth the current resolution depth
         * @return the resolved value; may be a non-{@link String} in WHOLE-VALUE mode
         */
        private Object resolveString(String value, int depth) {
            List<PlaceholderParser.Segment> segments = PlaceholderParser.parse(value);

            // No placeholder/malformed segments AND no escape sequences → return unchanged.
            // We detect "no escape transformation" by checking if all segments are Literals
            // whose concatenation equals the original string (i.e., no \${ was processed).
            // The simpler equivalent: if hasPlaceholder is false AND the string contains no
            // escape trigger sequence, the parsed text equals the input.
            boolean hasPlaceholder = segments.stream().anyMatch(s -> !(s instanceof PlaceholderParser.Literal));
            if (!hasPlaceholder && !value.contains("\\${")) {
                return value;
            }

            // WHOLE-VALUE mode: single Placeholder segment (no surrounding literals)
            if (segments.size() == 1 && segments.get(0) instanceof PlaceholderParser.Placeholder ph) {
                return resolve(ph.key(), ph.defaultValue(), depth);
            }

            // CONCAT mode: assemble from parsed segments (handles escape expansion + resolution)
            StringBuilder sb = new StringBuilder();
            for (PlaceholderParser.Segment seg : segments) {
                switch (seg) {
                    case PlaceholderParser.Literal lit -> sb.append(lit.text());
                    case PlaceholderParser.Placeholder ph -> {
                        Object resolved = resolve(ph.key(), ph.defaultValue(), depth);
                        sb.append(TreeLookup.stringify(resolved));
                    }
                    case PlaceholderParser.Malformed mal -> {
                        // Record malformed as unresolved; pass the raw text through
                        recordFailure(mal.rawText());
                        sb.append(mal.rawText());
                    }
                }
            }
            return sb.toString();
        }

        // --- Key resolution ---

        /**
         * Resolves a single placeholder key following the five-step algorithm:
         *
         * <ol>
         *   <li><strong>Depth check</strong> — if the resolution stack exceeds {@link #MAX_DEPTH},
         *       record a failure with a depth note and return the raw {@code ${...}} text.</li>
         *   <li><strong>Tree probe</strong> — consult {@link TreeLookup#find} unless the key is
         *       currently on the resolution stack (cycle / self-reference). A string result with
         *       placeholders is recursively resolved; a {@link JsonObject} or {@link JsonArray}
         *       value causes the key to be pushed on the stack, a walked copy of the container to
         *       be returned, and the key to be popped — so that any placeholder inside the
         *       container that refers back to the same key is detected as a cycle. A plain string
         *       or non-string scalar is returned directly (type preserved).</li>
         *   <li><strong>Sources in declared order, memoized</strong> — each source is consulted
         *       at most once per key. A {@link ConfigPropertySourceException} propagates
         *       immediately.</li>
         *   <li><strong>Default</strong> — if a default value text was provided and all sources
         *       missed, the default is parsed and resolved with the same stack at
         *       {@code depth + 1}.</li>
         *   <li><strong>Failure</strong> — record the unresolved reference and return the raw
         *       {@code ${...}} placeholder text in place.</li>
         * </ol>
         *
         * @param key          the placeholder key; never {@code null}
         * @param defaultValue the default text, or {@code null} if no default declared
         * @param depth        the current resolution depth (number of recursive calls on the
         *                     current resolution path)
         * @return the resolved value; may be any JSON-compatible type from the tree, a
         *         {@link String} from a source, a default-resolved value, or the raw
         *         {@code ${key}} text when unresolved
         */
        private Object resolve(String key, String defaultValue, int depth) {
            String rawRef = "${" + key + "}";

            // --- Step 1: depth check ---
            if (depth > MAX_DEPTH) {
                String chain = buildChainRendering(key);
                recordFailure(chain + " (max depth " + MAX_DEPTH + " exceeded)");
                return rawRef;
            }

            // --- Step 2: tree probe (only if key is not on the resolution stack) ---
            if (!stack.contains(key)) {
                Optional<Object> treeResult = TreeLookup.find(tree, key);
                if (treeResult.isPresent()) {
                    Object treeVal = treeResult.get();
                    if (treeVal instanceof String s && PlaceholderParser.containsPlaceholder(s)) {
                        // Recursively resolve the string value found in the tree
                        stack.push(key);
                        try {
                            return resolveString(s, depth + 1);
                        } finally {
                            stack.pop();
                        }
                    }
                    if (treeVal instanceof JsonObject obj) {
                        // Walk a copy so nested placeholders are resolved and the input is never mutated.
                        // Push the key so that any placeholder inside the container that refers back
                        // to this key is detected as a cycle rather than looping.
                        stack.push(key);
                        try {
                            return walkObject(obj.copy(), depth + 1);
                        } finally {
                            stack.pop();
                        }
                    }
                    if (treeVal instanceof JsonArray arr) {
                        // Same logic as JsonObject: walk a copy with the key on the stack.
                        stack.push(key);
                        try {
                            return walkArray(arr.copy(), depth + 1);
                        } finally {
                            stack.pop();
                        }
                    }
                    // Non-string scalar or plain string — return directly (type preserved)
                    return treeVal;
                }
            }

            // --- Step 3: sources in declared order, memoized ---
            if (!bootstrapMode) {
                for (int i = 0; i < sources.size(); i++) {
                    ConfigPropertySource source = sources.get(i);
                    MemoKey memoKey = new MemoKey(i, key);
                    Optional<String> cached = sourceMemo.get(memoKey);
                    if (cached == null) {
                        // ConfigPropertySourceException propagates immediately — uncaught
                        Optional<String> looked = source.lookup(key);
                        sourceMemo.put(memoKey, looked);
                        cached = looked;
                    }
                    if (cached.isPresent()) {
                        // Source values are treated as literal — never re-parsed
                        return cached.get();
                    }
                }
            }

            // --- Step 4: default ---
            if (defaultValue != null) {
                // Parse and resolve the default text at depth+1; defaults may contain placeholders
                return resolveString(defaultValue, depth + 1);
            }

            // --- Step 5: record failure ---
            recordFailure(buildChainRendering(key));
            return rawRef;
        }

        // --- Helpers ---

        /**
         * Builds a chain rendering for a depth-exceeded or unresolved failure.
         *
         * <p>The current stack contents (plus the new key) form the chain, rendered as
         * {@code "outer -> ... -> key"} using only key names — never values.
         *
         * @param key the key that triggered the failure or depth overflow
         * @return a chain rendering string; never contains resolved values
         */
        private String buildChainRendering(String key) {
            if (stack.isEmpty()) {
                return key;
            }
            // Stack is LIFO: peek returns the most recent pusher; we want oldest→newest→key
            List<String> chain = new ArrayList<>(stack);
            Collections.reverse(chain);
            chain.add(key);
            return String.join(" -> ", chain);
        }

        /**
         * Adds a reference rendering to the failure accumulator.
         *
         * @param rendering a key name or chain rendering; must not contain resolved values
         */
        private void recordFailure(String rendering) {
            failures.add(rendering);
        }

        /**
         * Throws a {@link PlaceholderResolutionException} if any failures were recorded during
         * the walk.
         *
         * <p>In bootstrap mode the message includes the phrase "bootstrap subtrees may use tree
         * references only" to guide the operator.
         *
         * @throws PlaceholderResolutionException if {@link #failures} is non-empty
         */
        void throwIfFailures() {
            if (failures.isEmpty()) {
                return;
            }
            List<String> sorted = List.copyOf(failures);
            String message;
            if (bootstrapMode) {
                message = "Placeholder resolution failed for bootstrap subtree: "
                        + sorted
                        + ". Bootstrap subtrees may use tree references only — "
                        + "external property sources are not available during this pass.";
            } else {
                message = "Placeholder resolution failed: unresolved references " + sorted;
            }
            throw new PlaceholderResolutionException(message, sorted);
        }

        /**
         * Composite memoization key: source index + the exact config key. A record gives true
         * equality semantics — hash collisions between different key strings can never
         * cross-contaminate memoized values.
         *
         * <p>Component params: {@code sourceIndex} — zero-based index of the source in the
         * declared list; {@code key} — the config key being looked up.
         */
        private record MemoKey(int sourceIndex, String key) {}
    }
}
