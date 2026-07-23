// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Producer-neutral payload descriptor types for the Vert.x framework.
 *
 * <p>Provides a lightweight, transport-agnostic representation of an HTTP request or response
 * body: a {@link dev.vertique.core.payload.PayloadKind} discriminant, a
 * {@link dev.vertique.core.payload.PayloadSource} interface describing the body's kind, optional
 * content-type, declared length, and access to its bytes (buffered or streaming), and a
 * {@link dev.vertique.core.payload.PayloadSources} static factory for creating instances.
 *
 * <p>The package is intentionally free of any audit, REST, or service-layer dependencies.
 * Modules that need to describe a payload — such as the audit ingress or rest-client — depend
 * only on this package.
 *
 * <p>No eager copy is performed by the factory: {@code buffered(byte[], ...)} captures the
 * array reference directly; callers that require immutability must copy before calling the
 * factory.
 *
 * @see dev.vertique.core.payload.PayloadSource
 * @see dev.vertique.core.payload.PayloadSources
 */
package dev.vertique.core.payload;
