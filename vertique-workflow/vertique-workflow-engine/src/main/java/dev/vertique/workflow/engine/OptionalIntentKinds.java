// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import jakarta.inject.Qualifier;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Dagger {@link Qualifier} that disambiguates the {@code Set<IntentKind>} of optional intent kinds
 * consumed by {@link RecorderRouter} from any other similarly-typed set in the application graph.
 *
 * <p>Without this qualifier, a generic {@code Set<IntentKind>} provider could collide with any
 * other {@code Set<IntentKind>} a future module might expose, and tests overriding the optional
 * set would have to fight Dagger's matching. The qualifier scopes the set strictly to "kinds the
 * router treats as optional."
 *
 * <p>Applied on the {@code @Provides} method in {@link WorkflowEngineModule} and on the
 * matching constructor parameter in {@link RecorderRouter}.
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface OptionalIntentKinds {}
