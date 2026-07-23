// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.lifecycle;

/**
 * Marker for a framework <em>compose validator</em> — a type whose {@code @Inject} constructor
 * performs Dagger-graph composition validation as a side effect of being constructed
 * (the <em>constructible-as-validation</em> pattern).
 *
 * <p>A compose validator declares, as required constructor parameters, the bindings a module needs
 * in order to function. The act of successfully constructing it proves those bindings are present;
 * a missing binding fails Dagger code-generation (compile time) or component construction (startup)
 * rather than surfacing as an obscure runtime error later. Some validators additionally throw an
 * {@link IllegalStateException} from their constructor for invariants Dagger cannot express
 * statically (e.g. "at least one {@code SERVICE} handler is registered").
 *
 * <p>The framework materializes the {@code Set<ComposeValidator>} multibinding during the
 * {@link LifecyclePhase#VALIDATE} phase (see {@link ComposeValidationStep}). Materializing the set
 * forces Dagger to construct every contributed validator, which runs all of their constructor-time
 * checks and produces the fail-fast behavior. Application code therefore never calls a compose
 * validator directly — contributing it {@code @IntoSet ComposeValidator} is sufficient.
 *
 * <p>This interface is intentionally empty: it carries no methods. Its only purpose is to let
 * modules join the {@code Set<ComposeValidator>} multibinding so the framework can drive their
 * construction generically.
 */
public interface ComposeValidator {}
