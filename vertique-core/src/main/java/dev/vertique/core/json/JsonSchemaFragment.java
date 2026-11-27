// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.EncodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.JacksonCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One self-contained JSON Schema (Draft 2020-12) fragment, held as an immutable canonical value.
 *
 * <p>A fragment describes the wire contract a {@link JsonProfileCodec} imposes on one Java class
 * (see {@link JsonSchemaTypeOverride}). It is a <em>fragment</em>, not a document: the schema
 * generator owns the document dialect and definition graph, so the dialect and reference keywords
 * {@code $schema}, {@code $id}, {@code $anchor}, {@code $dynamicAnchor}, {@code $ref},
 * {@code $dynamicRef}, and {@code $defs} are rejected. A fragment-local reference would change
 * meaning once the fragment is embedded in a generated document.
 *
 * <p>The rejection is structural and value-free: <strong>any</strong> occurrence of one of those
 * key names at any depth is rejected, including a key that is merely a property name inside a
 * {@code properties} object. That over-rejection is deliberate — it keeps the scan from having to
 * interpret the schema's own semantics.
 *
 * <p>Parsing provides syntactic and bounded structural validation only. Semantic compilation of the
 * completed schema remains the consuming validator's responsibility.
 *
 * <p>Instances are immutable and carry no reference to any caller-owned JSON tree: only the
 * canonical compact string is retained. Two fragments are equal when their canonical JSON is equal,
 * so fragments that differ only in key order or insignificant whitespace are equal.
 */
public final class JsonSchemaFragment {

    /**
     * The dialect and reference keywords a fragment must not contain at any depth.
     *
     * @see #parse(String)
     */
    private static final Set<String> REJECTED_KEYWORDS =
            Set.of("$schema", "$id", "$anchor", "$dynamicAnchor", "$ref", "$dynamicRef", "$defs");

    /**
     * The fragment-local codec: Vert.x's streaming codec, used only to read and to re-serialize fragment
     * text, never application payloads. It reads floating-point numbers as doubles, keeps the last of
     * repeated keys, accepts JSON comments and applies the operator's {@code vertx.jackson.defaultRead*}
     * limits; it needs no data-binding library.
     */
    private static final JacksonCodec CODEC = new JacksonCodec();

    private final String canonicalJson;

    private JsonSchemaFragment(String canonicalJson) {
        this.canonicalJson = canonicalJson;
    }

    /**
     * Parses and canonicalizes one self-contained schema fragment.
     *
     * <p>The input must be a non-null, non-blank JSON <em>object</em>. Object keys are ordered
     * recursively by {@link String#compareTo(String)} (UTF-16 code-unit order); array element order
     * is never changed. Only the resulting canonical compact string is retained — the parsed tree is
     * discarded, so the caller keeps sole ownership of anything it passed in.
     *
     * <p>Every failure is an {@link IllegalArgumentException} whose message identifies the violated
     * rule and is bounded; it never echoes fragment content.
     *
     * @param schemaJson the fragment JSON: a non-null, non-blank JSON object
     * @return the canonical, immutable fragment
     * @throws IllegalArgumentException if {@code schemaJson} is {@code null}, blank, not well-formed
     *     JSON, not rooted in a JSON object, or contains one of the rejected dialect keywords
     */
    public static JsonSchemaFragment parse(String schemaJson) {
        if (schemaJson == null) {
            throw new IllegalArgumentException("JSON schema fragment must not be null");
        }
        if (schemaJson.isBlank()) {
            throw new IllegalArgumentException("JSON schema fragment must not be blank");
        }
        Object root;
        try {
            root = CODEC.fromString(schemaJson);
        } catch (DecodeException e) {
            // The parser's message quotes the source, so neither it nor the cause may be propagated.
            throw new IllegalArgumentException("JSON schema fragment must be well-formed JSON");
        }
        if (!(root instanceof JsonObject)) {
            throw new IllegalArgumentException("JSON schema fragment must be a JSON object");
        }
        Object canonical = canonicalize(root);
        try {
            return new JsonSchemaFragment(CODEC.toString(canonical, false));
        } catch (EncodeException e) {
            throw new IllegalArgumentException("JSON schema fragment could not be canonicalized");
        }
    }

    /**
     * Returns the canonical compact JSON of this fragment: object keys ordered recursively by
     * {@link String#compareTo(String)}, array order preserved, no insignificant whitespace.
     *
     * @return the non-null canonical JSON; the same value on every call
     */
    public String canonicalJson() {
        return canonicalJson;
    }

    /**
     * Compares fragments by canonical JSON.
     *
     * @param obj the object to compare with
     * @return {@code true} if {@code obj} is a fragment with equal canonical JSON
     */
    @Override
    public boolean equals(Object obj) {
        return obj instanceof JsonSchemaFragment other && canonicalJson.equals(other.canonicalJson);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the canonical JSON's hash code
     */
    @Override
    public int hashCode() {
        return canonicalJson.hashCode();
    }

    /**
     * Recursively rejects the dialect keywords and copies the node into canonical key order.
     *
     * <p>Scalars are immutable and are therefore shared rather than copied; the structure this builds is
     * discarded by {@link #parse(String)} once it has been serialized.
     *
     * @param node the node to canonicalize
     * @return a canonically ordered copy of {@code node}
     * @throws IllegalArgumentException if any object key is a rejected dialect keyword
     */
    private static Object canonicalize(Object node) {
        if (node instanceof JsonObject object) {
            return canonicalize(object.getMap());
        }
        if (node instanceof JsonArray array) {
            return canonicalize(array.getList());
        }
        if (node instanceof Map<?, ?> object) {
            List<String> keys = new ArrayList<>();
            for (Object rawKey : object.keySet()) {
                String key = (String) rawKey;
                if (REJECTED_KEYWORDS.contains(key)) {
                    throw new IllegalArgumentException(
                            "JSON schema fragment must not contain the reserved keyword " + key);
                }
                keys.add(key);
            }
            keys.sort(String::compareTo);
            Map<String, Object> canonical = new LinkedHashMap<>();
            for (String key : keys) {
                canonical.put(key, canonicalize(object.get(key)));
            }
            return canonical;
        }
        if (node instanceof List<?> array) {
            List<Object> canonical = new ArrayList<>(array.size());
            for (Object element : array) {
                canonical.add(canonicalize(element));
            }
            return canonical;
        }
        return node;
    }
}
