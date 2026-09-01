// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.dagger;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.Multibinds;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitPolicy;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitModeKey;
import dev.vertique.ratelimit.spi.RateLimitObserver;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dagger configuration contribution for the application-scoped rate-limit runtime.
 *
 * <p>Policies resolve from typed {@code rateLimit.policies.<name>} configuration and/or Dagger
 * {@code @IntoSet RateLimitPolicy} contributions, merged via
 * {@link RateLimitPolicy#mergeConfigOverProgrammatic} (config wins wholesale on a same-name
 * overlap). A per-policy omitted {@code mode} inherits {@code rateLimit.defaultMode} (default
 * {@code LOCAL}) before config binding runs, so {@link RateLimitPolicy}'s own "required, no
 * default" rule for {@code mode} only ever fires for a truly unresolved value.
 *
 * <p>The subject-resolver binding and the {@code @IntoSet ApplicationShutdownStep} that forces
 * eager construction at bootstrap are a later task's artifacts (contracts/rate-limit-runtime.md,
 * "Dagger wiring"; `plan.md` pre-flight finding 5).
 */
@Module
public abstract class RateLimitCoreModule {

    private static final String DEFAULT_MODE = "defaultMode";
    private static final String MODE = "mode";

    /** Prevents direct construction of the static binding module. */
    private RateLimitCoreModule() {}

    /** Declares the backend provider map every {@code @RateLimitModeKey}-annotated binding joins. */
    @Multibinds
    abstract Map<RateLimitMode, RateLimitBackend> rateLimitBackends();

    /** Declares the optional application/Dagger-contributed (programmatic) policy set. */
    @Multibinds
    abstract Set<RateLimitPolicy> rateLimitPolicies();

    /** Declares the optional bound observer set; zero, one, or many observers compose freely. */
    @Multibinds
    abstract Set<RateLimitObserver> rateLimitObservers();

    /**
     * Provides the one runtime owned by the application graph.
     *
     * @param contributedPolicies programmatic policies from every Dagger {@code @IntoSet} contribution
     * @param backends the bound backend provider map
     * @param config the raw {@code rateLimit.*} configuration section
     * @param parser the framework's config-parsing seam
     * @param vertx application Vert.x instance
     * @param observers every bound {@link RateLimitObserver} contribution
     * @return application-scoped rate-limit runtime
     */
    @Provides
    @Singleton
    static RateLimiters rateLimiters(
            Set<RateLimitPolicy> contributedPolicies,
            Map<RateLimitMode, RateLimitBackend> backends,
            @VertxConfig JsonObject config,
            ConfigParser parser,
            Vertx vertx,
            Set<RateLimitObserver> observers) {
        JsonObject rateLimit = JsonConfigPaths.navigateObject(config, "rateLimit");
        RateLimitMode defaultMode =
                RateLimitMode.valueOf(rateLimit.getString(DEFAULT_MODE, RateLimitMode.LOCAL.name()));
        String keyDerivationSecret =
                JsonConfigPaths.navigateObject(rateLimit, "keyDerivation").getString("secret");
        JsonObject policiesJson = withDefaultedMode(JsonConfigPaths.navigateObject(rateLimit, "policies"), defaultMode);
        List<RateLimitPolicy> configPolicies = parser.parseKeyedObject(policiesJson, "name", RateLimitPolicy.class);
        Set<RateLimitPolicy> policies =
                RateLimitPolicy.mergeConfigOverProgrammatic(Set.copyOf(configPolicies), contributedPolicies);
        return new RateLimiters(policies, backends, keyDerivationSecret, vertx, observers);
    }

    /**
     * Fills a per-policy {@code mode} from {@code defaultMode} when the raw config entry omits it,
     * leaving an explicit per-policy {@code mode} untouched. {@code rateLimit.policies.<name>.mode}
     * is optional in raw configuration only via this fallback (contracts/rate-limit-runtime.md,
     * "Configuration" — {@code rateLimit.defaultMode}: "Baseline mode when a policy omits mode").
     *
     * @param policiesJson the raw {@code rateLimit.policies} section
     * @param defaultMode the resolved {@code rateLimit.defaultMode}
     * @return an equivalent section with every entry's {@code mode} populated
     */
    private static JsonObject withDefaultedMode(JsonObject policiesJson, RateLimitMode defaultMode) {
        JsonObject defaulted = new JsonObject();
        policiesJson.forEach(entry -> {
            Object value = entry.getValue();
            if (value instanceof JsonObject policyJson && !policyJson.containsKey(MODE)) {
                defaulted.put(entry.getKey(), policyJson.copy().put(MODE, defaultMode.name()));
            } else {
                defaulted.put(entry.getKey(), value);
            }
        });
        return defaulted;
    }

    /**
     * Contributes the LOCAL Bucket4j backend.
     *
     * @return the LOCAL {@link RateLimitBackend}
     */
    @Provides
    @IntoMap
    @RateLimitModeKey(RateLimitMode.LOCAL)
    static RateLimitBackend localRateLimitBackend() {
        return new LocalBucket4jRateLimitBackend();
    }
}
