// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.extension;

/**
 * Coarse ordering phase for framework extensions (interceptors, observers, hooks, contributors).
 *
 * <p>Phases sort in declaration order, so {@code SYSTEM_FIRST} always runs before
 * {@code APPLICATION}, which always runs before {@code SYSTEM_LAST} — independent of an
 * extension's {@link OrderedExtension#priority()}.
 *
 * <p><strong>Not a security boundary.</strong> The {@code SYSTEM_*} phases are a <em>trusted
 * platform-ordering</em> hint, not an access-control or isolation mechanism. Any module on the
 * classpath may declare a {@code SYSTEM_FIRST}/{@code SYSTEM_LAST} extension; the phase only affects
 * ordering, and confers no privilege. Do not rely on it to keep application code out of a position —
 * enforce trust elsewhere if that is required.
 *
 * <p>Ordering applies <em>within a single homogeneous extension set</em> (e.g. all interceptors of one
 * kind, sorted by {@link OrderedExtension#comparator()}). It does not interleave across <em>different</em>
 * extension sets, nor does it by itself govern when one kind of extension runs relative to another — that
 * is determined by where the framework invokes each set.
 */
public enum ExtensionPhase {
    /**
     * System/platform-owned extensions (Vertique modules or trusted application-platform modules) that
     * must run before any application extension — e.g. context capture.
     */
    SYSTEM_FIRST,
    /** Application-provided extensions (the default). */
    APPLICATION,
    /**
     * System/platform-owned extensions that must run after all application extensions — e.g. final
     * observation.
     */
    SYSTEM_LAST
}
