// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.shapes;

import dev.vertique.rest.core.response.ResponseProducer;
import dev.vertique.rest.core.response.ResponseProducerBinding;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;
import java.util.Set;

/**
 * The response producer binding for the interface {@link CharSequence}, which {@code String}
 * implements, as an application contributes it to the {@code Set<ResponseProducerBinding<?>>}
 * multibinding, and the producer-binding set the return-shape classification receives.
 *
 * <p>The producer is never invoked: only the binding's {@link ResponseProducerBinding#type() type}
 * matters to a document.
 */
public final class CharSequenceProducers {

    private static final ResponseProducerBinding<CharSequence> BINDING =
            new ResponseProducerBinding<>(CharSequence.class, new CharSequenceProducer());

    private CharSequenceProducers() {}

    /**
     * Returns the binding of {@link CharSequence} to {@link CharSequenceProducer}; every call returns
     * the same instance.
     *
     * @return the binding
     */
    public static ResponseProducerBinding<CharSequence> binding() {
        return BINDING;
    }

    /**
     * Returns a set holding only {@link #binding()}.
     *
     * @return the unmodifiable set
     */
    public static Set<ResponseProducerBinding<?>> bindings() {
        return Set.of(BINDING);
    }

    /** A producer that answers {@code 202 Accepted} without a body. */
    public static final class CharSequenceProducer implements ResponseProducer<CharSequence> {

        /** Creates the producer. */
        public CharSequenceProducer() {}

        @Override
        public Response produce(RoutingContext ctx, CharSequence result) {
            return Response.accepted().build();
        }
    }
}
