// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import jakarta.inject.Provider;
import java.util.Objects;

/**
 * INTERNAL framework seam — generated-code contract; not for hand-written use and outside the
 * maturity promise. Emitted by {@code vertique-codegen-jaxrs}'s
 * {@code GeneratedJaxRsResourcesModuleEmitter} and consumed by the package-private
 * {@code JaxRsApplicationComposer}.
 *
 * <p>Describes one DI-eligible JAX-RS resource contributed to the generated resource catalog: its
 * declared type, whether its conditional-activation annotation currently matches, and a lazy
 * {@link Provider} that constructs the resource instance on demand. The catalog stays lazy:
 * {@link #get()} is called only for a resource an active application actually selects.
 *
 * <p>This type evolves additively: a new factory overload may be added, and an existing factory
 * signature stays for at least one further minor release line, so a jar compiled by an earlier
 * annotation processor keeps linking against a newer runtime.
 */
public final class GeneratedJaxRsResourceEntry {

    private final Class<?> type;
    private final boolean enabled;
    private final Provider<?> provider;

    private GeneratedJaxRsResourceEntry(Class<?> type, boolean enabled, Provider<?> provider) {
        this.type = type;
        this.enabled = enabled;
        this.provider = provider;
    }

    /**
     * Creates a new catalog entry.
     *
     * @param type     the resource's declared type
     * @param enabled  whether the resource's {@code @ConditionalOnProperty} conditions currently
     *                 match, as evaluated by the generated binding method
     * @param provider lazily constructs the resource instance; never invoked by this factory
     * @return the new entry
     * @throws NullPointerException if {@code type} or {@code provider} is {@code null}
     */
    public static GeneratedJaxRsResourceEntry of(Class<?> type, boolean enabled, Provider<?> provider) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        return new GeneratedJaxRsResourceEntry(type, enabled, provider);
    }

    /**
     * Returns the resource's declared type.
     *
     * @return the declared type
     */
    public Class<?> type() {
        return type;
    }

    /**
     * Returns whether the resource's conditions currently match.
     *
     * @return {@code true} when the resource is enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Constructs the resource instance by delegating to the underlying provider.
     *
     * @return the constructed resource instance
     */
    public Object get() {
        return provider.get();
    }
}
