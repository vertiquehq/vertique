// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Source-agnostic SPI for contributing workflow definition resources.
 *
 * <p>The key types are:
 * <ul>
 *   <li>{@link dev.vertique.workflow.definition.source.WorkflowDefinitionSource} — functional SPI
 *       that supplies zero or more {@link dev.vertique.workflow.definition.source.DefinitionResource}
 *       instances.</li>
 *   <li>{@link dev.vertique.workflow.definition.source.DefinitionResource} — raw bytes plus a
 *       {@link dev.vertique.workflow.definition.parser.DocumentFormat} hint and
 *       {@link dev.vertique.workflow.definition.source.SourceMetadata}.</li>
 *   <li>{@link dev.vertique.workflow.definition.source.SourceMetadata} — provenance information
 *       stored alongside compiled definitions for audit/management surfaces.</li>
 * </ul>
 */
package dev.vertique.workflow.definition.source;
