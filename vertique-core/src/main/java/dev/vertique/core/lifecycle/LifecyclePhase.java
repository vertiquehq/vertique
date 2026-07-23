// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.lifecycle;

/**
 * The single public application-lifecycle phase vocabulary.
 *
 * <p>{@code LifecyclePhase} spans the entire application lifecycle in declaration (and therefore
 * ordinal) order: three non-verticle pre-deploy phases, the four verticle-deployment phases, then a
 * non-verticle post-start phase. Lifecycle participants are ordered by this phase first — see {@link
 * LifecycleOrdered#comparator()}.
 *
 * <p>This enum is the single lifecycle ordering vocabulary for the framework. The {@link #BOOTSTRAP}
 * / {@link #INFRA} / {@link #SERVICES} / {@link #EDGE} subset preserves the ordinal order and
 * semantics of the legacy verticle-deployment phases it replaced.
 *
 * <p><strong>Verticle-subset invariant.</strong> Only the four verticle-deployment phases —
 * {@link #BOOTSTRAP}, {@link #INFRA}, {@link #SERVICES}, {@link #EDGE} — may carry a verticle
 * deployment; {@link #isVerticlePhase()} reports exactly this subset. The non-verticle phases
 * ({@link #CONFIGURE}, {@link #VALIDATE}, {@link #MIGRATE}, {@link #AFTER_START}) host
 * {@link ApplicationStartupStep}/{@link ApplicationShutdownStep} work that runs outside verticle
 * deployment.
 */
public enum LifecyclePhase {

    // --- non-verticle: pre-deploy preparation ---

    /** Build host/runtime configuration (e.g. install Jackson modules) before any deployment. */
    CONFIGURE,
    /** Validate the assembled configuration and wiring before any deployment. */
    VALIDATE,
    /** Run data/schema migrations before any deployment. */
    MIGRATE,

    // --- verticle-deployment subset (order + semantics preserved) ---

    /** Framework bootstrapping (codec registration, config watchers). */
    BOOTSTRAP,
    /** Infrastructure verticles (management, health checks). */
    INFRA,
    /** Service-layer verticles (event bus dispatch). */
    SERVICES,
    /** Edge verticles (HTTP, WebSocket). */
    EDGE,

    // --- non-verticle: post-start ---

    /** Post-start work that runs after all verticles are deployed. */
    AFTER_START;

    /**
     * Reports whether this phase may carry a verticle deployment.
     *
     * @return {@code true} only for {@link #BOOTSTRAP}, {@link #INFRA}, {@link #SERVICES} and
     *     {@link #EDGE}; {@code false} for the non-verticle phases
     */
    public boolean isVerticlePhase() {
        return this == BOOTSTRAP || this == INFRA || this == SERVICES || this == EDGE;
    }
}
