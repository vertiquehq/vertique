// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.rest.security.DefaultCredentialRejectionReporter;
import dev.vertique.security.events.CredentialRejectedEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.NoSuchKeyIdException;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves end to end that an unsigned ({@code alg: none}) token and an RS256-to-HS256 key-confusion
 * token are rejected by a REAL Vert.x {@link JWTAuth} provider behind the real
 * {@link JwtBearerSecuritySchemeHandler}, not only by a fabricated failure message. A correctly
 * signed RS256 token on the same provider and handler is accepted, so the rejections come from the
 * token and not from the test wiring.
 *
 * <p>For each attack token the assertions run in this order: the authentication future failed with
 * a 401, the provider's failure is the expected cause type, and exactly one rejection event was
 * reported, with the reason code {@code JWT_ALG_UNSUPPORTED}, so the audit log names the attack.
 *
 * <p>The provider fails an unsigned token with {@code IllegalStateException} ("JWT is in secure mode
 * but token is not signed." without a signature segment, {@code Algorithm "none" not allowed} with
 * one) and a token whose algorithm it holds no key for with {@code NoSuchKeyIdException}. A kid that
 * matches no key of an algorithm the provider does hold keys for is not an algorithm attack, so it
 * stays {@code JWT_INVALID}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class JwtAlgorithmRejectionRealProviderTest {

    private static final String RS256 = "RS256";

    /** Collects the rejection events the handler's reporter emits. */
    private static final class CapturingObserver implements SecurityEventObserver {
        final List<CredentialRejectedEvent> rejections = new ArrayList<>();

        @Override
        public Future<Void> onCredentialRejected(CredentialRejectedEvent event) {
            rejections.add(event);
            return Future.succeededFuture();
        }
    }

    @Test
    @DisplayName("An unsigned alg=none token is rejected with 401 and one rejection event")
    void unsignedTokenIsRejected(Vertx vertx, VertxTestContext testContext) throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        JWTAuth provider = JwtAuthFactory.fromPublicKey(vertx, RS256, pem("PUBLIC KEY", keyPair.getPublic()));

        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = base64Url(claims().encode().getBytes(StandardCharsets.UTF_8));
        String unsigned = header + "." + payload + ".";

        assertRejectedWithRealProvider(
                provider, unsigned, IllegalStateException.class, "JWT_ALG_UNSUPPORTED", testContext);
    }

    @Test
    @DisplayName("An alg=none token that carries a signature segment is rejected as JWT_ALG_UNSUPPORTED")
    void unsignedTokenWithSignatureSegmentIsRejected(Vertx vertx, VertxTestContext testContext) throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        JWTAuth provider = JwtAuthFactory.fromPublicKey(vertx, RS256, pem("PUBLIC KEY", keyPair.getPublic()));

        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = base64Url(claims().encode().getBytes(StandardCharsets.UTF_8));
        String unsigned = header + "." + payload + "." + base64Url("junk".getBytes(StandardCharsets.UTF_8));

        assertRejectedWithRealProvider(
                provider, unsigned, IllegalStateException.class, "JWT_ALG_UNSUPPORTED", testContext);
    }

    @Test
    @DisplayName("A token whose algorithm the provider holds no key for is rejected as JWT_ALG_UNSUPPORTED")
    void tokenWithUnconfiguredAlgorithmIsRejected(Vertx vertx, VertxTestContext testContext) throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        JWTAuth provider = JwtAuthFactory.fromPublicKey(vertx, RS256, pem("PUBLIC KEY", keyPair.getPublic()));

        String token = base64Url("{\"alg\":\"ES256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "."
                + base64Url(claims().encode().getBytes(StandardCharsets.UTF_8))
                + "."
                + base64Url("junk".getBytes(StandardCharsets.UTF_8));

        assertRejectedWithRealProvider(provider, token, NoSuchKeyIdException.class, "JWT_ALG_UNSUPPORTED", testContext);
    }

    @Test
    @DisplayName("A kid that matches no key of a configured algorithm stays JWT_INVALID")
    void unknownKidOnConfiguredAlgorithmStaysInvalid(Vertx vertx, VertxTestContext testContext) throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        JWTAuth provider = JWTAuth.create(
                vertx,
                new JWTAuthOptions()
                        .addPubSecKey(new PubSecKeyOptions()
                                .setAlgorithm(RS256)
                                .setBuffer(pem("PUBLIC KEY", keyPair.getPublic()))
                                .setId("current-key")));

        String token = signRs256(keyPair, "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"rotated-away\"}");

        assertRejectedWithRealProvider(provider, token, NoSuchKeyIdException.class, "JWT_INVALID", testContext);
    }

    @Test
    @DisplayName("An RS256-to-HS256 key-confusion token is rejected with 401 and one rejection event")
    void keyConfusionTokenIsRejected(Vertx vertx, VertxTestContext testContext) throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        String publicKeyPem = pem("PUBLIC KEY", keyPair.getPublic());
        JWTAuth provider = JwtAuthFactory.fromPublicKey(vertx, RS256, publicKeyPem);

        String signingInput = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "."
                + base64Url(claims().encode().getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(publicKeyPem.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String confused = signingInput + "." + base64Url(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));

        assertRejectedWithRealProvider(
                provider, confused, NoSuchKeyIdException.class, "JWT_ALG_UNSUPPORTED", testContext);
    }

    @Test
    @DisplayName("A correctly signed RS256 token is accepted by the same provider and handler")
    void correctlySignedTokenIsAccepted(Vertx vertx, VertxTestContext testContext) throws Exception {
        KeyPair keyPair = generateRsaKeyPair();
        JWTAuth provider = JwtAuthFactory.fromPublicKey(vertx, RS256, pem("PUBLIC KEY", keyPair.getPublic()));

        String signed = signRs256(keyPair, "{\"alg\":\"RS256\",\"typ\":\"JWT\"}");

        CapturingObserver observer = new CapturingObserver();
        handler(provider, observer)
                .authenticate(context(signed))
                .onComplete(result -> testContext.verify(() -> {
                    assertTrue(result.succeeded(), "a correctly signed token must be accepted: " + result.cause());
                    assertNotNull(result.result(), "the accepted token must produce a user");
                    assertEquals(0, observer.rejections.size());
                    testContext.completeNow();
                }));
    }

    private static void assertRejectedWithRealProvider(
            JWTAuth provider,
            String token,
            Class<? extends Throwable> expectedCause,
            String expectedReasonCode,
            VertxTestContext testContext) {
        CapturingObserver observer = new CapturingObserver();
        handler(provider, observer)
                .authenticate(context(token))
                .onComplete(result -> testContext.verify(() -> {
                    // 1. rejected with 401
                    assertTrue(result.failed(), "the token must be rejected");
                    HttpException failure = assertInstanceOf(HttpException.class, result.cause());
                    assertEquals(401, failure.getStatusCode());
                    // 2. rejected by the provider for the expected reason, not by some other failure
                    assertTrue(
                            causeChainContains(failure, expectedCause),
                            "expected a " + expectedCause.getSimpleName() + " in the cause chain, got: "
                                    + failure.getCause());
                    // 3. exactly one rejection event is reported, naming the expected reason
                    assertEquals(1, observer.rejections.size());
                    assertEquals(expectedReasonCode, observer.rejections.get(0).reasonCode());
                    testContext.completeNow();
                }));
    }

    private static boolean causeChainContains(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                return false;
            }
        }
        return false;
    }

    private static JwtBearerSecuritySchemeHandler handler(JWTAuth provider, CapturingObserver observer) {
        CorrelationContext correlation = new CorrelationContextFactory(Optional.empty())
                .create(new CorrelationIdentifier("req-001", "test"), new CorrelationIdentifier("cor-001", "test"));
        ContextHolder holder = mock(ContextHolder.class);
        when(holder.current(CorrelationContext.class)).thenReturn(Optional.of(correlation));
        DefaultCredentialRejectionReporter reporter =
                new DefaultCredentialRejectionReporter(holder, new SecurityEventEmitter(Set.of(observer)));
        return JwtBearerSecuritySchemeHandler.forTesting(
                "bearerAuth", provider, JwtValidationConfig.builder().build(), reporter);
    }

    private static RoutingContext context(String token) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        return ctx;
    }

    private static JsonObject claims() {
        return new JsonObject().put("sub", "alg-rejection-user").put("exp", JwtAuthTestSupport.secondsFromNow(300));
    }

    private static String signRs256(KeyPair keyPair, String headerJson) throws Exception {
        String signingInput = base64Url(headerJson.getBytes(StandardCharsets.UTF_8))
                + "."
                + base64Url(claims().encode().getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + base64Url(signature.sign());
    }

    private static KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String pem(String label, java.security.Key key) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded());
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
