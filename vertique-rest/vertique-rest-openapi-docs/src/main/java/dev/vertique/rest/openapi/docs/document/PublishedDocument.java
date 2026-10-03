// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.document;

/**
 * A frozen document in both forms, with its entity tags and the fingerprint it was assembled from.
 * The byte arrays are never modified after construction.
 *
 * <p>Internal to the OpenAPI documentation module; not an application API.
 *
 * @param json the JSON form
 * @param yaml the YAML form
 * @param jsonTag the entity tag of the JSON form
 * @param yamlTag the entity tag of the YAML form
 * @param fingerprint the fingerprint of the mount the document was assembled from
 */
public record PublishedDocument(
        byte[] json, byte[] yaml, String jsonTag, String yamlTag, PublicationFingerprint fingerprint) {}
