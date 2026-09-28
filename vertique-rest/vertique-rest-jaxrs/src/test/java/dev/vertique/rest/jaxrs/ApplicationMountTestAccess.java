// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.jaxrs.publication.RestApplications;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Set;

/**
 * Test-support accessor exposing {@link JaxRsRouterMount}'s package-private ordered-resources,
 * application-identity, and validated-mark accessors, a default {@link JaxRsRouterMount.Factory},
 * and {@link JaxRsApplicationMountValidator}'s construction to the {@code
 * dev.vertique.rest.jaxrs.application} test package, which cannot reach a package-private member
 * or the package-private {@link TestFactories} declared in this (different) test package. Calls
 * only {@link JaxRsRouterMount#orderedResources()}, {@link JaxRsRouterMount#applicationName()},
 * {@link JaxRsRouterMount#declaringType()}, {@link JaxRsRouterMount#isValidated()}, {@link
 * TestFactories}, and the package-private {@code JaxRsRouterMount.Factory#createApplicationMount}
 * overload and {@link JaxRsApplicationMountValidator} constructor (E4) — no other access (G-05).
 */
public final class ApplicationMountTestAccess {

    private ApplicationMountTestAccess() {}

    /**
     * Returns the given mount's resources in their iteration order.
     *
     * @param mount the mount to read
     * @return the mount's resources, in their iteration order (for an application mount, ordered
     *     by fully qualified class name)
     */
    public static List<Object> orderedResources(JaxRsRouterMount mount) {
        return mount.orderedResources();
    }

    /**
     * Returns the given mount's declared application name, or {@code null} for a mount not built
     * from a declared application (for example, the zero-declaration default mount or a hand-built
     * mount).
     *
     * @param mount the mount to read
     * @return the declared application name, or {@code null}
     */
    @Nullable
    public static String applicationName(JaxRsRouterMount mount) {
        return mount.applicationName();
    }

    /**
     * Returns the given mount's declaring type, or {@code null} for a mount not built from a
     * declared application.
     *
     * @param mount the mount to read
     * @return the declaring type, or {@code null}
     */
    @Nullable
    public static Class<?> declaringType(JaxRsRouterMount mount) {
        return mount.declaringType();
    }

    /**
     * Returns whether the given mount was marked validated by a {@link MountCompositionValidator}.
     *
     * @param mount the mount to read
     * @return {@code true} when a composition validator marked this mount valid
     */
    public static boolean isValidated(JaxRsRouterMount mount) {
        return mount.isValidated();
    }

    /**
     * Builds an application mount through the package-private 5-argument
     * {@code JaxRsRouterMount.Factory#createApplicationMount}, for tests that assemble mount lists
     * by hand (E4).
     *
     * @param factory         the mount factory
     * @param mountPath       the application's mount path, as registered
     * @param openapiPath     the effective OpenAPI contract location, or {@code null}
     * @param resources       the application's resolved resources
     * @param applicationName the application's name
     * @param declaringType   the application's declaring type
     * @return the built application mount
     */
    public static JaxRsRouterMount createApplicationMount(
            JaxRsRouterMount.Factory factory,
            String mountPath,
            @Nullable String openapiPath,
            Set<Object> resources,
            String applicationName,
            Class<?> declaringType) {
        return factory.createApplicationMount(mountPath, openapiPath, resources, applicationName, declaringType);
    }

    /**
     * Returns the given validator as a {@link MountCompositionValidator}, for tests that call
     * {@link MountCompositionValidator#validate(List)} directly on a hand-built mount list (E4).
     *
     * @param validator the rest-jaxrs mount composition validator under test
     * @return {@code validator}, typed as {@link MountCompositionValidator}
     */
    public static MountCompositionValidator asValidator(Object validator) {
        return (MountCompositionValidator) validator;
    }

    /**
     * Returns a default {@link JaxRsRouterMount.Factory}, wired with the same inert stubs {@link
     * TestFactories} gives every rest-jaxrs routing test, for tests in the {@code application} test
     * package that build mounts by hand through {@link JaxRsRouterMount.Factory#create} or {@link
     * #createApplicationMount} (TP-010, E4). {@link TestFactories} is package-private, so a test
     * outside this package cannot call it directly.
     *
     * @return a fresh default factory
     */
    public static JaxRsRouterMount.Factory factory() {
        return TestFactories.builder().build();
    }

    /**
     * Builds a {@link JaxRsApplicationMountValidator} — {@code
     * dev.vertique.rest.jaxrs.JaxRsApplicationMountValidator} is package-private — and returns it
     * typed as {@link MountCompositionValidator}, for tests in the {@code application} test package
     * that construct a validator directly, bypassing {@code RestModule} (TP-010, TP-020, E4).
     *
     * @param view       the application view the validator reads its declared registrations from
     * @param config     the JAX-RS routing configuration, carrying the configured
     *                   {@code jaxrs.validationStrategy} id
     * @param strategies the registered request-validation strategies, resolved by id against
     *                   {@code config}'s configured id
     * @return the built validator, typed as {@link MountCompositionValidator}
     */
    public static MountCompositionValidator newValidator(
            RestApplications view, JaxRsConfig config, Set<RequestValidationStrategy> strategies) {
        return new JaxRsApplicationMountValidator(view, config, strategies);
    }
}
