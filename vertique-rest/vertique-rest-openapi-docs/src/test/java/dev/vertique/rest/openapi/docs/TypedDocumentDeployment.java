// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.Observations;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.typed.TypedDocumentModule;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.typed.TypedDocumentObservations;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.typed.TypedSingleDocumentModule;
import dev.vertique.rest.openapi.docs.fixture.support.Cleanup;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments.Outcome;
import io.vertx.core.MultiMap;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One typed document deployment under test: its own {@link Vertx}, its own loopback server on an
 * ephemeral port, and one client that does not follow redirects. Closing it closes the client, then
 * undeploys the server, then closes the {@link Vertx} it owns, whatever failed before, so a test
 * never leaves a socket or an event loop behind and one failing deployment cannot mask the next.
 */
final class TypedDocumentDeployment implements AutoCloseable {

    /** The longest one asynchronous step is awaited. */
    private static final Duration BOUND = Duration.ofSeconds(10);

    private static final String HOST = "127.0.0.1";

    /** The permissive configured default {@code Cache-Control} of every deployment. */
    static final String DEFAULT_CACHE_CONTROL = "public, max-age=3600";

    /**
     * One response.
     *
     * @param status  the status
     * @param headers the headers
     * @param body    the body, empty when there was none
     */
    record Reply(int status, MultiMap headers, Buffer body) {

        String header(String name) {
            return headers.get(name);
        }

        String text() {
            return body.toString();
        }
    }

    private final Cleanup cleanup;
    private final WebClient client;
    private final Outcome outcome;
    private final JWTAuth provider;
    private final TypedDocumentObservations observations;
    private final Observations mountObservations;

    private TypedDocumentDeployment(
            Cleanup cleanup,
            WebClient client,
            Outcome outcome,
            JWTAuth provider,
            TypedDocumentObservations observations,
            Observations mountObservations) {
        this.cleanup = cleanup;
        this.client = client;
        this.outcome = outcome;
        this.provider = provider;
        this.observations = observations;
        this.mountObservations = mountObservations;
    }

    /**
     * Returns the configuration of the all-policies deployment: loopback, no request validation, the
     * permissive default {@code Cache-Control}, and the JWT scheme {@code bearerAuth}.
     *
     * @return a fresh configuration
     */
    static JsonObject configuration() {
        JsonObject config = DocsConfigs.loopback();
        config.getJsonObject("jaxrs")
                .put("defaultHeaders", new JsonObject().put("cacheControl", DEFAULT_CACHE_CONTROL));
        config.put("jwt", new JsonObject().put("schemeName", "bearerAuth"));
        return config;
    }

    /**
     * Deploys every application of the typed document fixture.
     *
     * @return the running deployment
     * @throws Exception if the deployment fails, after every resource opened so far was closed
     */
    static TypedDocumentDeployment startAll() throws Exception {
        return startAll(configuration());
    }

    /**
     * Deploys every application of the typed document fixture with a configuration.
     *
     * @param config the application configuration
     * @return the running deployment
     * @throws Exception if the deployment fails, after every resource opened so far was closed
     */
    static TypedDocumentDeployment startAll(JsonObject config) throws Exception {
        Vertx vertx = Vertx.vertx();
        Cleanup cleanup = new Cleanup().await("close the Vert.x instance", vertx::close, BOUND);
        try {
            TypedDocumentTestComponents.AllPolicies component =
                    DaggerTypedDocumentTestComponents_AllPolicies.factory().create(vertx, config);
            return open(
                    vertx, cleanup, component::httpVerticle, component.observations(), component.mountObservations());
        } catch (Throwable failure) {
            closeAfter(cleanup, failure);
            throw failure;
        }
    }

    /**
     * Deploys one application declared by the given interface, with no action registry or authorizer.
     *
     * @param config      the application configuration
     * @param declaration the declaring interface of the application {@code management}
     * @return the running deployment
     * @throws Exception if the deployment fails, after every resource opened so far was closed
     */
    static TypedDocumentDeployment startSingle(JsonObject config, Class<?> declaration) throws Exception {
        Vertx vertx = Vertx.vertx();
        Cleanup cleanup = new Cleanup().await("close the Vert.x instance", vertx::close, BOUND);
        try {
            TypedDocumentTestComponents.SingleDocument component =
                    DaggerTypedDocumentTestComponents_SingleDocument.factory()
                            .create(vertx, config, new TypedSingleDocumentModule.Declaration(declaration));
            return open(vertx, cleanup, component::httpVerticle, null, null);
        } catch (Throwable failure) {
            closeAfter(cleanup, failure);
            throw failure;
        }
    }

    /**
     * Attempts to deploy one application declared by the given interface and returns the outcome
     * without throwing for a refused startup.
     *
     * @param config      the application configuration
     * @param declaration the declaring interface of the application {@code management}
     * @return the refusal when startup failed, or {@code null} when it started and was closed again
     * @throws Exception if the cleanup fails
     */
    static Throwable startupFailure(JsonObject config, Class<?> declaration) throws Exception {
        Vertx vertx = Vertx.vertx();
        Cleanup cleanup = new Cleanup().await("close the Vert.x instance", vertx::close, BOUND);
        try {
            TypedDocumentTestComponents.SingleDocument component =
                    DaggerTypedDocumentTestComponents_SingleDocument.factory()
                            .create(vertx, config, new TypedSingleDocumentModule.Declaration(declaration));
            Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
            if (outcome.deploymentId() != null) {
                cleanup.await("undeploy", () -> vertx.undeploy(outcome.deploymentId()), BOUND);
            }
            return outcome.failure();
        } finally {
            cleanup.close();
        }
    }

    private static TypedDocumentDeployment open(
            Vertx vertx,
            Cleanup cleanup,
            Supplier<Verticle> verticle,
            TypedDocumentObservations observations,
            Observations mountObservations)
            throws Exception {
        Outcome outcome = StartupDeployments.deploy(vertx, verticle);
        if (outcome.failure() != null) {
            throw new AssertionError("the deployment failed to start: " + outcome.failure(), outcome.failure());
        }
        if (outcome.port() == null) {
            throw new AssertionError("the deployment published no port");
        }
        WebClient client = WebClient.create(vertx);
        cleanup.await("undeploy", () -> vertx.undeploy(outcome.deploymentId()), BOUND);
        cleanup.step("close the client", client::close);
        JWTAuth provider = JwtAuthFactory.fromSymmetricKey(vertx, "HS256", TypedDocumentModule.SIGNING_KEY);
        return new TypedDocumentDeployment(cleanup, client, outcome, provider, observations, mountObservations);
    }

    private static void closeAfter(Cleanup cleanup, Throwable failure) {
        try {
            cleanup.close();
        } catch (AssertionError secondary) {
            failure.addSuppressed(secondary);
        }
    }

    /**
     * Mints a bearer token the deployment accepts.
     *
     * @param subject the subject
     * @param roles   the roles claim
     * @param scope   the space-separated scope claim, or {@code null} for none
     * @return the token
     */
    String token(String subject, List<String> roles, String scope) {
        return TypedDocumentModule.token(provider, subject, roles, scope);
    }

    /**
     * Returns what the fixture's authorizer and late contributor observed.
     *
     * @return the observations of an all-policies deployment
     */
    TypedDocumentObservations observations() {
        return observations;
    }

    /**
     * Returns the counters of the later plain mount of an all-policies deployment.
     *
     * @return the counters
     */
    Observations mountObservations() {
        return mountObservations;
    }

    /**
     * Sends one request.
     *
     * @param method  the method
     * @param path    the request path
     * @param token   the bearer token, or {@code null} to send no credential
     * @param headers further request headers
     * @return the response
     * @throws InterruptedException if interrupted while waiting
     */
    Reply request(HttpMethod method, String path, String token, Map<String, String> headers)
            throws InterruptedException {
        HttpRequest<Buffer> request = client.request(method, outcome.port(), HOST, path);
        if (token != null) {
            request.putHeader("Authorization", "Bearer " + token);
        }
        headers.forEach(request::putHeader);
        HttpResponse<Buffer> response = Futures.await(request.send(), BOUND);
        Buffer body = response.body() == null ? Buffer.buffer() : response.body();
        return new Reply(response.statusCode(), response.headers(), body);
    }

    @Override
    public void close() {
        cleanup.close();
    }
}
