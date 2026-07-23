// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.context.ContextValues;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;

/**
 * {@link SecurityRuntime} implementation backed by the unified {@link dev.vertique.core.context.ContextHolder}
 * via {@link ContextValues}.
 *
 * <p>Reads and writes the current {@link SecurityContext} using the substrate's typed per-request
 * storage (keyed by {@code SecurityContext.class.getName()}). This replaces the former
 * {@code SecurityContextHolder} / {@code ContextLocalSecurityRuntime} infrastructure, which
 * maintained its own dedicated {@code ContextLocal} slot.
 *
 * <p>The JAX-RS bridge factory is injected via Dagger. {@link SecurityModule} provides the
 * factory binding, so an application that wires {@code SecurityModule} alone has a complete
 * Dagger graph for the runtime — pulling in {@code AuthModule} is not required.
 */
@Singleton
public class HolderBackedSecurityRuntime implements SecurityRuntime {

    private final JaxRsSecurityContextFactory jaxRsFactory;

    /**
     * Creates a new {@code HolderBackedSecurityRuntime}.
     *
     * @param jaxRsFactory factory for creating JAX-RS {@code SecurityContext} bridges; must not be {@code null}
     */
    @Inject
    public HolderBackedSecurityRuntime(JaxRsSecurityContextFactory jaxRsFactory) {
        this.jaxRsFactory = Objects.requireNonNull(jaxRsFactory, "jaxRsFactory must not be null");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads from the {@link ContextValues} facade. Returns {@code null} when called outside a
     * Vert.x context or when no binding exists for the current request.
     */
    @Override
    public SecurityContext current() {
        return ContextValues.current(SecurityContext.class).orElse(null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link ContextValues#bind(Class, Object)} which enforces the
     * duplicated-context invariant. The returned scope, when closed, restores the prior binding
     * (or removes the key if it was absent before this call).
     *
     * @throws NullPointerException  if {@code context} is {@code null}
     * @throws IllegalStateException if called outside a Vert.x duplicated context
     */
    @Override
    public ContextHolder.Scope bindCurrent(SecurityContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return ContextValues.bind(SecurityContext.class, context);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to the injected {@link JaxRsSecurityContextFactory}, which is always present
     * because {@link SecurityModule} provides the binding as part of the runtime graph.
     */
    @Override
    public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
        return jaxRsFactory.create(context, secure);
    }
}
