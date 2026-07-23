// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Public API and SPI for correlation context propagation.
 *
 * <p>This package owns the <em>public API + SPI only</em>: immutable value types, read-only
 * interfaces, enums, and validators. No mutable state, no framework-internal wiring lives here.
 * Runtime implementations and mutable context-holder types live in the separate
 * {@code vertique-correlation} module.
 *
 * <p>All boundary types in this package are immutable. Records use defensive copies in their
 * canonical constructors to ensure that instances cannot be mutated after construction.
 *
 * <h2>Key types</h2>
 * <ul>
 *   <li>{@link dev.vertique.core.correlation.CorrelationIdentifier} — a correlation or request ID
 *       with its source label.
 *   <li>{@link dev.vertique.core.correlation.TraceReference} — a distributed trace ID + optional
 *       span ID.
 *   <li>{@link dev.vertique.core.correlation.ProtocolCorrelationRef} — a concrete header-level
 *       correlation extracted from an inbound protocol.
 *   <li>{@link dev.vertique.core.correlation.CorrelationContextSnapshot} — immutable snapshot of
 *       the full correlation state at a point in time.
 *   <li>{@link dev.vertique.core.correlation.CorrelationContext} — read-only interface for the
 *       live context, injectable via the dispatch framework.
 *   <li>{@link dev.vertique.core.correlation.CorrelationIdGenerator} — SPI for plugging in custom
 *       ID generation strategies.
 * </ul>
 */
package dev.vertique.core.correlation;
