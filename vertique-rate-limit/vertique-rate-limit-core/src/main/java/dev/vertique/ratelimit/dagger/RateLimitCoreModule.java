// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.dagger;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.ratelimit.LocalRateLimitBackendFactory;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitPolicy;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.spi.DefaultRateLimitSubjectResolver;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitModeKey;
import dev.vertique.ratelimit.spi.RateLimitObserver;
import dev.vertique.ratelimit.spi.RateLimitSubjectResolver;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * Dagger configuration contribution for the application-scoped rate-limit runtime.
 *
 * <p>Policies resolve from typed {@code rateLimit.policies.<name>} configuration and/or Dagger
 * {@code @IntoSet RateLimitPolicy} contributions, merged wholesale (config wins on a same-name
 * overlap — fields never merge across tiers; see {@link #mergeConfigOverProgrammatic}, this
 * module's own resolution step). A per-policy omitted {@code mode} inherits {@code
 * rateLimit.defaultMode} (default {@code LOCAL}) before config binding runs, so {@link
 * RateLimitPolicy}'s own "required, no default" rule for {@code mode} only ever fires for a truly
 * unresolved value.
 *
 * <p>The {@code @IntoSet ApplicationShutdownStep} contributed below forces eager construction of
 * {@link RateLimiters} at bootstrap, so its startup validation runs unconditionally before any
 * handle is requested (contracts/rate-limit-runtime.md, "Dagger wiring"; `plan.md` pre-flight
 * finding 5) — the same pattern {@code ResilienceModule} uses.
 *
 * <p>Includes {@code ContextRuntimeModule} (mirroring {@code CacheCoreModule}) so the default
 * {@link RateLimitSubjectResolver} — {@link DefaultRateLimitSubjectResolver} — has a bound {@code
 * ContextHolder} to read {@code SecurityContext} from.
 */
@Module(includes = ContextRuntimeModule.class)
public abstract class RateLimitCoreModule {

    private static final String DEFAULT_MODE = "defaultMode";
    private static final String MODE = "mode";
    private static final String ENABLED = "enabled";
    private static final String LOCAL = "local";
    private static final String MAX_TRACKED_KEYS = "maxTrackedKeys";
    private static final String CLEANUP_INTERVAL_MS = "cleanupIntervalMs";
    private static final String POLICIES = "policies";

    /**
     * Default applied when {@code rateLimit.local.maxTrackedKeys} is omitted — the documented
     * default (contracts/rate-limit-runtime.md, "Configuration" example config). An omitted value
     * degrades to this default, never to a silently-accepted {@code 0}; the resolved value (default
     * or explicit) is still eagerly bounds-validated (below 1 fails startup) by {@link
     * #localRateLimitBackend}, at this provider's own construction, the same eager-construction
     * discipline {@link RateLimiters}'s own startup validation uses.
     */
    private static final long DEFAULT_MAX_TRACKED_KEYS = 100_000L;

    /** Same default/validation contract as {@link #DEFAULT_MAX_TRACKED_KEYS}, for {@code cleanupIntervalMs}. */
    private static final long DEFAULT_CLEANUP_INTERVAL_MS = 60_000L;

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
     * Declares the optional custom {@link RateLimitSubjectResolver} binding. Present only when an
     * application module explicitly binds one; the framework default ({@link
     * DefaultRateLimitSubjectResolver}) applies otherwise (contracts/rate-limit-runtime.md,
     * "Subject resolution SPI").
     */
    @BindsOptionalOf
    abstract RateLimitSubjectResolver rateLimitSubjectResolver();

    /**
     * Provides the one runtime owned by the application graph.
     *
     * @param contributedPolicies programmatic policies from every Dagger {@code @IntoSet} contribution
     * @param backends the bound backend provider map
     * @param config the raw {@code rateLimit.*} configuration section
     * @param parser the framework's config-parsing seam
     * @param vertx application Vert.x instance
     * @param observers every bound {@link RateLimitObserver} contribution
     * @param defaultSubjectResolver the framework-default {@link RateLimitSubjectResolver}
     * @param customSubjectResolver the optional application-bound {@link RateLimitSubjectResolver},
     *     which wins over the default when present
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
            Set<RateLimitObserver> observers,
            DefaultRateLimitSubjectResolver defaultSubjectResolver,
            Optional<RateLimitSubjectResolver> customSubjectResolver) {
        JsonObject rateLimit = JsonConfigPaths.navigateObject(config, "rateLimit");
        RateLimitRootConfig rootConfig = parser.parse(rateLimit, RateLimitRootConfig.class);
        RateLimitMode defaultMode = rootConfig.defaultMode();
        String keyDerivationSecret =
                JsonConfigPaths.navigateObject(rateLimit, "keyDerivation").getString("secret");
        JsonObject policiesJson = withDefaultedMode(JsonConfigPaths.navigateObject(rateLimit, POLICIES), defaultMode);
        List<RateLimitPolicy> configPolicies = parser.parseKeyedObject(policiesJson, "name", RateLimitPolicy.class);
        Set<RateLimitPolicy> policies = mergeConfigOverProgrammatic(Set.copyOf(configPolicies), contributedPolicies);
        RateLimitSubjectResolver subjectResolver = customSubjectResolver.orElse(defaultSubjectResolver);
        return new RateLimiters(
                policies, backends, keyDerivationSecret, vertx, observers, subjectResolver, rootConfig.enabled());
    }

    /**
     * Contributes the runtime shutdown step to the host lifecycle, forcing {@link RateLimiters} to
     * construct eagerly at bootstrap so its startup validation (§4.3) runs unconditionally — the
     * same {@code @IntoSet ApplicationShutdownStep} pattern {@code ResilienceModule} uses.
     *
     * @param rateLimiters application-scoped rate-limit runtime
     * @return lifecycle-owned shutdown step
     */
    @Provides
    @IntoSet
    static ApplicationShutdownStep rateLimitersShutdownStep(RateLimiters rateLimiters) {
        return new RateLimitersShutdownStep(rateLimiters);
    }

    private static final class RateLimitersShutdownStep implements ApplicationShutdownStep {

        private final RateLimiters rateLimiters;

        private RateLimitersShutdownStep(RateLimiters rateLimiters) {
            this.rateLimiters = rateLimiters;
        }

        @Override
        public LifecyclePhase phase() {
            return LifecyclePhase.CONFIGURE;
        }

        @Override
        public int priority() {
            return Integer.MAX_VALUE;
        }

        @Override
        public String orderKey() {
            return RateLimitersShutdownStep.class.getName();
        }

        @Override
        public Future<Void> stop() {
            return rateLimiters.close();
        }
    }

    /**
     * Typed {@code rateLimit.enabled}/{@code rateLimit.defaultMode} configuration, parsed through
     * the canonical injected {@link ConfigParser} (docs/standards/config.md rule R10) instead of
     * the raw scalar {@code JsonObject} reads this module used before — so a string-encoded value
     * (e.g. {@code "enabled": "true"}) coerces reliably through the parser's dedicated,
     * coercion-lenient mapper, independent of any global/REST mapper strictness.
     *
     * @param enabled the resolved {@code rateLimit.enabled} root kill switch; defaults to {@link
     *     RateLimiters#DEFAULT_RATE_LIMIT_ENABLED} when omitted
     * @param defaultMode the resolved {@code rateLimit.defaultMode}; defaults to {@link
     *     RateLimitMode#LOCAL} when omitted
     */
    record RateLimitRootConfig(boolean enabled, RateLimitMode defaultMode) {

        /**
         * Jackson-friendly factory. {@code defaultMode} is bound from its raw {@link String} here,
         * not directly as {@link RateLimitMode}, so an unrecognized value wraps into the same
         * value-bearing {@link ConfigurationException} naming {@code rateLimit.defaultMode} this
         * module always surfaced — a validation failure raised from inside a {@code @JsonCreator}
         * propagates through {@code ConfigParser} verbatim, unlike a raw Jackson enum-binding
         * failure, which would surface only a generic, field-name-free message.
         *
         * @param enabled the raw {@code rateLimit.enabled}, or {@code null} when omitted
         * @param defaultMode the raw {@code rateLimit.defaultMode}, or {@code null} when omitted
         * @return the resolved, defaulted root config
         * @throws ConfigurationException if {@code defaultMode} is present but not one of {@link
         *     RateLimitMode}'s constants
         */
        @JsonCreator
        static RateLimitRootConfig fromJson(
                @JsonProperty(ENABLED) Boolean enabled, @JsonProperty(DEFAULT_MODE) String defaultMode) {
            String raw = defaultMode != null ? defaultMode : RateLimitMode.LOCAL.name();
            RateLimitMode resolvedMode;
            try {
                resolvedMode = RateLimitMode.valueOf(raw);
            } catch (IllegalArgumentException invalid) {
                throw new ConfigurationException("rateLimit.defaultMode must be one of "
                        + Arrays.toString(RateLimitMode.values()) + ", got '" + raw + "'");
            }
            return new RateLimitRootConfig(
                    enabled != null ? enabled : RateLimiters.DEFAULT_RATE_LIMIT_ENABLED, resolvedMode);
        }
    }

    /**
     * Typed {@code rateLimit.local.*} configuration, parsed through the canonical injected {@link
     * ConfigParser} (R10). Every field defaults when omitted, mirroring the documented defaults
     * (contracts/rate-limit-runtime.md, "Configuration" example: {@code maxTrackedKeys=100000},
     * {@code cleanupIntervalMs=60000}) — see {@link #localRateLimitBackend} for the eager bound
     * validation applied to the resolved values.
     *
     * @param maxTrackedKeys defaults to {@link #DEFAULT_MAX_TRACKED_KEYS} when omitted
     * @param cleanupIntervalMs defaults to {@link #DEFAULT_CLEANUP_INTERVAL_MS} when omitted
     */
    record RateLimitLocalConfig(long maxTrackedKeys, long cleanupIntervalMs) {

        @JsonCreator
        static RateLimitLocalConfig fromJson(
                @JsonProperty(MAX_TRACKED_KEYS) Long maxTrackedKeys,
                @JsonProperty(CLEANUP_INTERVAL_MS) Long cleanupIntervalMs) {
            return new RateLimitLocalConfig(
                    maxTrackedKeys != null ? maxTrackedKeys : DEFAULT_MAX_TRACKED_KEYS,
                    cleanupIntervalMs != null ? cleanupIntervalMs : DEFAULT_CLEANUP_INTERVAL_MS);
        }
    }

    /**
     * Typed {@code rateLimit.policies.<name>.local} per-policy override section, parsed through the
     * canonical injected {@link ConfigParser} (R10). Unlike {@link RateLimitLocalConfig}, {@code
     * maxTrackedKeys} stays a nullable boxed {@link Long} here — {@code null} distinguishes "no
     * override present" (fall back to the resolved global default) from an explicit override,
     * which standard Jackson record binding over the parser's lenient mapper already handles
     * without a custom {@code @JsonCreator}.
     *
     * @param maxTrackedKeys the per-policy override, or {@code null} when this policy declares none
     */
    record RateLimitPolicyLocalOverrideConfig(Long maxTrackedKeys) {}

    /**
     * Merges root-configuration policies over Dagger {@code @IntoSet}-contributed (programmatic)
     * policies: a config policy replaces a same-name programmatic policy <strong>wholesale</strong>
     * — fields never merge across tiers (contracts/rate-limit-runtime.md, "Policy model"). A name
     * present in only one tier passes through unchanged. This is a pure resolution step; it never
     * rejects a same-name overlap between tiers as a duplicate — that override is the intended
     * mechanism. {@link RateLimiters}'s own constructor separately rejects any duplicate name that
     * survives resolution into one flat set.
     *
     * @param configPolicies policies resolved from typed {@code rateLimit.policies.<name>} configuration
     * @param programmaticPolicies policies contributed via Dagger {@code @IntoSet RateLimitPolicy}
     * @return the merged policy set, one entry per distinct name, config-tier winning on overlap
     */
    private static Set<RateLimitPolicy> mergeConfigOverProgrammatic(
            Set<RateLimitPolicy> configPolicies, Set<RateLimitPolicy> programmaticPolicies) {
        Map<String, RateLimitPolicy> merged = new LinkedHashMap<>();
        programmaticPolicies.forEach(policy -> merged.put(policy.name(), policy));
        configPolicies.forEach(policy -> merged.put(policy.name(), policy));
        return Set.copyOf(merged.values());
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
     * Contributes the LOCAL Bucket4j backend, resolving each policy's own bounded registry size
     * lazily by name: {@code rateLimit.policies.<name>.local.maxTrackedKeys} when present, else the
     * shared {@code rateLimit.local.maxTrackedKeys} default (contracts/rate-limit-runtime.md,
     * "Local engine contract" — per-policy budget, never one shared pool).
     *
     * <p>{@code rateLimit.local.maxTrackedKeys}/{@code cleanupIntervalMs} (default and any
     * per-policy override) are eagerly bounds-validated here, at this provider's own construction —
     * forced eager the same way {@link RateLimiters}'s own startup validation is (this class's
     * javadoc, "Dagger wiring"), so a misconfigured bound fails application startup before any
     * handle is requested, never lazily at first LOCAL admission.
     *
     * @param config the raw {@code rateLimit.*} configuration section
     * @param parser the framework's config-parsing seam
     * @return the LOCAL {@link RateLimitBackend}
     * @throws ConfigurationException if the resolved global {@code maxTrackedKeys}/{@code
     *     cleanupIntervalMs}, or any per-policy {@code maxTrackedKeys} override, is below 1
     */
    @Provides
    @IntoMap
    @Singleton
    @RateLimitModeKey(RateLimitMode.LOCAL)
    static RateLimitBackend localRateLimitBackend(@VertxConfig JsonObject config, ConfigParser parser) {
        JsonObject rateLimit = JsonConfigPaths.navigateObject(config, "rateLimit");
        JsonObject local = JsonConfigPaths.navigateObject(rateLimit, LOCAL);
        RateLimitLocalConfig localConfig = parser.parse(local, RateLimitLocalConfig.class);
        requireAtLeastOne(localConfig.maxTrackedKeys(), "rateLimit.local.maxTrackedKeys");
        requireAtLeastOne(localConfig.cleanupIntervalMs(), "rateLimit.local.cleanupIntervalMs");
        JsonObject policiesJson = JsonConfigPaths.navigateObject(rateLimit, POLICIES);
        validatePerPolicyMaxTrackedKeysOverrides(policiesJson, parser);
        ToLongFunction<String> maxTrackedKeysResolver =
                policyName -> maxTrackedKeysFor(policiesJson, policyName, localConfig.maxTrackedKeys(), parser);
        return LocalRateLimitBackendFactory.local(maxTrackedKeysResolver, localConfig.cleanupIntervalMs());
    }

    private static long maxTrackedKeysFor(
            JsonObject policiesJson, String policyName, long defaultValue, ConfigParser parser) {
        JsonObject policyJson = JsonConfigPaths.navigateObject(policiesJson, policyName);
        JsonObject policyLocal = JsonConfigPaths.navigateObject(policyJson, LOCAL);
        RateLimitPolicyLocalOverrideConfig override =
                parser.parse(policyLocal, RateLimitPolicyLocalOverrideConfig.class);
        return override.maxTrackedKeys() != null ? override.maxTrackedKeys() : defaultValue;
    }

    /** Eagerly validates every present per-policy {@code local.maxTrackedKeys} override, by name. */
    private static void validatePerPolicyMaxTrackedKeysOverrides(JsonObject policiesJson, ConfigParser parser) {
        policiesJson.forEach(entry -> {
            if (entry.getValue() instanceof JsonObject policyJson) {
                JsonObject policyLocal = JsonConfigPaths.navigateObject(policyJson, LOCAL);
                RateLimitPolicyLocalOverrideConfig override =
                        parser.parse(policyLocal, RateLimitPolicyLocalOverrideConfig.class);
                if (override.maxTrackedKeys() != null) {
                    requireAtLeastOne(
                            override.maxTrackedKeys(),
                            "rateLimit.policies." + entry.getKey() + ".local.maxTrackedKeys");
                }
            }
        });
    }

    private static void requireAtLeastOne(long value, String path) {
        if (value < 1) {
            throw new ConfigurationException(path + " must be at least 1, got " + value);
        }
    }
}
