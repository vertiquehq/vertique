// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.deploy;

import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Verticle;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Descriptor for a verticle deployment with phase ordering, priority ordering, and deployment options.
 *
 * <p>Use the canonical constructor for full control, or the {@link #of} factory methods for common
 * cases with sensible defaults (priority 0, default {@link DeploymentOptions}).
 *
 * <p>For multi-instance deployment, set {@link DeploymentOptions#setInstances(int)} on the options.
 * The {@code supplier} is called once per instance, so it must return a fresh {@link Verticle} each
 * time. Dagger's {@code Provider<T>} satisfies this naturally when the verticle class is not
 * {@code @Singleton}-scoped.
 *
 * <p>The {@code phase} must be one of the {@link LifecyclePhase#isVerticlePhase() verticle-subset}
 * phases ({@link LifecyclePhase#BOOTSTRAP BOOTSTRAP}, {@link LifecyclePhase#INFRA INFRA},
 * {@link LifecyclePhase#SERVICES SERVICES}, {@link LifecyclePhase#EDGE EDGE}); a non-verticle phase
 * is rejected at construction.
 *
 * @param name human-readable name for logging and deployment ID lookup
 * @param supplier factory that creates a fresh verticle instance per call
 * @param options Vert.x deployment options (instances, threading model, config, etc.)
 * @param phase deployment phase for coarse-grained startup/shutdown ordering; must be a
 *     {@link LifecyclePhase#isVerticlePhase() verticle phase}
 * @param priority deployment priority within the phase; lower values deploy first, same-priority
 *     deploys in parallel
 */
public record VerticleDeployment(
        String name,
        Supplier<? extends Verticle> supplier,
        DeploymentOptions options,
        LifecyclePhase phase,
        int priority) {

    /**
     * Validates that required fields are non-null, that {@code phase} is a verticle phase, and
     * normalizes {@code null} options to defaults.
     *
     * @throws IllegalArgumentException if {@code phase} is not a
     *     {@link LifecyclePhase#isVerticlePhase() verticle phase}
     */
    public VerticleDeployment {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(phase, "phase");
        if (!phase.isVerticlePhase()) {
            throw new IllegalArgumentException("VerticleDeployment '" + name + "' has non-verticle phase " + phase
                    + "; legal verticle phases are BOOTSTRAP, INFRA, SERVICES, EDGE");
        }
        if (options == null) {
            options = new DeploymentOptions();
        }
    }

    /**
     * Creates a deployment with default options and priority 0.
     *
     * @param name human-readable deployment name
     * @param supplier verticle factory
     * @param phase deployment phase for ordering
     * @return a new deployment descriptor
     */
    public static VerticleDeployment of(String name, Supplier<? extends Verticle> supplier, LifecyclePhase phase) {
        return new VerticleDeployment(name, supplier, new DeploymentOptions(), phase, 0);
    }

    /**
     * Creates a deployment with default options and the given priority.
     *
     * @param name human-readable deployment name
     * @param supplier verticle factory
     * @param phase deployment phase for ordering
     * @param priority deployment priority within the phase (lower deploys first)
     * @return a new deployment descriptor
     */
    public static VerticleDeployment of(
            String name, Supplier<? extends Verticle> supplier, LifecyclePhase phase, int priority) {
        return new VerticleDeployment(name, supplier, new DeploymentOptions(), phase, priority);
    }
}
