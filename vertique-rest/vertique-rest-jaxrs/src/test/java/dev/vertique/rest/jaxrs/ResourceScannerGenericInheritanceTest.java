// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Future;
import jakarta.ws.rs.*;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResourceScannerGenericInheritanceTest {
    interface Contract<T> {
        @POST
        default T accept(@QueryParam("id") T id, List<List<T>> body) {
            return id;
        }
    }

    interface Forward<U> extends Contract<U> {}

    @Path("/default")
    static class DefaultResource implements Forward<Integer> {}

    static class Base<T> {
        @POST
        public T accept(@BeanParam T bean) {
            return bean;
        }
    }

    static class Middle<U> extends Base<U> {}

    @Path("/base")
    static class Concrete extends Middle<Bean> {}

    static class Bean {
        @QueryParam("q")
        String q;
    }

    @Path("/raw")
    @SuppressWarnings("rawtypes")
    static class Raw extends Base {}

    @Test
    void bindsDefaultThroughMultipleInterfaces() throws Exception {
        var meta = new ResourceScanner(new SecurityPolicyBuilder())
                .scanResource(new DefaultResource())
                .getFirst();
        assertEquals(Integer.class, meta.params().get(0).type());
        var outer = (ParameterizedType) meta.params().get(1).genericType();
        var inner = (ParameterizedType) outer.getActualTypeArguments()[0];
        assertEquals(Integer.class, inner.getActualTypeArguments()[0]);
        ObjectMapper mapper = new ObjectMapper();
        List<?> decoded = mapper.readValue("[[42]]", mapper.constructType(outer));
        assertInstanceOf(Integer.class, ((List<?>) decoded.getFirst()).getFirst());
        assertEquals(Integer.class, meta.responseBodyType());
        assertEquals(Object.class, meta.method().getParameterTypes()[0]);
    }

    @Test
    void bindsSuperclassBeanAndReturn() {
        var meta = new ResourceScanner(new SecurityPolicyBuilder())
                .scanResource(new Concrete())
                .getFirst();
        assertEquals(Bean.class, meta.params().getFirst().type());
        assertEquals(Bean.class, meta.responseBodyType());
    }

    interface BodyContract<T, R> {
        @POST
        default R submit(List<List<T>> body) {
            return null;
        }
    }

    public static class Payload {
        public String value;
    }

    @Path("/body")
    static class BodyResource implements BodyContract<Payload, Future<Payload>> {}

    @Test
    void nestedBodyDecodesConcreteObjectsAndFutureReturn() throws Exception {
        var meta = new ResourceScanner(new SecurityPolicyBuilder())
                .scanResource(new BodyResource())
                .getFirst();
        ObjectMapper mapper = new ObjectMapper();
        List<?> decoded = mapper.readValue(
                "[[{\"value\":\"ok\"}]]",
                mapper.constructType(meta.params().getFirst().genericType()));
        Payload payload = assertInstanceOf(Payload.class, ((List<?>) decoded.getFirst()).getFirst());
        assertEquals("ok", payload.value);
        assertTrue(meta.returnsFuture());
        assertEquals(Payload.class, meta.responseBodyType());
    }

    @Test
    void rawSuperclassRetainsErasure() {
        var meta = new ResourceScanner(new SecurityPolicyBuilder())
                .scanResource(new Raw())
                .getFirst();
        assertEquals(Object.class, meta.params().getFirst().type());
    }
}
