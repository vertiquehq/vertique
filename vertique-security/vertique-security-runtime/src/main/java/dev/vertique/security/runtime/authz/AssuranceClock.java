// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import jakarta.inject.Qualifier;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Dagger {@link Qualifier} that marks the {@link java.time.Clock} read by
 * {@link AssuranceRequirementNarrower}'s freshness-decay evaluation.
 *
 * <p>This qualifier isolates {@link AssuranceRequirementModule}'s {@code Clock} binding from any
 * other unqualified {@code Clock} binding an installing {@code @Component} might also provide.
 * Without a dedicated qualifier, two unqualified {@code @Provides static Clock} methods installed
 * in the same Dagger component would collide as a duplicate binding at compile time.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AssuranceClock {}
