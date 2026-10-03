// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * The JSON Pointers of every reserved-name assertion in one generated schema, bound to the digest of
 * that schema's canonical bytes. Only this package constructs instances.
 *
 * <p>An input-direction generator reserves a name it binds but does not publish — a {@code
 * @JsonIgnore} or {@code @Schema(hidden = true)} member, for example — with an assertion under {@code
 * propertyNames} that spells the name out: an {@code enum} of the reserved names for a
 * case-sensitively bound type, or a pattern of their ASCII case folds for a case-insensitively bound
 * one. {@link #pointers()} locates every such assertion in the finished document, including every copy
 * alias expansion or case-fold publication made of it, so a publisher can remove exactly those
 * locations and publish the document without any reserved name, in any spelling or fold. A removed
 * assertion leaves the rest of the document intact: in particular, a case-insensitively bound type's
 * refusal of non-ASCII keys is a separate assertion and is never listed. A {@code propertyNames} the
 * generator did not emit as a reserved-name assertion — one a profile override fragment declares — is
 * never listed.
 *
 * <p>{@link #digest()} binds the manifest to one document: {@code "sha-256:"} followed by the
 * lowercase hexadecimal SHA-256 of the UTF-8 bytes of the document's canonical form — its keys
 * ordered, compact, rewritten exactly as the generator's own canonical output is — so {@link
 * #matches(String)} holds for the document however it was later re-rendered, and fails for any
 * difference that survives parsing and canonicalization. The manifest of an output-direction
 * generator, and of a generator constructed with {@link
 * AnnotationJsonSchemaGenerator#withVictoolsDefaults()}, is always empty, because neither emits a
 * reserved-name assertion.
 *
 * <p>Instances are immutable and safe to share between threads. Two manifests are equal when their
 * pointers and digests are. {@link #toString()} prints the pointers and the digest only: a pointer
 * names a location in the schema, never a reserved name, and no schema content is printed.
 */
public final class RedactionManifest {

    /** The label the digest's hexadecimal hash follows. */
    private static final String DIGEST_ALGORITHM_LABEL = "sha-256:";

    /** The RFC 6901 pointers, sorted by {@link String#compareTo(String)}; unmodifiable. */
    private final List<String> pointers;

    /** The digest of the schema's canonical bytes. */
    private final String digest;

    /**
     * Binds the listed reserved-name assertions to the digest of the document they were listed in.
     *
     * @param pointers the RFC 6901 pointers, sorted by {@link String#compareTo(String)}
     * @param digest   the digest of the document's canonical bytes, as {@link #digestOf(String)}
     *                 computes it
     * @throws NullPointerException if {@code pointers}, one of its elements, or {@code digest} is
     *                              {@code null}
     */
    RedactionManifest(List<String> pointers, String digest) {
        this.pointers = List.copyOf(pointers);
        this.digest = Objects.requireNonNull(digest, "digest");
    }

    /**
     * Returns the RFC 6901 JSON Pointer of every reserved-name assertion in the schema, each naming
     * the assertion's own schema object, sorted by {@link String#compareTo(String)}.
     *
     * @return the pointers; unmodifiable, and empty when the schema reserves no name
     */
    public List<String> pointers() {
        return pointers;
    }

    /**
     * Returns the digest of the schema's canonical bytes: {@code "sha-256:"} followed by 64 lowercase
     * hexadecimal characters.
     *
     * @return the digest
     */
    public String digest() {
        return digest;
    }

    /**
     * Returns whether {@code schemaJson} is the schema this manifest was bound to: it is parsed,
     * brought to canonical form, and its digest compared with {@link #digest()}.
     *
     * <p>Key order, insignificant whitespace, trailing whitespace, and any other difference parsing
     * erases — an escaped spelling of a character, or another notation of a number that parses to the
     * same value — do not affect the result; any difference that survives parsing and canonicalization
     * does. Text that is not exactly one well-formed JSON value — empty text, a truncated document,
     * content after the value, an object repeating a key, or nesting deeper than the reader accepts —
     * never matches, and never throws.
     *
     * @param schemaJson the JSON text to check
     * @return {@code true} when {@code schemaJson}'s canonical bytes have this manifest's digest
     * @throws NullPointerException if {@code schemaJson} is {@code null}
     */
    public boolean matches(String schemaJson) {
        Objects.requireNonNull(schemaJson, "schemaJson");
        try {
            // A text holding no value has no digest, and a null candidate equals no digest.
            return digest.equals(digestOf(schemaJson));
        } catch (JsonProcessingException unparseable) {
            return false;
        }
    }

    /**
     * Computes the digest of a document's canonical bytes: {@code "sha-256:"} followed by the
     * lowercase hexadecimal SHA-256 of the UTF-8 bytes of {@link SchemaCanonicalizer}'s canonical form
     * of the parsed document.
     *
     * @param schemaJson the document's JSON text; one JSON value, optionally followed by whitespace
     * @return the digest, or {@code null} when the text holds no JSON value at all
     * @throws JsonProcessingException if the text is not well-formed JSON, carries content after its
     *                                 first value, repeats a key within one object, or its canonical
     *                                 form cannot be written
     */
    static String digestOf(String schemaJson) throws JsonProcessingException {
        JsonNode document = NeutralJson.readStrict(schemaJson);
        if (document.isMissingNode()) {
            return null;
        }
        String canonical = SchemaCanonicalizer.canonicalize(document);
        return DIGEST_ALGORITHM_LABEL + HexFormat.of().formatHex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The SHA-256 hash of {@code bytes}.
     *
     * @param bytes the bytes to hash
     * @return the 32-byte hash
     */
    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException unavailable) {
            // Every Java platform implementation is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RedactionManifest that && pointers.equals(that.pointers) && digest.equals(that.digest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pointers, digest);
    }

    @Override
    public String toString() {
        return "RedactionManifest[pointers=" + pointers + ", digest=" + digest + "]";
    }
}
