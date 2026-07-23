// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.core.VertiqueComponentFactory;
import dev.vertique.core.VertiqueRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * ServiceLoader discovery of the application's single {@link VertiqueComponentFactory}.
 *
 * <p>The standalone entry ({@link VertiqueBootstrapVerticle}) has no compile-time knowledge of which
 * Dagger component a given application assembles. It discovers the application's
 * {@link VertiqueComponentFactory} from the classpath via {@link ServiceLoader} (registered in
 * {@code META-INF/services/dev.vertique.core.VertiqueComponentFactory}) and requires
 * <strong>exactly one</strong>:
 * <ul>
 *   <li><strong>zero</strong> discovered → fail-fast: no application factory is registered;</li>
 *   <li><strong>more than one</strong> → fail-fast listing the discovered FQNs (the framework cannot
 *       pick between competing application graphs).</li>
 * </ul>
 *
 * <p>Diagnostics on either failure name the classloader used for discovery and point at the
 * {@code META-INF/services} resource and the generated-vs-manual registration paths so an operator
 * can locate the missing or duplicate provider configuration.
 *
 * <p>The two halves are split for testability: {@link #discover()} performs the I/O-bound
 * {@link ServiceLoader} load and delegates the exactly-one rule to the pure
 * {@link #selectFactory(List)} method, which is unit-tested directly with synthetic lists.
 */
final class VertiqueComponentFactoryLoader {

    /** The {@code META-INF/services} resource name a provider config file must use. */
    static final String SERVICE_RESOURCE = "META-INF/services/" + VertiqueComponentFactory.class.getName();

    private VertiqueComponentFactoryLoader() {}

    /**
     * Discovers all {@link VertiqueComponentFactory} providers via {@link ServiceLoader} on the
     * thread context classloader (falling back to this class's classloader) and applies the
     * exactly-one rule via {@link #selectFactory(List)}.
     *
     * <p>The single discovered factory is returned wrapped in a {@link
     * VertiqueComponentFactory#build(VertiqueRuntime) build}-time validating delegate: the standalone
     * contract is that the registered factory builds a {@link VertiqueApplicationComponent} (so the
     * lifecycle runner can drive it). Because {@link ServiceLoader} erases the component type
     * parameter, a provider typed e.g. {@code VertiqueComponentFactory<Object>} would otherwise pass
     * discovery and then fail deep in the runner with an opaque {@link ClassCastException}. The
     * wrapper checks the produced component and throws a guided {@link IllegalStateException} naming
     * the provider FQN and the expected type instead (see {@link #validatingWrapper}).
     *
     * @return the single discovered factory, wrapped so its {@code build} validates the produced
     *     component type for the lifecycle runner
     * @throws IllegalStateException if zero or more than one provider is registered (see
     *     {@link #selectFactory(List)})
     * @throws java.util.ServiceConfigurationError if a {@code META-INF/services} provider entry is
     *     malformed (propagated from {@link ServiceLoader})
     */
    static VertiqueComponentFactory<VertiqueApplicationComponent> discover() {
        ClassLoader classLoader = discoveryClassLoader();

        List<VertiqueComponentFactory<?>> discovered = new ArrayList<>();
        // ServiceLoader uses the raw VertiqueComponentFactory type (generics are erased at the SPI
        // boundary); collect into a List<VertiqueComponentFactory<?>> for the selection rule.
        // A malformed provider entry throws ServiceConfigurationError here; the bootstrap verticle's
        // start() catches Throwable so it is surfaced as a clean failed start promise.
        ServiceLoader.load(VertiqueComponentFactory.class, classLoader).forEach(discovered::add);

        return selectFactory(discovered, classLoader);
    }

    /**
     * Applies the exactly-one rule to a discovered factory list, using this class's classloader for
     * diagnostics. Convenience overload of {@link #selectFactory(List, ClassLoader)} for unit tests
     * that do not exercise a specific classloader.
     *
     * @param discovered the discovered factories (may be empty)
     * @return the single factory, wrapped to validate the produced component type at build time
     * @throws IllegalStateException if {@code discovered} is empty or holds more than one element
     */
    static VertiqueComponentFactory<VertiqueApplicationComponent> selectFactory(
            List<VertiqueComponentFactory<?>> discovered) {
        return selectFactory(discovered, VertiqueComponentFactoryLoader.class.getClassLoader());
    }

    /**
     * Pure implementation of the exactly-one selection rule. The single discovered factory is
     * returned wrapped by {@link #validatingWrapper} so its {@code build} validates the produced
     * component type.
     *
     * @param discovered the discovered factories (may be empty)
     * @param classLoader the classloader used for discovery, named in the failure diagnostics
     * @return the single factory, wrapped to validate the produced component type at build time
     * @throws IllegalStateException if {@code discovered} is empty (no application factory) or holds
     *     more than one element (ambiguous — the message lists every discovered FQN)
     */
    static VertiqueComponentFactory<VertiqueApplicationComponent> selectFactory(
            List<VertiqueComponentFactory<?>> discovered, ClassLoader classLoader) {
        if (discovered.isEmpty()) {
            throw new IllegalStateException("No application " + VertiqueComponentFactory.class.getSimpleName()
                    + " found on the classpath. Register exactly one via " + SERVICE_RESOURCE
                    + " (or generate one). Discovery classloader: " + classLoader + ".");
        }
        if (discovered.size() > 1) {
            String fqns = discovered.stream()
                    .map(factory -> factory.getClass().getName())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException("Found " + discovered.size() + " application "
                    + VertiqueComponentFactory.class.getSimpleName()
                    + " implementations on the classpath but exactly one is required: [" + fqns
                    + "]. Keep a single provider entry in " + SERVICE_RESOURCE
                    + " (generated or manual). Discovery classloader: " + classLoader + ".");
        }
        return validatingWrapper(discovered.get(0));
    }

    /**
     * Wraps a discovered raw factory so its {@link VertiqueComponentFactory#build(VertiqueRuntime)
     * build} validates the produced component type. Because {@link ServiceLoader} erases the
     * component type parameter, the wrapper is the only place the standalone contract — that the
     * registered factory builds a {@link VertiqueApplicationComponent} — can be enforced. When the
     * raw factory's {@code build} returns {@code null} or a value that is not a {@link
     * VertiqueApplicationComponent}, the wrapper throws a guided {@link IllegalStateException} naming
     * the provider FQN and the expected type, instead of letting an opaque {@link ClassCastException}
     * surface deep in the runner. The wrapper is single-build (the runner calls it exactly once); it
     * delegates to the raw factory once per call with no caching.
     *
     * @param raw the single discovered factory; never {@code null}
     * @return a delegating factory whose {@code build} validates the produced component type
     */
    private static VertiqueComponentFactory<VertiqueApplicationComponent> validatingWrapper(
            VertiqueComponentFactory<?> raw) {
        String providerFqn = raw.getClass().getName();
        return runtime -> {
            Object component = raw.build(runtime);
            if (!(component instanceof VertiqueApplicationComponent vac)) {
                throw new IllegalStateException("VertiqueComponentFactory '" + providerFqn + "' produced "
                        + (component == null ? "null" : component.getClass().getName())
                        + ", but a standalone application factory must build a "
                        + VertiqueApplicationComponent.class.getName() + ".");
            }
            return vac;
        };
    }

    /**
     * Returns the classloader used for {@link ServiceLoader} discovery: the thread context
     * classloader when set, otherwise this class's classloader.
     *
     * @return the discovery classloader; never {@code null}
     */
    private static ClassLoader discoveryClassLoader() {
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        return (tccl != null) ? tccl : VertiqueComponentFactoryLoader.class.getClassLoader();
    }
}
