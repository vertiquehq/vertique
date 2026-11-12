// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest.label;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.micrometer.rest.RestServerRequestMetricsListener;
import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration proof of the {@code rest.application} tag over the production JAX-RS router, the
 * production completion emitter and the production {@code MicrometerRestModule} wiring.
 *
 * <p>Two compositions ({@link LabelComponents}): one with applications {@code public} and
 * {@code partner}, which mount the same {@link SharedItemsResource} under the same operationId and
 * mount-relative route, and {@code mgmt}, whose {@link ManagementResource} rejects an anonymous
 * request with 401 and a non-admin with 403; one with no application, whose default mount serves
 * {@link LegacyResource}. Tag keys are literals so the expectations never copy production state.
 *
 * <p>The adapter's listener and the test's {@link CompletionLog} receive the same event in an
 * unspecified order, so the test waits for the series itself, polling the registry with a bound,
 * instead of trusting a latch released by the other listener.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class ApplicationLabelIT {

    private static final String METER = RestServerRequestMetricsListener.METER_NAME;
    private static final String APPLICATION = "rest.application";
    private static final String ROUTE = "route";
    private static final String OPERATION = "operation";
    private static final String STATUS = "status";
    private static final String METHOD = "method";

    private static final long AWAIT_SECONDS = 15;
    private static final long SERIES_WAIT_MILLIS = 5_000;

    private Vertx vertx;
    private WebClient client;
    private String deploymentId;
    private int port;

    @BeforeEach
    void setUp(Vertx injectedVertx) {
        vertx = injectedVertx;
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
    }

    /**
     * Closes the client, then undeploys the verticle, which closes the server. The injected
     * {@link Vertx} is owned and closed by the extension.
     */
    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (deploymentId != null) {
            await(vertx.undeploy(deploymentId));
        }
    }

    @Test
    @DisplayName("one resource mounted by two applications records one series per application")
    void sharedResourceSplitsByApplication() throws InterruptedException {
        LabelComponents.ApplicationsComponent component =
                DaggerLabelComponents_ApplicationsComponent.factory().create(config());
        deploy(component::httpVerticle);
        MeterRegistry registry = component.meterRegistry();

        int publicStatus = send("/api/public/items", null, null).statusCode();
        int partnerStatus = send("/api/partner/items", null, null).statusCode();
        Timer publicTimer = awaitTimer(registry, "public", "listItems");
        Timer partnerTimer = awaitTimer(registry, "partner", "listItems");

        assertAll(
                () -> assertEquals(200, publicStatus, "public request status"),
                () -> assertEquals(200, partnerStatus, "partner request status"),
                () -> assertNotNull(publicTimer, "series tagged " + APPLICATION + "=public; " + series(registry)),
                () -> assertNotNull(partnerTimer, "series tagged " + APPLICATION + "=partner; " + series(registry)),
                () -> assertNotSame(publicTimer, partnerTimer, "the applications must not share a series"),
                () -> assertEquals(1, publicTimer.count(), "public count; " + series(registry)),
                () -> assertEquals(1, partnerTimer.count(), "partner count; " + series(registry)),
                () -> assertEquals(2, registry.find(METER).timers().size(), "two series; " + series(registry)),
                () -> assertEquals(
                        withoutApplication(publicTimer),
                        withoutApplication(partnerTimer),
                        "the series differ only in " + APPLICATION + "; " + series(registry)),
                () -> assertEquals("/items", tag(publicTimer, ROUTE), "route is the mount-relative template"),
                () -> assertEquals("GET", tag(publicTimer, METHOD), "method"),
                () -> assertEquals("200", tag(publicTimer, STATUS), "status"));
    }

    @Test
    @DisplayName("a request rejected on an application's operation keeps the application on its event and series")
    void rejectedApplicationRequestsCarryTheName() throws InterruptedException {
        LabelComponents.ApplicationsComponent component =
                DaggerLabelComponents_ApplicationsComponent.factory().create(config());
        deploy(component::httpVerticle);
        MeterRegistry registry = component.meterRegistry();
        CompletionLog log = component.completionLog();

        int anonymousStatus = send("/api/mgmt/status", null, null).statusCode();
        Timer unauthorized = awaitTimer(registry, "mgmt", "status", "401");
        List<RestRequestCompletedEvent> anonymousEvents = log.events();

        int nonAdminStatus = send("/api/mgmt/status", "user", "").statusCode();
        Timer forbidden = awaitTimer(registry, "mgmt", "status", "403");

        int adminStatus = send("/api/mgmt/status", "admin", "admin").statusCode();
        Timer ok = awaitTimer(registry, "mgmt", "status", "200");

        assertAll(
                () -> assertEquals(401, anonymousStatus, "anonymous request status"),
                () -> assertEquals(1, anonymousEvents.size(), "one completion event for the 401; " + anonymousEvents),
                () -> assertEquals(
                        "mgmt",
                        anonymousEvents.isEmpty()
                                ? null
                                : anonymousEvents.get(0).operation().applicationName(),
                        "the 401 event's operation carries the application name"),
                () -> assertEquals(
                        "status",
                        anonymousEvents.isEmpty()
                                ? null
                                : anonymousEvents.get(0).operation().operationId(),
                        "the 401 event's operation carries the operationId"),
                () -> assertEquals(
                        401,
                        anonymousEvents.isEmpty() ? -1 : anonymousEvents.get(0).statusCode(),
                        "the 401 event's status"),
                () -> assertNotNull(unauthorized, "401 series tagged " + APPLICATION + "=mgmt; " + series(registry)),
                () -> assertEquals(403, nonAdminStatus, "non-admin request status"),
                () -> assertNotNull(forbidden, "403 series tagged " + APPLICATION + "=mgmt; " + series(registry)),
                () -> assertEquals(200, adminStatus, "admin request status"),
                () -> assertNotNull(ok, "200 series tagged " + APPLICATION + "=mgmt; " + series(registry)),
                () -> assertTrue(
                        registry.find(METER).timers().stream()
                                .allMatch(timer -> "mgmt".equals(tag(timer, APPLICATION))),
                        "every series of this composition belongs to mgmt; " + series(registry)));
    }

    @Test
    @DisplayName("an operation of a composition without applications is tagged none")
    void legacyMountIsTaggedNone() throws InterruptedException {
        LabelComponents.LegacyComponent component =
                DaggerLabelComponents_LegacyComponent.factory().create(config());
        deploy(component::httpVerticle);
        MeterRegistry registry = component.meterRegistry();

        int status = send("/legacy", null, null).statusCode();
        Timer none = awaitTimer(registry, "none", "legacy");
        List<RestRequestCompletedEvent> events = component.completionLog().events();

        assertAll(
                () -> assertEquals(200, status, "legacy request status"),
                () -> assertNotNull(none, "series tagged " + APPLICATION + "=none; " + series(registry)),
                () -> assertEquals(1, none.count(), "count of the none series"),
                () -> assertEquals(1, registry.find(METER).timers().size(), "one series; " + series(registry)),
                () -> assertEquals("/legacy", tag(none, ROUTE), "route"),
                () -> assertEquals(1, events.size(), "one completion event; " + events),
                () -> assertEquals(
                        null,
                        events.isEmpty() ? "absent" : events.get(0).operation().applicationName(),
                        "the legacy event's operation has no application name"));
    }

    // --- Harness ---

    private static JsonObject config() {
        return new JsonObject()
                .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                .put("jaxrs", new JsonObject().put("validationStrategy", "none"));
    }

    private void deploy(Supplier<Verticle> verticles) {
        deploymentId = await(vertx.deployVerticle(verticles, new DeploymentOptions()));
        port = (Integer) vertx.sharedData().getLocalMap("vertique").get("http.port");
    }

    private HttpResponse<Buffer> send(String path, String user, String roles) {
        HttpRequest<Buffer> request = client.request(HttpMethod.GET, port, "127.0.0.1", path);
        if (user != null) {
            request.putHeader(StubScheme.USER_HEADER, user);
        }
        if (roles != null) {
            request.putHeader(StubScheme.ROLES_HEADER, roles);
        }
        return await(request.send());
    }

    private static Timer awaitTimer(MeterRegistry registry, String application, String operation)
            throws InterruptedException {
        return awaitTimer(registry, application, operation, null);
    }

    /**
     * Polls the registry until a timer tagged with the application, operation and (when given) status
     * exists, or the bound elapses.
     *
     * @return the timer, or {@code null} when none appeared within the bound
     */
    private static Timer awaitTimer(MeterRegistry registry, String application, String operation, String status)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SERIES_WAIT_MILLIS);
        while (true) {
            var search = registry.find(METER).tag(APPLICATION, application).tag(OPERATION, operation);
            if (status != null) {
                search = search.tag(STATUS, status);
            }
            Timer timer = search.timer();
            if (timer != null || System.nanoTime() >= deadline) {
                return timer;
            }
            Thread.sleep(20);
        }
    }

    private static String tag(Timer timer, String key) {
        return timer == null ? null : timer.getId().getTag(key);
    }

    private static List<Tag> withoutApplication(Timer timer) {
        if (timer == null) {
            return List.of();
        }
        return timer.getId().getTags().stream()
                .filter(tag -> !APPLICATION.equals(tag.getKey()))
                .toList();
    }

    private static String series(MeterRegistry registry) {
        return "series: "
                + registry.find(METER).timers().stream()
                        .map(t -> t.getId().getTags().toString() + " count=" + t.count())
                        .toList();
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new AssertionError("asynchronous step failed: " + e.getMessage(), e);
        }
    }
}
