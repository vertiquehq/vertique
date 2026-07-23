// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import java.util.Optional;

/**
 * Process-wide singleton registry for generated JAX-RS resource descriptor companions.
 *
 * <p>For each concrete JAX-RS resource class {@code Foo}, the CG-010 pipeline emits a
 * {@code Foo_JaxRsDescriptor} companion in the same package. This registry locates and caches
 * those companions via a {@link ClassValue}-backed lookup so that
 * {@code JaxRsRouteRegistrar} can use the fast generated descriptor path instead of the
 * reflective {@code ResourceScanner} when the companion is present.
 *
 * <h2>Lookup semantics</h2>
 *
 * <p>The registry derives the expected companion FQN from the resource class's binary name:
 * <ul>
 *   <li>package: same as the resource class</li>
 *   <li>simple name: binary class part with {@code '$'} replaced by {@code '_'}
 *       (e.g. {@code Outer$Inner} → {@code Outer_Inner})</li>
 *   <li>suffix: {@code _JaxRsDescriptor}</li>
 * </ul>
 *
 * <p>Lookup uses {@code resource.getClass()} exactly — no superclass walk. A registry-side
 * hierarchy walk would silently match descriptors through subclasses or proxies, registering
 * resources the runtime currently skips. Inherited-resource and proxy support is a separate
 * future PRD.
 *
 * <h2>Cache-miss policy</h2>
 *
 * <ul>
 *   <li>{@link ClassNotFoundException} → cached as empty ({@link Optional#empty()}): no
 *       companion on the classpath; the caller falls back to the reflective
 *       {@code ResourceScanner}.</li>
 *   <li>Any other {@link ReflectiveOperationException} or cast failure → the exception is
 *       wrapped, cached, and rethrown on every retrieval. A broken generated class is a build
 *       defect; silently masking it would hide the bug until the first affected request.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link ClassValue} is the thread-safety primitive. No additional locking is required.
 * Cache entries are GC-eligible alongside their owning class loader, preventing retention leaks
 * in multi-classloader environments.
 *
 * @see GeneratedJaxRsResourceDescriptor
 * @see GeneratedJaxRsBeanParamRegistry
 */
public final class GeneratedJaxRsDescriptorRegistry {

    /** Suffix appended to the resource class binary name to derive the companion FQN. */
    static final String SUFFIX = "_JaxRsDescriptor";

    private static final GeneratedJaxRsDescriptorRegistry SHARED = new GeneratedJaxRsDescriptorRegistry();

    private final GeneratedCompanionRegistry<GeneratedJaxRsResourceDescriptor<?>> delegate =
            new GeneratedCompanionRegistry<>(SUFFIX, castCompanionInterface());

    /**
     * Creates a new registry instance. Prefer {@link #shared()} for production use; this
     * constructor is provided for tests that need isolated registry instances.
     */
    public GeneratedJaxRsDescriptorRegistry() {}

    /**
     * Returns the process-wide shared registry instance.
     *
     * @return the shared singleton; never {@code null}
     */
    public static GeneratedJaxRsDescriptorRegistry shared() {
        return SHARED;
    }

    /**
     * Looks up the generated JAX-RS resource descriptor for the given resource class.
     *
     * <p>The lookup uses the resource type directly — no superclass walk is performed. See
     * the class-level javadoc for the rationale.
     *
     * @param resourceType the concrete resource class to look up; must not be {@code null}
     * @return the descriptor companion, or {@link Optional#empty()} if no companion exists on
     *         the classpath
     * @throws RuntimeException if a companion class exists but cannot be instantiated or is not
     *                          a valid {@link GeneratedJaxRsResourceDescriptor}
     */
    public Optional<GeneratedJaxRsResourceDescriptor<?>> lookup(Class<?> resourceType) {
        return delegate.lookup(resourceType);
    }

    /**
     * Derives the expected companion class FQN for the given resource type.
     *
     * <p>Package-private so that registry tests can byte-equality-check the result against the
     * codegen-side {@code Identifiers.generatedClassName(...)} with the {@code _JaxRsDescriptor}
     * suffix.
     *
     * @param resourceType the resource class
     * @return the FQN of the expected generated companion class
     */
    static String derivedFqn(Class<?> resourceType) {
        return GeneratedCompanionRegistry.derivedFqn(resourceType, SUFFIX);
    }

    /**
     * Produces a typed reference to the raw {@link GeneratedJaxRsResourceDescriptor} class,
     * suppressing the unchecked-cast warning at a single well-understood site.
     *
     * @return the companion interface class, cast to the wildcard-parameterized form
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Class<GeneratedJaxRsResourceDescriptor<?>> castCompanionInterface() {
        return (Class<GeneratedJaxRsResourceDescriptor<?>>) (Class) GeneratedJaxRsResourceDescriptor.class;
    }
}
