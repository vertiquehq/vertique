// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.shared;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.json.schema.AnnotationJsonSchemaGenerator;
import dev.vertique.json.schema.CanonicalSchema;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.json.JsonObject;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stateful source that switches the request body of {@value #SWITCHED_OPERATION} from {@link
 * OrderV1} to {@link OrderV2} after its first resolution of that operation.
 *
 * <p>For {@value #SWITCHED_OPERATION} it returns the delegate's result rebuilt with one change: the
 * body schema is the input-direction description of the current variant by {@link
 * AnnotationJsonSchemaGenerator#describe}, attached together with the redaction data {@code describe}
 * produced for exactly that schema. So each variant, taken alone, is what the framework's generator
 * would bind. Which variant is current:
 *
 * <ul>
 *   <li>{@link #startingAtFirstVariant}: {@link OrderV1} on the first resolution, {@link OrderV2} on
 *       every later one;
 *   <li>{@link #startingAtSecondVariant}: {@link OrderV2} on every resolution.
 * </ul>
 *
 * <p>Every other operation's schemas are the delegate's result, unchanged. Thread-safe.
 */
public final class OrderBodySwitchingSchemaSource implements OperationSchemaSource {

    /** The operation whose body this source switches. */
    public static final String SWITCHED_OPERATION = BackOfficeOrderResource.CREATE_ORDER;

    private final OperationSchemaSource delegate;
    private final boolean startsAtSecondVariant;
    private final AtomicInteger orderResolutions = new AtomicInteger();

    private OrderBodySwitchingSchemaSource(OperationSchemaSource delegate, boolean startsAtSecondVariant) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.startsAtSecondVariant = startsAtSecondVariant;
    }

    /**
     * Creates a source that describes {@link OrderV1} on its first resolution of {@value
     * #SWITCHED_OPERATION} and {@link OrderV2} on every later one.
     *
     * @param delegate the source whose results are returned for every operation
     * @return the source
     */
    public static OrderBodySwitchingSchemaSource startingAtFirstVariant(OperationSchemaSource delegate) {
        return new OrderBodySwitchingSchemaSource(delegate, false);
    }

    /**
     * Creates a source that describes {@link OrderV2} on every resolution of {@value
     * #SWITCHED_OPERATION}.
     *
     * @param delegate the source whose results are returned for every operation
     * @return the source
     */
    public static OrderBodySwitchingSchemaSource startingAtSecondVariant(OperationSchemaSource delegate) {
        return new OrderBodySwitchingSchemaSource(delegate, true);
    }

    @Override
    public OperationSchemas schemasFor(JaxRsOperationDescriptor op, JsonMapperProfile profile) {
        OperationSchemas canonical = delegate.schemasFor(op, profile);
        if (!SWITCHED_OPERATION.equals(op.operationId())) {
            return canonical;
        }
        int resolution = orderResolutions.incrementAndGet();
        Class<?> variant = startsAtSecondVariant || resolution > 1 ? OrderV2.class : OrderV1.class;
        CanonicalSchema described =
                AnnotationJsonSchemaGenerator.forInputProfile(profile).describe(variant);
        return canonical.toBuilder()
                .bodySchema(new JsonObject(described.json()), described.redactionManifest())
                .build();
    }

    /**
     * Returns how often {@value #SWITCHED_OPERATION} was resolved.
     *
     * @return the resolution count
     */
    public int orderResolutions() {
        return orderResolutions.get();
    }
}
