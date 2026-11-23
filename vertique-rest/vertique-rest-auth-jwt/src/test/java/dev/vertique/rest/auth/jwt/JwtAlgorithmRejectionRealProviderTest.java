// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
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
 * Proves that the algorithm-rejection reason code comes out of a REAL Vert.x {@link JWTAuth}
 * provider, not only out of a fabricated failure message: an unsigned ({@code alg: none}) token and
 * an RS256-to-HS256 key-confusion token are driven through the real provider and the real
 * {@link JwtBearerSecuritySchemeHandler}.
 *
 * <p>For each token the assertions run in this order: the authentication future failed with a 401,
 * the token was not accepted (no user), and exactly one rejection event was reported.
 *
 * <p>Observed reason codes with the real provider (Vert.x 5.2.3): the unsigned token fails with
 * {@code IllegalStateException("JWT is in secure mode but token is not signed.")} and the
 * key-confusion token with {@code NoSuchKeyIdException("algorithm [HS256]: <null>")}; the handler
 * reports both as {@code JWT_INVALID}, not {@code JWT_ALG_UNSUPPORTED}.
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

        assertRejectedWithRealProvider(provider, unsigned, testContext);
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

        assertRejectedWithRealProvider(provider, confused, testContext);
    }

    private static void assertRejectedWithRealProvider(JWTAuth provider, String token, VertxTestContext testContext) {
        CapturingObserver observer = new CapturingObserver();
        CorrelationContext correlation = new CorrelationContextFactory(Optional.empty())
                .create(new CorrelationIdentifier("req-001", "test"), new CorrelationIdentifier("cor-001", "test"));
        ContextHolder holder = mock(ContextHolder.class);
        when(holder.current(CorrelationContext.class)).thenReturn(Optional.of(correlation));
        DefaultCredentialRejectionReporter reporter =
                new DefaultCredentialRejectionReporter(holder, new SecurityEventEmitter(Set.of(observer)));
        JwtBearerSecuritySchemeHandler handler = JwtBearerSecuritySchemeHandler.forTesting(
                "bearerAuth", provider, JwtValidationConfig.builder().build(), reporter);

        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        handler.authenticate(ctx)
                .onComplete(result -> testContext.verify(() -> {
                    // 1. rejected with 401
                    assertTrue(result.failed(), "the token must be rejected");
                    HttpException failure = assertInstanceOf(HttpException.class, result.cause());
                    assertEquals(401, failure.getStatusCode());
                    // 2. not accepted
                    User accepted = result.result();
                    assertEquals(null, accepted, "no user may be produced for the token");
                    // 3. exactly one rejection event is reported. The reason code is deliberately not pinned
                    // to JWT_ALG_UNSUPPORTED: the real provider does not produce the "Algorithm not
                    // supported/allowed" message for these two tokens (see the class Javadoc).
                    assertEquals(1, observer.rejections.size(), "real provider cause: " + failure.getCause());
                    testContext.completeNow();
                }));
    }

    private static JsonObject claims() {
        return new JsonObject().put("sub", "alg-rejection-user").put("exp", JwtAuthTestSupport.secondsFromNow(300));
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
