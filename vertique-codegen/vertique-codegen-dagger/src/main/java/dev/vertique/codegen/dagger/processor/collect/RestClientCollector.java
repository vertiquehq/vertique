// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor.collect;

import dev.vertique.codegen.CodegenContext;
import java.util.List;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

/**
 * Annotation-rooted collector for REST client interfaces annotated with
 * {@code @RestClient}.
 *
 * <p>Discovers interfaces carrying {@code dev.vertique.rest.client.RestClient}. No
 * {@code @Inject} constructor validation is performed because these are interfaces — the proxy
 * instance is created at runtime by {@code RestClientFactory.builder().build(InterfaceType.class)}.
 *
 * <p>The collected bindings are routed to
 * {@link dev.vertique.codegen.dagger.processor.emit.RestClientModuleEmitter}, which emits
 * {@code @Provides @Singleton InterfaceType provideXxx(RestClientFactory factory)} methods
 * (not multibinding contributions).
 */
public final class RestClientCollector extends AnnotationRootedCollector {

    private static final String REST_CLIENT_FQN = "dev.vertique.rest.client.RestClient";

    /**
     * Constructs a {@code RestClientCollector} bound to the given context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public RestClientCollector(CodegenContext ctx) {
        super(ctx);
    }

    /**
     * Returns the single marker annotation FQN handled by this collector.
     *
     * @return a singleton list containing {@code "dev.vertique.rest.client.RestClient"}
     */
    @Override
    protected List<String> markerFqns() {
        return List.of(REST_CLIENT_FQN);
    }

    /**
     * Returns {@code false} — REST client interfaces have no constructors and do not require
     * {@code @Inject} constructor validation.
     *
     * @return {@code false}
     */
    @Override
    protected boolean requiresInjectConstructor() {
        return false;
    }

    /**
     * Rejects {@code @RestClient} on a non-interface type — including abstract classes, which the
     * base collector would otherwise drop silently. {@code RestClientFactory.build(...)} requires
     * an interface to generate a JDK proxy; placing {@code @RestClient} on a class (concrete or
     * abstract) would fail at runtime. Catching it at compile time gives a clearer error.
     */
    @Override
    protected boolean validateKind(TypeElement type) {
        if (type.getKind() != ElementKind.INTERFACE) {
            ctx.diagnostics()
                    .error(
                            type,
                            "@RestClient may only be applied to interfaces; %s is a %s",
                            type.getQualifiedName(),
                            type.getKind().name().toLowerCase());
            return false;
        }
        return true;
    }
}
