// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import java.util.Optional;

/**
 * Process-wide singleton registry for generated JAX-RS bean-param model companions.
 *
 * <p>For each {@code @BeanParam} or {@code @RequestParams} type {@code Foo}, the CG-010
 * pipeline emits a {@code Foo_BeanParamModel} companion in the same package. This registry
 * locates and caches those companions via a {@link ClassValue}-backed lookup so that
 * {@code ParameterExtractor} can use the fast generated model path instead of the reflective
 * {@code computeBeanFields} path when the companion is present.
 *
 * <h2>Lookup semantics</h2>
 *
 * <p>The registry derives the expected companion FQN from the bean class's binary name:
 * <ul>
 *   <li>package: same as the bean class</li>
 *   <li>simple name: binary class part with {@code '$'} replaced by {@code '_'}
 *       (e.g. {@code Outer$Inner} → {@code Outer_Inner})</li>
 *   <li>suffix: {@code _BeanParamModel}</li>
 * </ul>
 *
 * <p>Lookup uses the bean type directly — no superclass walk. See
 * {@link GeneratedJaxRsDescriptorRegistry} for the rationale.
 *
 * <h2>Cache-miss policy</h2>
 *
 * <ul>
 *   <li>{@link ClassNotFoundException} → cached as empty ({@link Optional#empty()}): no
 *       companion on the classpath; the caller falls back to the reflective
 *       {@code computeBeanFields} path.</li>
 *   <li>Any other {@link ReflectiveOperationException} or cast failure → the exception is
 *       wrapped, cached, and rethrown on every retrieval. A broken generated class is a build
 *       defect; silently masking it would hide the bug until the first affected request.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link ClassValue} is the thread-safety primitive. No additional locking is required.
 * Cache entries are GC-eligible alongside their owning class loader.
 *
 * @see GeneratedJaxRsBeanParamModel
 * @see GeneratedJaxRsDescriptorRegistry
 */
public final class GeneratedJaxRsBeanParamRegistry {

    /** Suffix appended to the bean class binary name to derive the companion FQN. */
    static final String SUFFIX = "_BeanParamModel";

    private static final GeneratedJaxRsBeanParamRegistry SHARED = new GeneratedJaxRsBeanParamRegistry();

    private final GeneratedCompanionRegistry<GeneratedJaxRsBeanParamModel<?>> delegate =
            new GeneratedCompanionRegistry<>(SUFFIX, castCompanionInterface());

    /**
     * Creates a new registry instance. Prefer {@link #shared()} for production use; this
     * constructor is provided for tests that need isolated registry instances.
     */
    public GeneratedJaxRsBeanParamRegistry() {}

    /**
     * Returns the process-wide shared registry instance.
     *
     * @return the shared singleton; never {@code null}
     */
    public static GeneratedJaxRsBeanParamRegistry shared() {
        return SHARED;
    }

    /**
     * Looks up the generated bean-param model for the given bean class.
     *
     * <p>The lookup uses the bean type directly — no superclass walk is performed. See
     * {@link GeneratedJaxRsDescriptorRegistry} for the rationale.
     *
     * @param beanType the concrete bean class to look up; must not be {@code null}
     * @return the model companion, or {@link Optional#empty()} if no companion exists on the
     *         classpath
     * @throws RuntimeException if a companion class exists but cannot be instantiated or is not
     *                          a valid {@link GeneratedJaxRsBeanParamModel}
     */
    public Optional<GeneratedJaxRsBeanParamModel<?>> lookup(Class<?> beanType) {
        return delegate.lookup(beanType);
    }

    /**
     * Derives the expected companion class FQN for the given bean type.
     *
     * <p>Package-private so that registry tests can byte-equality-check the result against the
     * codegen-side {@code Identifiers.generatedClassName(...)} with the {@code _BeanParamModel}
     * suffix.
     *
     * @param beanType the bean class
     * @return the FQN of the expected generated companion class
     */
    static String derivedFqn(Class<?> beanType) {
        return GeneratedCompanionRegistry.derivedFqn(beanType, SUFFIX);
    }

    /**
     * Produces a typed reference to the raw {@link GeneratedJaxRsBeanParamModel} class,
     * suppressing the unchecked-cast warning at a single well-understood site.
     *
     * @return the companion interface class, cast to the wildcard-parameterized form
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Class<GeneratedJaxRsBeanParamModel<?>> castCompanionInterface() {
        return (Class<GeneratedJaxRsBeanParamModel<?>>) (Class) GeneratedJaxRsBeanParamModel.class;
    }
}
