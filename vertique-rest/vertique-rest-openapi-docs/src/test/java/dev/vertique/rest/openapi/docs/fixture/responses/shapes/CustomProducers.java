// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.shapes;

import dev.vertique.rest.core.response.ResponseProducer;
import dev.vertique.rest.core.response.ResponseProducerBinding;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;
import java.util.Set;

/**
 * The response producer binding for {@link Custom}, as an application contributes it to the {@code
 * Set<ResponseProducerBinding<?>>} multibinding, and the producer-binding set the return-shape
 * classification receives.
 *
 * <p>The producer is never invoked: only the binding's {@link ResponseProducerBinding#type() type}
 * matters to a document.
 */
public final class CustomProducers {

    private static final ResponseProducerBinding<Custom> BINDING =
            new ResponseProducerBinding<>(Custom.class, new CustomProducer());

    private CustomProducers() {}

    /**
     * Returns the binding of {@link Custom} to {@link CustomProducer}; every call returns the same
     * instance.
     *
     * @return the binding
     */
    public static ResponseProducerBinding<Custom> binding() {
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
    public static final class CustomProducer implements ResponseProducer<Custom> {

        /** Creates the producer. */
        public CustomProducer() {}

        @Override
        public Response produce(RoutingContext ctx, Custom result) {
            return Response.accepted().build();
        }
    }
}
