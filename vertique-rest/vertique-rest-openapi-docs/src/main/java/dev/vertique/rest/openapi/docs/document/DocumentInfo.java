// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.document;

import jakarta.annotation.Nullable;

/**
 * The resolved {@code info} object of a document: the members {@link DocumentWriter#info(DocumentInfo)}
 * writes.
 *
 * <p>Internal to the OpenAPI documentation module; not an application API.
 *
 * @param title the document title
 * @param version the document version
 * @param description the optional document description
 */
public record DocumentInfo(
        String title, String version, @Nullable String description) {}
