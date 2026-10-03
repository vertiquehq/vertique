// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.document;

import jakarta.annotation.Nullable;

/**
 * The {@code info} members of a document: the members {@link DocumentWriter#info(DocumentInfo)}
 * writes. Title and version are guaranteed non-blank only after the configuration checks ran;
 * the writer only ever receives checked values.
 *
 * <p>Internal to the OpenAPI documentation module; not an application API.
 *
 * @param title the document title; may be absent or blank before the configuration checks
 * @param version the document version; may be absent or blank before the configuration checks
 * @param description the optional document description
 */
public record DocumentInfo(
        String title, String version, @Nullable String description) {}
