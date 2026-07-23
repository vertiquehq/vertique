// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * YAML and JSON parsers for workflow definition documents.
 *
 * <p>The parser layer converts raw bytes in YAML or JSON format into a
 * {@link dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument} record tree.
 * It performs no semantic validation — that is the responsibility of the validator in the
 * {@code dev.vertique.workflow.definition.validator} package.
 *
 * <p>Key types:
 * <ul>
 *   <li>{@link dev.vertique.workflow.definition.parser.DocumentFormat} — enum identifying the
 *       source format (YAML or JSON). The format hint is supplied by the source, not sniffed
 *       from content.
 *   <li>{@link dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory} — thread-safe
 *       factory that produces one dedicated {@code ObjectMapper} for YAML and one for JSON.
 *   <li>{@link dev.vertique.workflow.definition.parser.WorkflowDefinitionParser} — single-method
 *       parser that delegates to the appropriate mapper and wraps IO failures in a typed
 *       {@link dev.vertique.workflow.definition.parser.WorkflowDefinitionParseException}.
 *   <li>{@link dev.vertique.workflow.definition.parser.WorkflowDefinitionParseException} — unchecked
 *       exception wrapping Jackson deserialization failures.
 * </ul>
 */
package dev.vertique.workflow.definition.parser;
