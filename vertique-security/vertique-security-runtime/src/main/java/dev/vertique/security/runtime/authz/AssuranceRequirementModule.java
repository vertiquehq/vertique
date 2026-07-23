// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.security.authz.AuthorizationNarrower;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.time.Clock;

/**
 * Opt-in Dagger module that activates the minimum-assurance policy hooks in the authorization
 * engine (PRD identity-002 FR-ID-CA-005).
 *
 * <p>Contributes {@link AssuranceRequirementNarrower} into the {@code Set<AuthorizationNarrower>}
 * multibinding declared by {@code SecurityAuthzModule}, so it folds into the
 * {@code NarrowingAuthorizer}/{@code NarrowingIntrospector} that module wires, and provides the
 * {@link AssuranceRequirementConfig} it reads from the {@code identity.assurance} section of the
 * application config.
 *
 * <p><strong>Minimum-assurance enforcement is opt-in.</strong> Without this module installed, the
 * authorization graph is unaffected: {@code SecurityAuthzModule}'s {@code Set<AuthorizationNarrower>}
 * multibinding is empty-by-default, so no action is ever assurance-gated. Installing this module —
 * and configuring at least one entry under {@code identity.assurance.actions} — is what turns
 * minimum-assurance enforcement on; with no configured entries the installed narrower is still a
 * no-op passthrough (see {@link AssuranceRequirementNarrower}'s no-requirement branch).
 *
 * <p><strong>Install contract — Clock binding.</strong> This module provides the {@link Clock}
 * consumed by {@link AssuranceRequirementNarrower}'s freshness-decay evaluation under the
 * {@link AssuranceClock} qualifier ({@link Clock#systemUTC()}), not as an unqualified binding. This
 * lets the module coexist with any other module the installing {@code @Component} also includes that
 * provides its own unqualified or differently-qualified {@code Clock} binding without a Dagger
 * duplicate-binding error.
 *
 * <p>Config path: {@code identity.assurance} — for example:
 *
 * <pre>{@code
 * identity:
 *   assurance:
 *     actions:
 *       "cms.content.delete":
 *         minProviderLevel: 2
 *         maxAgeMs: 300000
 * }</pre>
 *
 * <p>Include this module alongside {@code SecurityAuthzModule} in any Dagger {@code @Component} that
 * should enforce minimum-assurance step-up gating.
 */
@Module
public abstract class AssuranceRequirementModule {

    private AssuranceRequirementModule() {
        /* Dagger abstract module — no instances */
    }

    /**
     * Provides the parsed {@link AssuranceRequirementConfig} from the {@code identity.assurance}
     * section of the application config.
     *
     * @param config the full application config injected via {@code @VertxConfig}
     * @param parser the injected config parser
     * @return the parsed, validated assurance-requirement configuration; never {@code null}
     */
    @Provides
    @Singleton
    static AssuranceRequirementConfig assuranceRequirementConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(
                JsonConfigPaths.navigateObject(config, "identity", "assurance"), AssuranceRequirementConfig.class);
    }

    /**
     * Provides the {@link AssuranceClock}-qualified {@link Clock} consumed by
     * {@link AssuranceRequirementNarrower}'s freshness-decay evaluation. See the class javadoc's
     * "Install contract — Clock binding" note for why this binding is qualified.
     *
     * @return a UTC wall-clock; never {@code null}
     */
    @Provides
    @AssuranceClock
    @Singleton
    static Clock assuranceRequirementClock() {
        return Clock.systemUTC();
    }

    /**
     * Contributes {@link AssuranceRequirementNarrower} into the {@code Set<AuthorizationNarrower>}
     * multibinding declared by {@code SecurityAuthzModule}.
     *
     * @param impl the singleton narrower, built from the config-backed
     *             {@link AssuranceRequirementConfig} and the provided {@link Clock}
     * @return the multibinding contribution
     */
    @Provides
    @IntoSet
    static AuthorizationNarrower assuranceRequirementNarrower(AssuranceRequirementNarrower impl) {
        return impl;
    }
}
