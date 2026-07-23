// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import dev.vertique.core.exception.MalformedDurableMetadataException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, namespaced durable <em>context</em> document carried across durable boundaries (outbox,
 * Kafka, delayed-job, workflow). Top-level keys are short namespace names owned by exactly one
 * context type (e.g. {@code "correlation"}, {@code "localization"}); each value is that type's JSON
 * body. This is the content of the {@code context} section of a durable carrier — it carries no
 * delivery/transport data.
 *
 * <p>The document is backed internally by a deep-copied {@link JsonObject} that is never exposed by
 * reference. Every factory and accessor that crosses the boundary ({@link #of}, {@link #with},
 * {@link #fromJson}, {@link #fromCarrier}, {@link #body}, {@link #toJson}, {@link #toCarrier})
 * performs a recursive deep copy via {@link JsonObject#copy()}, so the {@link JsonObject#getMap()}
 * live-aliasing trap cannot leak: mutating an input after construction, or mutating a returned
 * object, never affects the stored value. {@code JsonObject} never appears on the durable encoder /
 * decoder SPI method signatures — those exchange {@code DurableMetadata}.
 *
 * <h2>Carrier wrapper</h2>
 *
 * <p>Every durable JSONB carrier nests this document under the {@link #CONTEXT_KEY} key:
 * {@code { "context": { "correlation": {…}, "localization": {…} } }}. Use {@link #toCarrier()} /
 * {@link #fromCarrier(JsonObject)} for the wrapped form and {@link #toJson()} / {@link #fromJson}
 * for the bare namespaces object.
 */
public final class DurableMetadata {

    /** Carrier wrapper key under which the namespaces document is nested in a durable JSONB carrier. */
    public static final String CONTEXT_KEY = "context";

    /**
     * Maximum number of namespaces permitted in a decoded document. Defense-in-depth bound per
     * ADR-0147 — far above any legitimate durable-context namespace count (the framework registers
     * on the order of single digits of namespace types), so no legitimate caller can trip it; the
     * bound exists solely to cap the cost of decoding a malformed or adversarial carrier. Not
     * exposed as configuration.
     */
    static final int MAX_NAMESPACES = 64;

    /**
     * Maximum encoded size, in bytes, permitted for a decoded namespaces document. Defense-in-depth
     * bound per ADR-0147 — 256 KiB is far above any legitimate durable-context payload (correlation
     * IDs, locale tags, tenant identifiers), so no legitimate caller can trip it; the bound exists
     * solely to cap the cost of decoding a malformed or adversarial carrier. Not exposed as
     * configuration.
     */
    static final int MAX_ENCODED_SIZE_BYTES = 262_144;

    /**
     * Maximum nesting depth permitted within a single namespace body. Defense-in-depth bound per
     * ADR-0147 — 32 levels is far above any legitimate durable-context body shape, so no legitimate
     * caller can trip it; the bound exists solely to cap the cost of decoding a malformed or
     * adversarial carrier. Enforced via an iterative stack walk (never recursive) so the bound
     * check itself cannot stack-overflow on an adversarial input. Not exposed as configuration.
     */
    static final int MAX_NESTING_DEPTH = 32;

    /** Policy for resolving a namespace present on both sides of a {@link #merge(DurableMetadata, MergePolicy)}. */
    public enum MergePolicy {
        /** Keep the receiver's namespace body; drop the other side's. */
        CALLER_WINS,
        /** Throw {@link IllegalStateException} when a namespace is present on both sides. */
        FAIL_ON_CONFLICT
    }

    /** Owned, deep-copied namespaces document ({@code {namespace: body}}); never exposed by reference. */
    private final JsonObject namespaces;

    private DurableMetadata(JsonObject namespaces) {
        this.namespaces = namespaces;
    }

    /**
     * Returns an empty document with no namespaces.
     *
     * @return an empty {@code DurableMetadata}
     */
    public static DurableMetadata empty() {
        return new DurableMetadata(new JsonObject());
    }

    /**
     * Returns a document containing a single namespace.
     *
     * @param namespace the namespace name; must not be {@code null} or blank
     * @param body      the namespace body; deep-copied in, must not be {@code null}
     * @return a single-namespace document
     * @throws IllegalArgumentException if {@code namespace} is blank
     * @throws NullPointerException     if {@code body} is {@code null}
     */
    public static DurableMetadata of(String namespace, JsonObject body) {
        requireNamespace(namespace);
        Objects.requireNonNull(body, "body");
        return new DurableMetadata(new JsonObject().put(namespace, body.copy()));
    }

    /**
     * Reconstructs a document from a bare namespaces object (deep-copied in).
     *
     * <p>Every namespace body is validated to be a JSON object; a non-object body is rejected here,
     * at decode time, rather than surfacing later as an unclassified {@link ClassCastException} when
     * a specific decoder or {@link #merge} reads the malformed namespace.
     *
     * <p>Three defense-in-depth bounds are enforced, cheapest first: namespace count
     * ({@link #MAX_NAMESPACES}), nesting depth within each namespace body
     * ({@link #MAX_NESTING_DEPTH}, checked via an iterative stack walk), and total encoded size
     * ({@link #MAX_ENCODED_SIZE_BYTES}, computed with a single {@link JsonObject#toBuffer()} pass).
     * See ADR-0147 — these bounds are far above any legitimate use and exist only to cap the cost of
     * decoding a malformed or adversarial carrier.
     *
     * @param namespaces the {@code {namespace: body}} object; {@code null} yields {@link #empty()}
     * @return the reconstructed document
     * @throws MalformedDurableMetadataException if any namespace body is present but is not a JSON
     *                                            object, or if any defense-in-depth bound is
     *                                            exceeded
     */
    public static DurableMetadata fromJson(JsonObject namespaces) {
        if (namespaces == null) {
            return empty();
        }
        if (namespaces.size() > MAX_NAMESPACES) {
            throw new MalformedDurableMetadataException(
                    "durable context document exceeds the maximum of " + MAX_NAMESPACES + " namespaces");
        }
        for (String key : namespaces.fieldNames()) {
            // getValue() normalizes the stored value (which may be a raw java.util.Map or an
            // already-wrapped JsonObject, depending on how the JsonObject was constructed) to a
            // JsonObject for nested-object entries, so this check is storage-shape-independent.
            Object value = namespaces.getValue(key);
            if (!(value instanceof JsonObject body)) {
                throw new MalformedDurableMetadataException("durable context namespace '" + key
                        + "' must be a JSON object, but was "
                        + (value == null ? "null" : value.getClass().getSimpleName()));
            }
            requireDepthWithinBound(body, key);
        }
        requireSizeWithinBound(namespaces);
        return new DurableMetadata(namespaces.copy());
    }

    /**
     * Walks {@code body} iteratively (never recursively, so an adversarial input cannot
     * stack-overflow the bound check itself) and rejects it if any nested {@link JsonObject} or
     * {@link JsonArray} value is nested more than {@link #MAX_NESTING_DEPTH} levels below {@code
     * body} itself.
     *
     * @param body      the namespace body to walk
     * @param namespace the owning namespace name, used only in the exception message
     * @throws MalformedDurableMetadataException if the nesting depth bound is exceeded
     */
    private static void requireDepthWithinBound(JsonObject body, String namespace) {
        Deque<Object> stack = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        stack.push(body);
        depths.push(0);
        while (!stack.isEmpty()) {
            Object current = stack.pop();
            int depth = depths.pop();
            if (depth > MAX_NESTING_DEPTH) {
                throw new MalformedDurableMetadataException("durable context namespace '" + namespace
                        + "' body nesting exceeds the maximum depth of " + MAX_NESTING_DEPTH);
            }
            if (current instanceof JsonObject obj) {
                for (String field : obj.fieldNames()) {
                    Object value = obj.getValue(field);
                    if (value instanceof JsonObject || value instanceof JsonArray) {
                        stack.push(value);
                        depths.push(depth + 1);
                    }
                }
            } else if (current instanceof JsonArray array) {
                for (int i = 0; i < array.size(); i++) {
                    Object value = array.getValue(i);
                    if (value instanceof JsonObject || value instanceof JsonArray) {
                        stack.push(value);
                        depths.push(depth + 1);
                    }
                }
            }
        }
    }

    /**
     * Rejects {@code namespaces} if its encoded size exceeds {@link #MAX_ENCODED_SIZE_BYTES}.
     * Computed with a single {@link JsonObject#toBuffer()} pass over the whole document.
     *
     * @param namespaces the namespaces document to measure
     * @throws MalformedDurableMetadataException if the encoded size bound is exceeded
     */
    private static void requireSizeWithinBound(JsonObject namespaces) {
        if (namespaces.toBuffer().length() > MAX_ENCODED_SIZE_BYTES) {
            throw new MalformedDurableMetadataException("durable context document exceeds the maximum encoded size of "
                    + MAX_ENCODED_SIZE_BYTES + " bytes");
        }
    }

    /**
     * Reconstructs a document from a durable JSONB carrier by reading its {@link #CONTEXT_KEY} section.
     *
     * <p>When the carrier contains a {@link #CONTEXT_KEY} entry whose value is present but is not a
     * JSON object, this is rejected at decode time rather than deferred to a later, unclassified
     * failure.
     *
     * @param carrier the carrier object; a {@code null} carrier or absent {@code context} section
     *                yields {@link #empty()}
     * @return the reconstructed document
     * @throws MalformedDurableMetadataException if the carrier's {@link #CONTEXT_KEY} entry is
     *                                            present but is not a JSON object, or if any
     *                                            namespace body within it is not a JSON object
     */
    public static DurableMetadata fromCarrier(JsonObject carrier) {
        if (carrier == null) {
            return empty();
        }
        if (carrier.containsKey(CONTEXT_KEY)) {
            Object value = carrier.getValue(CONTEXT_KEY);
            if (!(value instanceof JsonObject)) {
                throw new MalformedDurableMetadataException("durable context carrier key '" + CONTEXT_KEY
                        + "' must be a JSON object, but was "
                        + (value == null ? "null" : value.getClass().getSimpleName()));
            }
        }
        return fromJson(carrier.getJsonObject(CONTEXT_KEY));
    }

    /**
     * @return {@code true} if no namespaces are present
     */
    public boolean isEmpty() {
        return namespaces.isEmpty();
    }

    /**
     * @return an unmodifiable, insertion-ordered set of the namespace names present
     */
    public Set<String> namespaces() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(namespaces.fieldNames()));
    }

    /**
     * @param namespace the namespace name
     * @return {@code true} if the namespace is present
     */
    public boolean has(String namespace) {
        return namespace != null && namespaces.containsKey(namespace);
    }

    /**
     * Returns a deep copy of the given namespace's body.
     *
     * @param namespace the namespace name
     * @return a deep copy of the body, or {@link Optional#empty()} if the namespace is absent
     */
    public Optional<JsonObject> body(String namespace) {
        if (namespace == null) {
            return Optional.empty();
        }
        JsonObject body = namespaces.getJsonObject(namespace);
        return body == null ? Optional.empty() : Optional.of(body.copy());
    }

    /**
     * Returns a new document with the given namespace added or replaced.
     *
     * @param namespace the namespace name; must not be {@code null} or blank
     * @param body      the namespace body; deep-copied in, must not be {@code null}
     * @return a new document; this instance is unchanged
     * @throws IllegalArgumentException if {@code namespace} is blank
     * @throws NullPointerException     if {@code body} is {@code null}
     */
    public DurableMetadata with(String namespace, JsonObject body) {
        requireNamespace(namespace);
        Objects.requireNonNull(body, "body");
        JsonObject copy = namespaces.copy();
        copy.put(namespace, body.copy());
        return new DurableMetadata(copy);
    }

    /**
     * Returns a new document with the given namespace removed, if present.
     *
     * @param namespace the namespace name to remove; must not be {@code null} or blank
     * @return a new document without {@code namespace}; this instance is unchanged. Returns this
     *         instance unchanged (not a copy) if {@code namespace} was already absent
     * @throws IllegalArgumentException if {@code namespace} is blank
     */
    public DurableMetadata without(String namespace) {
        requireNamespace(namespace);
        if (!namespaces.containsKey(namespace)) {
            return this;
        }
        JsonObject copy = namespaces.copy();
        copy.remove(namespace);
        return new DurableMetadata(copy);
    }

    /**
     * Returns the union of this document and {@code other}, resolving shared namespaces per
     * {@code policy}. This instance is treated as the caller/authoritative side.
     *
     * @param other  the other document to overlay; must not be {@code null}
     * @param policy how to resolve a namespace present on both sides
     * @return a new merged document; this instance is unchanged
     * @throws IllegalStateException if {@code policy} is {@link MergePolicy#FAIL_ON_CONFLICT} and a
     *                               namespace is present on both sides
     */
    public DurableMetadata merge(DurableMetadata other, MergePolicy policy) {
        Objects.requireNonNull(other, "other");
        Objects.requireNonNull(policy, "policy");
        JsonObject result = namespaces.copy();
        for (String ns : other.namespaces.fieldNames()) {
            if (result.containsKey(ns)) {
                if (policy == MergePolicy.FAIL_ON_CONFLICT) {
                    throw new IllegalStateException("Conflicting durable context namespace: " + ns);
                }
                continue; // CALLER_WINS: keep the receiver's body
            }
            result.put(ns, other.namespaces.getJsonObject(ns).copy());
        }
        return new DurableMetadata(result);
    }

    /**
     * @return a deep copy of the bare namespaces object ({@code {namespace: body}})
     */
    public JsonObject toJson() {
        return namespaces.copy();
    }

    /**
     * @return a deep copy wrapped under {@link #CONTEXT_KEY} ({@code {"context": {namespace: body}}})
     */
    public JsonObject toCarrier() {
        return new JsonObject().put(CONTEXT_KEY, namespaces.copy());
    }

    private static void requireNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be null or blank");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof DurableMetadata other && namespaces.equals(other.namespaces);
    }

    @Override
    public int hashCode() {
        return namespaces.hashCode();
    }

    @Override
    public String toString() {
        return "DurableMetadata" + namespaces.encode();
    }
}
