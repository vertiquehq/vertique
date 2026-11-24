// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.health;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

/**
 * Dagger module that declares the {@link HealthCheck} multibinding sets for
 * {@link Liveness @Liveness} and {@link Readiness @Readiness} qualifiers, and binds the
 * {@link HealthCheckAggregator} that evaluates them.
 *
 * <p>Extracted into {@code core} so that non-management modules (e.g., {@code db-postgresql},
 * {@code services}) can contribute health checks without depending on the {@code management}
 * module.
 *
 * <p>Include this module in any Dagger component or module that contributes or consumes
 * health checks. {@code ManagementModule} includes it automatically.
 */
@Module
public abstract class HealthCheckModule {

    /**
     * Declares the empty-by-default multibinding set of liveness health checks.
     * Individual modules contribute entries via {@code @Provides @IntoSet @Liveness HealthCheck}.
     *
     * @return the set of liveness health checks (may be empty)
     */
    @Multibinds
    @Liveness
    abstract Set<HealthCheck> livenessChecks();

    /**
     * Declares the empty-by-default multibinding set of readiness health checks.
     * Individual modules contribute entries via {@code @Provides @IntoSet @Readiness HealthCheck}.
     *
     * @return the set of readiness health checks (may be empty)
     */
    @Multibinds
    @Readiness
    abstract Set<HealthCheck> readinessChecks();

    /**
     * Declares the application configuration root as an optional binding, so the aggregator can read
     * the health-check timeout when a component binds the configuration and still resolves, with the
     * default timeout, in a component that does not.
     *
     * @return the optional application configuration root
     */
    @BindsOptionalOf
    @VertxConfig
    abstract JsonObject optionalConfig();

    /**
     * Provides the aggregator that evaluates either set in process. Its per-check timeout is
     * {@code management.healthCheckTimeoutSeconds} when the component binds the application
     * configuration and sets that key, so an injected aggregator and the management probes give the
     * same answer; otherwise it is the {@linkplain HealthCheckAggregator#DEFAULT_CHECK_TIMEOUT_SECONDS
     * default}.
     * Inject it together with the qualified set to evaluate:
     * <pre>{@code
     * @Inject
     * ReadinessGate(HealthCheckAggregator aggregator, @Readiness Set<HealthCheck> readinessChecks) { ... }
     *
     * Future<HealthReport> ready = aggregator.evaluate(readinessChecks);
     * }</pre>
     *
     * <p>The binding is unscoped and the aggregator is stateless, so a component of any scope may
     * include this module. A consumer that applies a different timeout constructs its own
     * {@link HealthCheckAggregator#HealthCheckAggregator(long)}.
     *
     * @param config the application configuration root, when the component binds one
     * @return an aggregator with the configured, or else the default, per-check timeout
     * @throws ConfigurationException if {@code management.healthCheckTimeoutSeconds} is set but is not
     *     a positive whole number
     */
    @Provides
    static HealthCheckAggregator healthCheckAggregator(@VertxConfig Optional<JsonObject> config) {
        return config.map(HealthCheckModule::configuredTimeoutSeconds)
                .map(HealthCheckAggregator::new)
                .orElseGet(HealthCheckAggregator::new);
    }

    /**
     * Reads {@code management.healthCheckTimeoutSeconds} from the configuration root.
     *
     * @param config the application configuration root
     * @return the configured timeout in seconds, or {@code null} when the key is absent
     */
    private static Long configuredTimeoutSeconds(JsonObject config) {
        JsonObject management = JsonConfigPaths.navigateObject(config, "management");
        if (!management.containsKey("healthCheckTimeoutSeconds")) {
            return null;
        }
        Object value = management.getValue("healthCheckTimeoutSeconds");
        long seconds;
        if (value instanceof Number number) {
            if (hasFractionalPart(number)) {
                throw new ConfigurationException("management.healthCheckTimeoutSeconds must be a whole number");
            }
            seconds = number.longValue();
        } else if (value instanceof String text) {
            // Property-source values arrive as strings; the management probes coerce them the same way.
            try {
                seconds = Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                throw new ConfigurationException("management.healthCheckTimeoutSeconds must be a whole number");
            }
        } else {
            throw new ConfigurationException("management.healthCheckTimeoutSeconds must be a whole number");
        }
        if (seconds <= 0) {
            throw new ConfigurationException("management.healthCheckTimeoutSeconds must be positive");
        }
        return seconds;
    }

    /**
     * Tells whether a number is not a whole number: a floating-point or decimal value with a
     * fractional part, or one that is not finite.
     *
     * @param number the configured number
     * @return {@code true} when the number cannot be read as a whole number of seconds
     */
    private static boolean hasFractionalPart(Number number) {
        if (number instanceof Double || number instanceof Float) {
            double d = number.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d);
        }
        if (number instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().scale() > 0;
        }
        return false;
    }
}
