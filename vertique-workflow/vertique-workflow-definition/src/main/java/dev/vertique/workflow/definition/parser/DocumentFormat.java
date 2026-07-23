// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.parser;

/**
 * Identifies the serialization format of a workflow definition document source.
 *
 * <p>The format hint is always supplied by the source (e.g., a file extension, a content-type
 * header, or an explicit configuration value) — it is never sniffed from the raw bytes. This
 * keeps source-type ambiguity out of the parser and makes the format contract explicit at the
 * call site.
 */
public enum DocumentFormat {

    /** YAML format, parsed using {@code jackson-dataformat-yaml}. */
    YAML,

    /** JSON format, parsed using the standard Jackson JSON factory. */
    JSON
}
