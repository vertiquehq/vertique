// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.convert.ConversionContexts;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;

/**
 * Cookie names are case-sensitive (RFC 6265), so the validation gate reads a declared cookie only by
 * its exact name. The binder matches cookies the same way; the two must agree, otherwise the gate
 * validates a value the resource method never receives, or skips one it does.
 */
class CookieNameMatchingGateTest {

    private static final String DECLARED = "session";
    private static final String OPERATION_ID = "cookieNameMatching";

    @Test
    @DisplayName("A cookie sent under the declared name is validated against its schema")
    void exactNameCookieIsValidated() {
        Handler<RoutingContext> gate = gateForPatternedCookie();

        RoutingContext ctx = requestWithCookie(DECLARED, "bad");
        gate.handle(ctx);

        Throwable failure = singleFailure(ctx);
        assertInstanceOf(
                RestValidationException.class,
                failure,
                "a cookie declared as '" + DECLARED + "' must be validated when sent under exactly that name");
    }

    @Test
    @DisplayName("A cookie whose name differs only in case is not the declared cookie and is not validated")
    void caseDifferingCookieIsNotTheDeclaredCookie() {
        Handler<RoutingContext> gate = gateForPatternedCookie();

        RoutingContext ctx = requestWithCookie("Session", "bad");
        gate.handle(ctx);

        assertEquals(
                0,
                failureCount(ctx),
                "cookie names match exactly, so 'Session' is not the declared 'session' and must not be "
                        + "validated against its schema");
        verify(ctx).next();
    }

    @Test
    @DisplayName("A conforming cookie under the declared name is accepted")
    void conformingExactNameCookieIsAccepted() {
        Handler<RoutingContext> gate = gateForPatternedCookie();

        RoutingContext ctx = requestWithCookie(DECLARED, "ok");
        gate.handle(ctx);

        assertEquals(0, failureCount(ctx), "a conforming value must pass the gate");
        verify(ctx).next();
    }

    private static Handler<RoutingContext> gateForPatternedCookie() {
        JsonObject schema = new JsonObject().put("type", "string").put("pattern", "^ok$");
        JaxRsOperationDescriptor operation = StubDescriptors.builder()
                .operationId(OPERATION_ID)
                .httpMethod("GET")
                .routeTemplate("/cookie-name-matching")
                .parameters(List.of(
                        new ParamDescriptor(DECLARED, ParamLocation.COOKIE, String.class, null, null, null, List.of())))
                .fileParts(List.of())
                .build();
        OperationSchemas schemas = OperationSchemas.builder()
                .parameterSchema(ParamLocation.COOKIE, DECLARED, schema)
                .build();
        return new WebValidationStrategy(JaxRsConfig.builder().build(), ConversionContexts.defaultResolver(), Set.of())
                .gateFor(operation, schemas)
                .orElseThrow();
    }

    private static RoutingContext requestWithCookie(String name, String value) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        Map<String, Object> data = new HashMap<>();
        when(ctx.request()).thenReturn(request);
        when(ctx.pathParams()).thenReturn(Map.of());
        when(ctx.queryParams()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(ctx.fileUploads()).thenReturn(List.of());
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.cookies()).thenReturn(Set.of(Cookie.cookie(name, value)));
        when(request.formAttributes()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(ctx.get(anyString())).thenAnswer(invocation -> data.get(invocation.getArgument(0, String.class)));
        when(ctx.put(anyString(), any())).thenAnswer(invocation -> {
            data.put(invocation.getArgument(0, String.class), invocation.getArgument(1));
            return ctx;
        });
        return ctx;
    }

    private static int failureCount(RoutingContext ctx) {
        int count = 0;
        for (Invocation invocation : mockingDetails(ctx).getInvocations()) {
            if ("fail".equals(invocation.getMethod().getName())) {
                count++;
            }
        }
        return count;
    }

    private static Throwable singleFailure(RoutingContext ctx) {
        Throwable failure = null;
        int count = 0;
        for (Invocation invocation : mockingDetails(ctx).getInvocations()) {
            if (!"fail".equals(invocation.getMethod().getName())) {
                continue;
            }
            count++;
            for (Object argument : invocation.getArguments()) {
                if (argument instanceof Throwable thrown) {
                    failure = thrown;
                }
            }
        }
        assertEquals(1, count, "the gate must fail the request exactly once");
        return failure;
    }
}
