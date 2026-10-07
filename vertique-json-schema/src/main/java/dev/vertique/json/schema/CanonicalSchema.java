// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import java.util.Objects;

/**
 * INTERNAL framework seam — callable by sibling Vertique modules only; not an application contract and outside the application maturity promise.
 *
 * A canonical JSON Schema document and the redaction manifest the generator bound to it, as {@link
 * AnnotationJsonSchemaGenerator#describe(java.lang.reflect.Type)} returns them.
 *
 * <p>For an instance {@code describe} produced, {@link #json()} is exactly the text {@link
 * AnnotationJsonSchemaGenerator#generateCanonical(java.lang.reflect.Type)} returns for the same type
 * on the same generator, and {@link #redactionManifest()} lists every reserved-name assertion in that
 * document and carries the digest of its canonical bytes; see {@link RedactionManifest}.
 *
 * <p>A caller cannot forge a manifest, but this is a plain record anyone can construct, pairing any
 * text with any manifest. A consumer therefore trusts the pairing only when {@code
 * redactionManifest().matches(json())} holds; a manifest borrowed from another document fails that
 * check for different JSON.
 *
 * @param json              the canonical, compact Draft 2020-12 JSON Schema document; never {@code
 *                          null}
 * @param redactionManifest the reserved-name assertions of {@code json} and its digest; never {@code
 *                          null}
 */
public record CanonicalSchema(String json, RedactionManifest redactionManifest) {

    /**
     * Pairs a document with a redaction manifest, without checking that the manifest matches it.
     *
     * @throws NullPointerException if {@code json} or {@code redactionManifest} is {@code null}
     */
    public CanonicalSchema {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(redactionManifest, "redactionManifest");
    }
}
