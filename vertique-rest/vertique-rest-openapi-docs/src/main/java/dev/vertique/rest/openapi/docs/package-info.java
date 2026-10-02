// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Opt-in runtime OpenAPI 3.1 documents served per documented REST application. A declaring
 * interface annotated with {@link dev.vertique.rest.openapi.docs.ApiDocs} enables the document
 * named by its application; {@link dev.vertique.rest.openapi.docs.OpenApiDocsModule} is the Dagger
 * module that installs the documentation feature.
 *
 * <p>This package holds the module's application-facing surface, {@code ApiDocs} and {@code
 * OpenApiDocsModule}. The implementation lives in subpackages that are internal to the module and
 * are not an application API:
 *
 * <ul>
 *   <li>{@code config}: the {@code apidocs} section, its checks, and the enabled documents;
 *   <li>{@code metadata}: the annotations and descriptor facts of an operation;
 *   <li>{@code diagnostics}: the warning logger and the warnings an assembly holds;
 *   <li>{@code schema}: generating, embedding, refusing, relocating, and naming schemas;
 *   <li>{@code document}: the serialized document, its entity tags, and its snapshot;
 *   <li>{@code assembly}: building one application's document from its operations;
 *   <li>{@code contract}: serving an application's own OpenAPI contract;
 *   <li>{@code publication}: receiving publications and storing one document per application;
 *   <li>{@code serving}: the documentation mount, its routes, responses, caching, and composition
 *       checks.
 * </ul>
 */
package dev.vertique.rest.openapi.docs;
