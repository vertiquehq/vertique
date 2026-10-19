// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.json.schema.OutputUnit;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.openapi.validation.SchemaValidationException;
import io.vertx.openapi.validation.ValidatorErrorType;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves that the {@code openapi-contract} strategy lists at most 100 violations and one truncation entry,
 * and that it stops converting after the 101st concrete violation.
 *
 * <p>The listing is proven end to end: a real Vert.x server runs the gate behind {@code BodyHandler} against
 * a contract whose request body is an array of integers, and the test posts arrays of strings, one violation
 * per element. The conversion bound is proven on the strategy's mapping of an engine result that counts how
 * many of its errors are read.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class OpenApiContractValidationStrategyCapTest {

    private static final String CONTRACT_PATH = "openapi-contract-cap-test.json";

    private static final AtomicReference<RestValidationException> CAPTURED = new AtomicReference<>();

    private static HttpServer server;
    private static WebClient client;
    private static int port;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
        OpenApiContractValidationStrategy strategy = new OpenApiContractValidationStrategy(
                vertx, JaxRsConfig.builder().openapiPath(CONTRACT_PATH).build());
        Handler<RoutingContext> gate = strategy.gateFor(
                        StubDescriptors.builder()
                                .operationId("createNumbers")
                                .httpMethod("POST")
                                .routeTemplate("/numbers")
                                .build(),
                        OperationSchemas.empty())
                .orElseThrow();

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.post("/numbers")
                .handler(gate)
                .handler(rc -> rc.response().setStatusCode(201).end("created"));
        router.route().failureHandler(rc -> {
            Throwable failure = rc.failure();
            if (failure instanceof RestValidationException rve) {
                CAPTURED.set(rve);
            }
            rc.response()
                    .setStatusCode(failure instanceof RestValidationException ? 400 : 500)
                    .end();
        });
        vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .onSuccess(s -> {
                    server = s;
                    port = s.actualPort();
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        Future<Void> closeServer = server != null ? server.close() : Future.succeededFuture();
        closeServer.onComplete(ar -> {
            if (client != null) {
                client.close();
            }
            ctx.completeNow();
        });
    }

    @ParameterizedTest(name = "{0} violations")
    @ValueSource(ints = {99, 100, 101, 250})
    @DisplayName("the strategy lists at most 100 entries and then one truncation entry")
    void listsAtMostOneHundredEntriesAndTheMarker(int violations) throws Exception {
        // Given a body whose schema violations number as given: an array of strings where integers are required
        JsonArray body = new JsonArray();
        IntStream.range(0, violations).forEach(i -> body.add("not-a-number-" + i));

        // When it is posted
        CAPTURED.set(null);
        int status = client.post(port, "127.0.0.1", "/numbers")
                .putHeader("content-type", "application/json")
                .sendBuffer(Buffer.buffer(body.encode()))
                .map(response -> response.statusCode())
                .toCompletionStage()
                .toCompletableFuture()
                .get(20, TimeUnit.SECONDS);

        // Then the request is rejected with the validation failure
        assertEquals(400, status);
        RestValidationException failure = CAPTURED.get();
        assertNotNull(failure, "the gate must have raised a RestValidationException");
        List<ValidationErrorDetail> errors = failure.errors();
        // And 99 and 100 entries carry no marker, 101 and 250 give 101 entries
        boolean cut = violations > 100;
        assertEquals(cut ? 101 : violations, errors.size());
        // And the first 100 (or all) are the first violations in the engine's order
        for (int i = 0; i < Math.min(violations, 100); i++) {
            assertEquals("/" + i, errors.get(i).pointer(), "pointer of entry " + i);
            assertEquals("body", errors.get(i).location());
            assertEquals("type", errors.get(i).type());
        }
        // And only a cut list ends in the marker, which has no member but its detail and type
        if (cut) {
            assertEquals(
                    new ValidationErrorDetail(null, null, "further violations omitted", null, "truncated", null),
                    errors.get(100));
        }
        assertEquals(
                cut ? 1 : 0,
                errors.stream().filter(e -> "truncated".equals(e.type())).count());
    }

    @Test
    @DisplayName("the conversion stops at the 101st concrete violation and does not count structural units")
    void stopsConvertingAtTheOneHundredAndFirstConcreteViolation() {
        // Given an engine result of 20,000 concrete violations, each preceded by a structural wrapper unit,
        // that counts how many of its units are read
        int[] unitsRead = {0};
        List<OutputUnit> units = new AbstractList<>() {
            @Override
            public OutputUnit get(int index) {
                unitsRead[0]++;
                int position = index / 2;
                return index % 2 == 0
                        ? new OutputUnit().setKeywordLocation("#/items").setInstanceLocation("#/" + position)
                        : new OutputUnit()
                                .setKeywordLocation("#/items/type")
                                .setInstanceLocation("#/" + position)
                                .setError("not an integer");
            }

            @Override
            public int size() {
                return 40_000;
            }
        };
        OutputUnit output = new OutputUnit().setErrors(units);
        assertSame(units, output.getErrors(), "the result holds the counting list itself");
        assertEquals(0, unitsRead[0], "nothing is read before mapping");
        SchemaValidationException raw =
                new SchemaValidationException("submitted", ValidatorErrorType.INVALID_VALUE, output, null);

        // When it is mapped
        List<ValidationErrorDetail> errors = OpenApiContractValidationStrategy.mapToRestValidationException(raw)
                .errors();

        // Then the first 100 concrete violations are listed and the marker follows: wrappers were not counted
        List<String> pointers = new ArrayList<>();
        errors.subList(0, 100).forEach(e -> pointers.add(e.pointer()));
        assertEquals(101, errors.size());
        assertEquals(IntStream.range(0, 100).mapToObj(i -> "/" + i).toList(), pointers);
        assertEquals("truncated", errors.get(100).type());
        // And the walk stopped at the 101st concrete violation (the 202nd unit); a loop that tests its bound at the
        // top of the next round may read a unit or two more
        assertTrue(unitsRead[0] > 0, "the probe must observe the conversion");
        assertTrue(unitsRead[0] <= 204, unitsRead[0] + " of 40,000 units were read");
    }
}
