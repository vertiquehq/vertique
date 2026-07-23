// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the rest-jaxrs operation-descriptor surface: {@link JaxRsOperationDescriptor},
 * {@link ParamDescriptor}, and {@link BodyDescriptor}.
 *
 * <p>Verifies the inheritance contract ({@code JaxRsOperationDescriptor extends
 * RestOperationDescriptor}), the parameter/body accessor contract, and record equality for
 * {@link ParamDescriptor}.
 */
class JaxRsOperationDescriptorTest {

    /**
     * Minimal {@link JaxRsOperationDescriptor} test stub with configurable parameters and body.
     */
    private static JaxRsOperationDescriptor stub(List<ParamDescriptor> params, Optional<BodyDescriptor> body) {
        return new JaxRsOperationDescriptor() {
            @Override
            public String operationId() {
                return "getThing";
            }

            @Override
            public String httpMethod() {
                return "GET";
            }

            @Override
            public String routeTemplate() {
                return "/things/{id}";
            }

            @Override
            public List<String> consumes() {
                return List.of();
            }

            @Override
            public List<String> produces() {
                return List.of();
            }

            @Override
            public SecurityPolicy securityPolicy() {
                return new SecurityPolicy.None();
            }

            @Override
            public List<SecurityRequirementSet> securityRequirementSets() {
                return List.of();
            }

            @Override
            public List<Annotation> methodAnnotations() {
                return List.of();
            }

            @Override
            public List<Annotation> classAnnotations() {
                return List.of();
            }

            @Override
            public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
                return Optional.empty();
            }

            @Override
            public List<ParamDescriptor> parameters() {
                return params;
            }

            @Override
            public List<FilePartDescriptor> fileParts() {
                return List.of();
            }

            @Override
            public Optional<BodyDescriptor> body() {
                return body;
            }
        };
    }

    @Test
    @DisplayName("JaxRsOperationDescriptor extends RestOperationDescriptor — operationId same via both views")
    void jaxRsOperationDescriptorExtendsRestOperationDescriptor() {
        JaxRsOperationDescriptor op = stub(List.of(), Optional.empty());
        RestOperationDescriptor base = op;
        assertEquals(op.operationId(), base.operationId());
        assertEquals("getThing", base.operationId());
    }

    @Test
    @DisplayName("JaxRsOperationDescriptor exposes the supplied parameters and body")
    void jaxRsOperationDescriptorExposesParametersAndBody() {
        ParamDescriptor p1 = new ParamDescriptor("id", ParamLocation.PATH, Integer.class, null, null, null, List.of());
        ParamDescriptor p2 = new ParamDescriptor("tag", ParamLocation.QUERY, String.class, null, null, null, List.of());
        BodyDescriptor bodyDesc = new BodyDescriptor(String.class, null, List.of());

        JaxRsOperationDescriptor op = stub(List.of(p1, p2), Optional.of(bodyDesc));

        assertEquals(List.of(p1, p2), op.parameters());
        assertTrue(op.body().isPresent());
        assertSame(bodyDesc, op.body().get());
    }

    @Test
    @DisplayName("fileParts is an explicit operation descriptor contract")
    void filePartsIsExplicitContract() throws NoSuchMethodException {
        assertFalse(JaxRsOperationDescriptor.class.getMethod("fileParts").isDefault());
        assertEquals(List.of(), stub(List.of(), Optional.empty()).fileParts());
    }

    @Test
    @DisplayName("ParamDescriptor record equality — identical fields are equal")
    void paramDescriptorRecordEquality() {
        ParamDescriptor a = new ParamDescriptor("id", ParamLocation.PATH, Integer.class, null, null, null, List.of());
        ParamDescriptor b = new ParamDescriptor("id", ParamLocation.PATH, Integer.class, null, null, null, List.of());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
