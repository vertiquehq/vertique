// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

/**
 * Marker interface for values that may be bound into {@link ContextHolder} and injected by
 * context-aware consumers.
 *
 * <p>This interface carries no methods. It serves two distinct purposes:
 *
 * <ol>
 *   <li><b>Compile-time safety</b> — the typed {@link ContextHolder} write path accepts only
 *       {@code ContextValue} types, preventing arbitrary objects from being stored under a
 *       context key without an explicit opt-in.
 *   <li><b>Runtime validation</b> — erased reinstatement paths (snapshot restore, durable decode)
 *       perform a {@code instanceof ContextValue} check and reject non-{@code ContextValue} objects
 *       at runtime to catch misconfiguration early.
 * </ol>
 *
 * <p>Every {@link dev.vertique.core.eventbus.DispatchContextValue @DispatchContextValue}-annotated
 * type is also a {@code ContextValue} — the annotation marks the narrower subset of context values
 * that a service handler may declare as method parameters and have resolved automatically by the
 * dispatch framework.
 *
 * <p>The read path ({@link ContextHolder#current(Class)}) does <em>not</em> require the requested
 * type to implement {@code ContextValue}; any type stored under a context key can be retrieved
 * without the marker.
 */
public interface ContextValue {}
