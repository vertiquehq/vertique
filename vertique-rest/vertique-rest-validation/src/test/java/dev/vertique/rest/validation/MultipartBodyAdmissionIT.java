// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestMounts;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves {@code http.maxMultipartBodySizeBytes} is enforced by the mount {@code BodyHandler} as a
 * pre-auth / pre-resource admission ceiling: an over-limit multipart request returns 413 before the
 * resource method runs, even when {@code http.maxBodySize} would still admit the bytes.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class MultipartBodyAdmissionIT {

    private static final long ASYNC_TIMEOUT_SECONDS = 10;

    /** Global body limit large enough that only the multipart admission ceiling can reject. */
    private static final long LARGE_MAX_BODY_SIZE = 10_000_000L;

    /** Dedicated multipart admission ceiling exercised by the over-/under-limit fixtures. */
    private static final long MULTIPART_ADMISSION_BYTES = 4_096L;

    private static Vertx vertx;
    private static WebClient client;

    private HttpServer server;
    private java.nio.file.Path uploadsDirectory;
    private AtomicBoolean resourceInvoked;
    private AtomicReference<Boolean> interceptorSawRequest;

    @BeforeAll
    static void setUpClient(Vertx injectedVertx) {
        vertx = injectedVertx;
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
    }

    @AfterAll
    static void tearDownClient(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        ctx.completeNow();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            server = null;
        }
        RestTestMounts.deleteRecursively(uploadsDirectory);
    }

    @Test
    @DisplayName("Multipart over maxMultipartBodySizeBytes is 413 before the resource runs")
    void overLimitMultipartRejectedBeforeResource() throws Exception {
        startServer("overLimitMultipart");

        byte[] payload = new byte[(int) MULTIPART_ADMISSION_BYTES + 1];
        Arrays.fill(payload, (byte) 'x');
        Buffer body = MultipartBodies.singleFile("upload", "big.bin", "application/octet-stream", payload);

        assertTrue(body.length() > MULTIPART_ADMISSION_BYTES, "fixture must exceed the multipart ceiling");
        assertTrue(body.length() < LARGE_MAX_BODY_SIZE, "fixture must still fit under maxBodySize");

        HttpResult result = postMultipart(body);

        assertEquals(413, result.statusCode(), "over-limit multipart must fail closed with 413");
        assertFalse(resourceInvoked.get(), "BodyHandler must reject before the resource method");
        assertNull(interceptorSawRequest.get(), "request interceptors must not run after a body-limit failure");
    }

    @Test
    @DisplayName("Multipart at or under maxMultipartBodySizeBytes reaches the resource")
    void underLimitMultipartAccepted() throws Exception {
        startServer("underLimitMultipart");

        // Keep the whole encoded multipart under the admission ceiling: headers + framing cost bytes.
        byte[] payload = "small-enough".getBytes(StandardCharsets.US_ASCII);
        Buffer body = MultipartBodies.singleFile("upload", "small.bin", "application/octet-stream", payload);
        assertTrue(body.length() <= MULTIPART_ADMISSION_BYTES, "under-limit fixture must fit the ceiling");

        HttpResult result = postMultipart(body);

        assertEquals(200, result.statusCode(), "under-limit multipart must be accepted");
        assertEquals("ok", result.body());
        assertTrue(resourceInvoked.get(), "accepted multipart must reach the resource method");
        assertEquals(Boolean.TRUE, interceptorSawRequest.get(), "accepted multipart must pass BodyHandler");
    }

    @Test
    @DisplayName("Non-multipart bodies still use maxBodySize, not the multipart admission ceiling")
    void textBodyUsesGlobalMaxBodySize() throws Exception {
        startServer("textUsesGlobalLimit");

        // Larger than the multipart ceiling but well under maxBodySize — must not be rejected by the
        // multipart admission path.
        String text = "y".repeat((int) MULTIPART_ADMISSION_BYTES + 64);
        assertTrue(text.length() > MULTIPART_ADMISSION_BYTES);
        assertTrue(text.length() < LARGE_MAX_BODY_SIZE);

        HttpResult result = client.post(server.actualPort(), "127.0.0.1", "/admission/text")
                .putHeader("Content-Type", "text/plain")
                .sendBuffer(Buffer.buffer(text, StandardCharsets.UTF_8.name()))
                .map(response -> new HttpResult(response.statusCode(), String.valueOf(response.bodyAsString())))
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(200, result.statusCode(), "text over the multipart ceiling must still be admitted");
        assertEquals("text-ok", result.body());
        assertTrue(resourceInvoked.get());
    }

    private void startServer(String testName) {
        uploadsDirectory = java.nio.file.Path.of(
                "target", "file-uploads", "MultipartBodyAdmissionIT", testName + "-" + UUID.randomUUID());
        resourceInvoked = new AtomicBoolean(false);
        interceptorSawRequest = new AtomicReference<>();

        RequestInterceptor capture = new RequestInterceptor() {
            @Override
            public void onRequest(RoutingContext rc) {
                interceptorSawRequest.set(Boolean.TRUE);
            }
        };
        RestTestContributions contributions =
                RestTestContributions.builder().addRequestInterceptor(capture).build();
        JsonObject config = new JsonObject()
                .put(
                        "http",
                        new JsonObject()
                                .put("host", "127.0.0.1")
                                .put("uploadsDirectory", uploadsDirectory.toString())
                                .put("maxBodySize", LARGE_MAX_BODY_SIZE)
                                .put("maxMultipartBodySizeBytes", MULTIPART_ADMISSION_BYTES));

        server = RestTestMounts.startServerBlocking(
                vertx,
                MountFixtures.mount(vertx, config, contributions),
                Set.of(new AdmissionResource(resourceInvoked)),
                Duration.ofSeconds(ASYNC_TIMEOUT_SECONDS));
    }

    private HttpResult postMultipart(Buffer body) throws Exception {
        return client.post(server.actualPort(), "127.0.0.1", "/admission/upload")
                .putHeader("Content-Type", MultipartBodies.contentType())
                .sendBuffer(body)
                .map(response -> new HttpResult(response.statusCode(), String.valueOf(response.bodyAsString())))
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Path("/admission")
    public static class AdmissionResource {

        private final AtomicBoolean invoked;

        AdmissionResource(AtomicBoolean invoked) {
            this.invoked = invoked;
        }

        @POST
        @Path("/upload")
        @Consumes(MediaType.MULTIPART_FORM_DATA)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "admissionUpload")
        public String upload(List<FileUpload> files) {
            invoked.set(true);
            return "ok";
        }

        @POST
        @Path("/text")
        @Consumes(MediaType.TEXT_PLAIN)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "admissionText")
        public String text(String body) {
            invoked.set(true);
            return "text-ok";
        }
    }

    private record HttpResult(int statusCode, String body) {}
}
