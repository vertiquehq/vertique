// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.health;

import jakarta.inject.Qualifier;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Dagger qualifier that classifies a {@link HealthCheck} as a liveness indicator.
 *
 * <p>Liveness checks should be lightweight and fast — they determine whether
 * the process is alive and should not be restarted by the orchestrator.
 * Avoid checking external dependencies in liveness checks.
 *
 * <p>Usage:
 * <pre>{@code
 * @Provides @IntoSet @Liveness
 * HealthCheck processCheck(ProcessHealthCheck check) {
 *     return check;
 * }
 * }</pre>
 *
 * @see Readiness
 * @see HealthCheck
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
public @interface Liveness {}
