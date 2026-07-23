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
 * Dagger qualifier that classifies a {@link HealthCheck} as a readiness indicator.
 *
 * <p>Readiness checks verify that the application can serve traffic — for example,
 * that the database pool is connected, Kafka consumers are assigned, or downstream
 * services are reachable.
 *
 * <p>Usage:
 * <pre>{@code
 * @Provides @IntoSet @Readiness
 * HealthCheck databaseCheck(DatabaseHealthCheck check) {
 *     return check;
 * }
 * }</pre>
 *
 * @see Liveness
 * @see HealthCheck
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
public @interface Readiness {}
