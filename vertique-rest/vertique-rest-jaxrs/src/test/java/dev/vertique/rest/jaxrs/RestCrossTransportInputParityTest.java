// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.input.processing.testkit.CrossTransportFixtureLevel1;
import dev.vertique.input.processing.testkit.CrossTransportInputCorpus;
import dev.vertique.input.processing.testkit.CrossTransportUppercaseSanitizer;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.convert.ConversionContexts;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R03 TP-001 — {@code shouldMatchRestParityOnTheSharedCorpus}, REST half.
 *
 * <p>Runs {@link CrossTransportInputCorpus#rawInput()} through the real REST input-processing
 * boundary — {@link ParameterExtractor#extractArguments}, the exact production method the reflective
 * dispatch path calls per request (FR-024), constructed the same way {@code ParameterExtractorTest}
 * does — for a body parameter typed as the <strong>published</strong> {@link
 * CrossTransportFixtureLevel1} corpus record, not a locally re-declared lookalike. Asserts the
 * resulting materialized body equals the corpus's published {@code expectedProcessedInput()}.
 *
 * <p>The MCP half of this same claim is {@code dev.vertique.mcp.server.McpCrossTransportInputParityTest}
 * in {@code vertique-mcp-server}. Neither test references the other directly — each asserts
 * independently against the one published {@link CrossTransportInputCorpus#expectedProcessedInput()}
 * ground truth. Both modules depend on the exact same {@code dev.vertique:vertique-input-processing:test-jar}
 * coordinate and reference the exact same corpus class, so the two assertions can only both pass if
 * REST's and MCP's real input-processing boundaries produce the same result for the same fixtures.
 */
class RestCrossTransportInputParityTest {

    /** Resource fixture whose single BODY parameter is the published corpus's root record type. */
    static final class ParityResource {
        @SuppressWarnings("unused")
        public CrossTransportFixtureLevel1 echo(CrossTransportFixtureLevel1 root) {
            return root;
        }
    }

    @Test
    @DisplayName("shouldMatchRestParityOnTheSharedCorpus")
    void shouldMatchRestParityOnTheSharedCorpus() throws Exception {
        // Given: a real InputObjectProcessor resolving only the corpus's own declared sanitizer — a
        // wrong-class resolution would fail loudly rather than silently pass — precomputed for the
        // corpus's root type exactly as JaxRsRouteRegistrar does at startup, and a real ParameterExtractor
        // built the same way ParameterExtractorTest builds one for the reflective dispatch path.
        InputObjectProcessor processor = InputObjectProcessor.createDefault(
                canonicalizerType -> {
                    throw new IllegalArgumentException("unresolvable canonicalizer " + canonicalizerType);
                },
                sanitizerType -> {
                    if (sanitizerType == CrossTransportUppercaseSanitizer.class) {
                        return new CrossTransportUppercaseSanitizer();
                    }
                    throw new IllegalArgumentException("unresolvable sanitizer " + sanitizerType);
                });
        processor.precomputeFieldNameResolution(CrossTransportFixtureLevel1.class, InputFieldNameResolver.IDENTITY);

        Method echo = ParityResource.class.getMethod("echo", CrossTransportFixtureLevel1.class);
        ResourceMethodMeta meta = new ResourceMethodMeta(
                new ParityResource(),
                echo,
                "echo",
                "POST",
                "/parity",
                List.of(new ResourceMethodMeta.ParamMeta(
                        "root", ResourceMethodMeta.ParamSource.BODY, CrossTransportFixtureLevel1.class)),
                CrossTransportFixtureLevel1.class,
                false,
                false,
                new SecurityPolicy.None(),
                new ResourceMethodMeta.MediaTypes(List.of(), List.of()),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of());

        List<RequestBodyDecoder> decoders = List.of(new JsonRequestBodyDecoder());
        ParameterExtractor extractor = new ParameterExtractor(
                meta,
                decoders,
                new RestContextResolution(Set.of()),
                processor,
                ConversionContexts.defaultResolver(),
                InputFieldNameResolver.IDENTITY);

        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(request.getHeader("Content-Type")).thenReturn("application/json");

        BoundRequest boundRequest =
                stubBoundRequest(RequestValue.of(new JsonObject(CrossTransportInputCorpus.rawInput())));

        // When: the real production extraction path materializes the BODY parameter.
        Object[] args = extractor.extractArguments(ctx, boundRequest);

        // Then (DECISIVE): the materialized body, re-normalized to a plain Map tree, equals the corpus's
        // published expected result — not a value this test restates itself.
        assertEquals(1, args.length, "extractArguments must materialize exactly the one BODY parameter");
        CrossTransportFixtureLevel1 materialized = (CrossTransportFixtureLevel1) args[0];
        assertEquals(
                CrossTransportInputCorpus.expectedProcessedInput(),
                asMap(materialized),
                "DECISIVE: the real REST extraction path's materialized body matches the published corpus");

        // And: the sanitizer transform is non-identity, so this assertion could not pass by the pipeline
        // silently doing nothing.
        assertNotEquals(
                CrossTransportInputCorpus.rawInput(),
                asMap(materialized),
                "the sanitizer transform is non-identity; a no-op pipeline could not pass the assertion above");
    }

    /** Re-normalizes a materialized {@link CrossTransportFixtureLevel1} to a plain comparable map tree. */
    private static Map<String, Object> asMap(CrossTransportFixtureLevel1 value) {
        return Map.of(
                "scalar", value.scalar(),
                "items", value.items(),
                "entries", value.entries(),
                "nested",
                        Map.of(
                                "scalar", value.nested().scalar(),
                                "items", value.nested().items(),
                                "entries", value.nested().entries()));
    }

    /** Minimal {@link BoundRequest} stub exposing the supplied body and empty parameter maps. */
    private static BoundRequest stubBoundRequest(RequestValue body) {
        return new BoundRequest() {
            @Override
            public Map<String, RequestValue> pathParameters() {
                return Map.of();
            }

            @Override
            public Map<String, RequestValue> query() {
                return Map.of();
            }

            @Override
            public Map<String, RequestValue> headers() {
                return Map.of();
            }

            @Override
            public Map<String, RequestValue> cookies() {
                return Map.of();
            }

            @Override
            public RequestValue body() {
                return body;
            }

            @Override
            public HttpServerRequest raw() {
                return null;
            }
        };
    }
}
