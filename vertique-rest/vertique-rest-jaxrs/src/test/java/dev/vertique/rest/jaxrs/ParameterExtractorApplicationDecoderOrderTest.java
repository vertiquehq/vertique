// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.json.JacksonFieldNameResolver;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.jaxrs.convert.ConversionContexts;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * With the input-processing engine bound, a JSON or form body bound by the framework's own
 * decoders is bound inline so a declared chain can run. An application decoder that the priority
 * order places ahead of those framework decoders is the decoder the chain picks, so it decodes the
 * body instead; one placed behind them stays a fallback.
 */
class ParameterExtractorApplicationDecoderOrderTest {

    /** Sanitizer whose effect is unmistakable in an assertion. */
    public static final class UppercasingSanitizer implements Sanitizer {
        @Override
        public String sanitize(String value, InputValueContext context) {
            return value == null ? null : value.toUpperCase(Locale.ROOT);
        }
    }

    /** Body whose field is sanitized when the framework binds it. */
    public record Person(
            @Sanitize(UppercasingSanitizer.class) String name) {}

    /** Body that declares no policy anywhere. */
    public record Plain(String name) {}

    /** Resource fixture whose methods take the bodies. */
    static final class Resource {
        @SuppressWarnings("unused")
        public String create(Person body) {
            return body.name();
        }

        @SuppressWarnings("unused")
        public String createPlain(Plain body) {
            return body.name();
        }
    }

    /** An application decoder for {@link Person} bodies of any content type. */
    private static final class ApplicationDecoder implements RequestBodyDecoder {
        private final int priority;
        private final AtomicInteger decoded = new AtomicInteger();
        private final Object result;
        private final Class<?> claimed;

        ApplicationDecoder(int priority, Class<?> claimed) {
            this.priority = priority;
            this.claimed = claimed;
            this.result = claimed == Plain.class ? new Plain("from-application-decoder") : new Person("from-decoder");
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public boolean canDecode(Class<?> targetType, String contentType) {
            return targetType == claimed;
        }

        @Override
        public Object decode(RoutingContext ctx, RequestValue body, Class<?> targetType) {
            decoded.incrementAndGet();
            return result;
        }
    }

    private static InputObjectProcessor engine() {
        return InputObjectProcessor.createDefault(c -> null, c -> new UppercasingSanitizer());
    }

    private static ParameterExtractor extractor(List<RequestBodyDecoder> decoders) throws Exception {
        return extractor(decoders, Person.class);
    }

    private static ParameterExtractor extractor(List<RequestBodyDecoder> decoders, Class<?> bodyType) throws Exception {
        Method create = Resource.class.getDeclaredMethod(bodyType == Plain.class ? "createPlain" : "create", bodyType);
        ResourceMethodMeta meta = new ResourceMethodMeta(
                new Resource(),
                create,
                "create",
                "POST",
                "/people",
                List.of(new ResourceMethodMeta.ParamMeta("body", ResourceMethodMeta.ParamSource.BODY, bodyType)),
                String.class,
                false,
                false,
                new dev.vertique.rest.core.security.SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of());
        return new ParameterExtractor(
                meta,
                decoders,
                new RestContextResolution(Set.of()),
                engine(),
                ConversionContexts.defaultResolver(),
                JacksonFieldNameResolver.forRoute(null));
    }

    private static RoutingContext context(String contentType) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(request.getHeader("Content-Type")).thenReturn(contentType);
        when(request.formAttributes())
                .thenReturn(MultiMap.caseInsensitiveMultiMap().add("name", "ada"));
        when(ctx.<ObjectMapper>get(BoundRequest.KEY_RESOLVED_BODY_MAPPER)).thenReturn(null);
        return ctx;
    }

    private static Object decode(ParameterExtractor extractor, String contentType) {
        return decode(extractor, contentType, Person.class);
    }

    private static Object decode(ParameterExtractor extractor, String contentType, Class<?> bodyType) {
        return extractor.deserializeBody(
                RequestValue.of(new JsonObject().put("name", "ada")),
                bodyType,
                null,
                context(contentType),
                EffectiveInputPolicies.NONE);
    }

    @Test
    @DisplayName("an application decoder ahead of the framework decoders decodes a JSON body")
    void applicationDecoderDecodesJson() throws Exception {
        ApplicationDecoder application = new ApplicationDecoder(0, Plain.class);

        Object result = decode(
                extractor(List.of(application, new JsonRequestBodyDecoder()), Plain.class),
                "application/json",
                Plain.class);

        assertSame(application.result, result);
        assertEquals(1, application.decoded.get());
    }

    @Test
    @DisplayName("an application decoder ahead of the framework decoders decodes a form body")
    void applicationDecoderDecodesForm() throws Exception {
        ApplicationDecoder application = new ApplicationDecoder(0, Plain.class);

        Object result = decode(
                extractor(List.of(application, new FormUrlencodedRequestBodyDecoder()), Plain.class),
                "application/x-www-form-urlencoded",
                Plain.class);

        assertSame(application.result, result);
        assertEquals(1, application.decoded.get());
    }

    @Test
    @DisplayName("an application decoder behind the framework decoders stays a fallback")
    void applicationDecoderBehindFrameworkDecodersIsNotAsked() throws Exception {
        ApplicationDecoder fallback = new ApplicationDecoder(2000, Person.class);

        Object result = decode(extractor(List.of(new JsonRequestBodyDecoder(), fallback)), "application/json");

        Person person = assertInstanceOf(Person.class, result);
        assertEquals("ADA", person.name(), "the framework binds the body and runs its declared chain");
        assertEquals(0, fallback.decoded.get());
    }

    @Test
    @DisplayName("an application decoder that does not claim the target type leaves the body to the framework")
    void applicationDecoderThatDoesNotClaimIsNotAsked() throws Exception {
        RequestBodyDecoder other = new RequestBodyDecoder() {
            @Override
            public boolean canDecode(Class<?> targetType, String contentType) {
                return targetType == String.class && "text/csv".equals(contentType);
            }

            @Override
            public Object decode(RoutingContext ctx, RequestValue body, Class<?> targetType) {
                throw new AssertionError("must not be asked");
            }
        };

        Object result = decode(extractor(List.of(other, new JsonRequestBodyDecoder())), "application/json");

        assertEquals("ADA", assertInstanceOf(Person.class, result).name());
    }

    @Test
    @DisplayName("without an application decoder the framework binds the body and runs its declared chain")
    void frameworkBindsWithoutApplicationDecoder() throws Exception {
        Object result = decode(extractor(List.of(new JsonRequestBodyDecoder())), "application/json");

        assertEquals("ADA", assertInstanceOf(Person.class, result).name());
    }

    @Test
    @DisplayName("an application decoder that returns null yields null instead of falling back to the framework")
    void applicationDecoderNullResultIsReturned() throws Exception {
        RequestBodyDecoder nullDecoder = new RequestBodyDecoder() {
            @Override
            public boolean canDecode(Class<?> targetType, String contentType) {
                return targetType == Plain.class;
            }

            @Override
            public Object decode(RoutingContext ctx, RequestValue body, Class<?> targetType) {
                return null;
            }
        };

        assertNull(decode(
                extractor(List.of(nullDecoder, new JsonRequestBodyDecoder()), Plain.class),
                "application/json",
                Plain.class));
    }

    @Test
    @DisplayName("an application decoder claiming a String body decodes it ahead of the framework text decoder")
    void applicationDecoderDecodesStringBody() throws Exception {
        RequestBodyDecoder stringDecoder = new RequestBodyDecoder() {
            @Override
            public boolean canDecode(Class<?> targetType, String contentType) {
                return targetType == String.class;
            }

            @Override
            public Object decode(RoutingContext ctx, RequestValue body, Class<?> targetType) {
                return "decoded-by-application";
            }
        };

        Object result = extractor(List.of(stringDecoder, new TextRequestBodyDecoder()))
                .deserializeBody(
                        RequestValue.of("raw"), String.class, null, context("text/plain"), EffectiveInputPolicies.NONE);

        assertEquals("decoded-by-application", result);
    }

    @Test
    @DisplayName("an application decoder claiming a body whose type declares a chain is refused at request time")
    void claimedBodyWithDeclaredChainIsRefused() throws Exception {
        ApplicationDecoder application = new ApplicationDecoder(0, Person.class);

        ParameterExtractor extractor = extractor(List.of(application, new JsonRequestBodyDecoder()));

        for (String contentType : List.of("application/json", "application/vnd.api+json", "Application/JSON; v=2")) {
            ConfigurationException failure =
                    assertThrows(ConfigurationException.class, () -> decode(extractor, contentType));
            assertTrue(failure.getMessage().contains(ApplicationDecoder.class.getName()), failure.getMessage());
            assertFalse(failure.getMessage().contains(contentType), "the message carries no request value");
        }
        assertEquals(0, application.decoded.get(), "the decoder must not run");
    }

    @Test
    @DisplayName("a claimed body with a route-level chain and no declared type policy is refused too")
    void claimedBodyWithRouteLevelChainIsRefused() throws Exception {
        ApplicationDecoder application = new ApplicationDecoder(0, Plain.class);
        ParameterExtractor extractor = extractor(List.of(application, new JsonRequestBodyDecoder()), Plain.class);
        EffectiveInputPolicies routeChain = new EffectiveInputPolicies(List.of(), List.of(UppercasingSanitizer.class));

        assertThrows(
                ConfigurationException.class,
                () -> extractor.deserializeBody(
                        RequestValue.of(new JsonObject().put("name", "ada")),
                        Plain.class,
                        null,
                        context("application/json"),
                        routeChain));
        assertEquals(0, application.decoded.get(), "the decoder must not run");
    }

    @Test
    @DisplayName("a decoder whose canDecode throws is skipped on a route with no chain and refuses one with a chain")
    void throwingCanDecodeIsContained() throws Exception {
        RequestBodyDecoder throwing = new RequestBodyDecoder() {
            @Override
            public boolean canDecode(Class<?> targetType, String contentType) {
                throw new IllegalStateException("cannot tell");
            }

            @Override
            public Object decode(RoutingContext ctx, RequestValue body, Class<?> targetType) {
                throw new AssertionError("must not be asked");
            }
        };

        Object plain = decode(
                extractor(List.of(throwing, new JsonRequestBodyDecoder()), Plain.class),
                "application/json",
                Plain.class);
        assertEquals("ada", assertInstanceOf(Plain.class, plain).name(), "the framework binds the body");

        assertThrows(
                ConfigurationException.class,
                () -> decode(extractor(List.of(throwing, new JsonRequestBodyDecoder())), "application/json"));
    }
}
