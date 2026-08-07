// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(VertxExtension.class)
class JwtAuthFactoryTest {

    @Test
    @DisplayName("Should create JWTAuth from classpath JWKS and generate a token")
    void shouldCreateFromClasspathJwks(Vertx vertx) {
        JWTAuth auth = JwtAuthFactory.fromJwks(vertx, "classpath:test-jwks.json");

        assertNotNull(auth);
        String token = auth.generateToken(new JsonObject().put("sub", "test"));
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    @DisplayName("Should throw UncheckedIOException for missing classpath resource")
    void shouldThrowForMissingClasspathResource(Vertx vertx) {
        assertThrows(UncheckedIOException.class, () -> JwtAuthFactory.fromJwks(vertx, "classpath:nonexistent.json"));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for null location")
    void shouldThrowForNullLocation(Vertx vertx) {
        assertThrows(IllegalArgumentException.class, () -> JwtAuthFactory.fromJwks(vertx, null));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for blank location")
    void shouldThrowForBlankLocation(Vertx vertx) {
        assertThrows(IllegalArgumentException.class, () -> JwtAuthFactory.fromJwks(vertx, "  "));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for JWKS without 'keys' array")
    void shouldThrowForJwksWithoutKeysArray(@TempDir Path tempDir, Vertx vertx) throws Exception {
        Path jwksFile = tempDir.resolve("bad-jwks.json");
        Files.writeString(jwksFile, "{\"notkeys\":[]}");

        assertThrows(IllegalArgumentException.class, () -> JwtAuthFactory.fromJwks(vertx, jwksFile.toString()));
    }

    @Test
    @DisplayName("Should create JWTAuth from symmetric key and generate a token")
    void shouldCreateFromSymmetricKey(Vertx vertx) {
        JWTAuth auth =
                JwtAuthFactory.fromSymmetricKey(vertx, "HS256", "super-secret-key-for-testing-minimum-256-bits-long!");

        assertNotNull(auth);
        String token = auth.generateToken(new JsonObject().put("sub", "test"));
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    @DisplayName("Should create JWTAuth from filesystem path")
    void shouldCreateFromFilesystemPath(@TempDir Path tempDir, Vertx vertx) throws Exception {
        Path jwksFile = copyJwksToTempDir(tempDir);

        JWTAuth auth = JwtAuthFactory.fromJwks(vertx, jwksFile.toString());
        assertNotNull(auth);
    }

    @Test
    @DisplayName("Should delegate authenticate and both generateToken overloads through the returned JWTAuth")
    void shouldDelegateEveryJwtAuthMethod(Vertx vertx, VertxTestContext testContext) {
        // The factory returns a wrapper that attests the validation config it applied. The wrapper
        // must forward every JWTAuth method: dropping either generateToken overload would silently
        // break token signing for every application (and every test) that signs through the factory.
        JWTAuth auth =
                JwtAuthFactory.fromSymmetricKey(vertx, "HS256", "super-secret-key-for-testing-minimum-256-bits-long!");

        String defaultOptionsToken = auth.generateToken(new JsonObject().put("sub", "delegation-default"));
        String explicitOptionsToken =
                auth.generateToken(new JsonObject().put("sub", "delegation-explicit"), new JWTOptions());

        assertNotNull(defaultOptionsToken);
        assertFalse(defaultOptionsToken.isBlank());
        assertNotNull(explicitOptionsToken);
        assertFalse(explicitOptionsToken.isBlank());

        auth.authenticate(new TokenCredentials(defaultOptionsToken))
                .compose(user -> auth.authenticate(new TokenCredentials(explicitOptionsToken)))
                .onComplete(testContext.succeeding(user -> testContext.completeNow()));
    }

    @Test
    @DisplayName("Should not complete synchronously on the event loop for a filesystem location")
    void shouldNotCompleteSynchronouslyOnEventLoopForFilesystemLocation(
            @TempDir Path tempDir, Vertx vertx, VertxTestContext testContext) throws Exception {
        Path jwksFile = copyJwksToTempDir(tempDir);

        assertDoesNotCompleteOnCallingContext(vertx, testContext, jwksFile.toString());
    }

    @Test
    @DisplayName("Should not complete synchronously on the event loop for a classpath location")
    void shouldNotCompleteSynchronouslyOnEventLoopForClasspathLocation(Vertx vertx, VertxTestContext testContext) {
        assertDoesNotCompleteOnCallingContext(vertx, testContext, "classpath:test-jwks.json");
    }

    /**
     * Copies the test JWKS fixture off the classpath into {@code tempDir}, so a test can exercise
     * the factory's filesystem branch rather than its {@code classpath:} branch.
     *
     * @param tempDir the JUnit-managed temporary directory to copy into
     * @return the path of the copied JWKS document
     * @throws Exception if the fixture cannot be read or written
     */
    private static Path copyJwksToTempDir(Path tempDir) throws Exception {
        Path jwksFile = tempDir.resolve("jwks.json");
        try (var is = JwtAuthFactoryTest.class.getResourceAsStream("/test-jwks.json")) {
            assertNotNull(is, "test-jwks.json must exist on test classpath");
            Files.copy(is, jwksFile);
        }
        return jwksFile;
    }

    /**
     * Asserts that {@link JwtAuthFactory#fromJwksAsync(Vertx, String)} moves the JWKS read off the
     * calling thread, and that the future it returns still resolves successfully.
     *
     * <p>The read is issued from inside {@code vertx.runOnContext(...)} — the same footing as
     * {@code RefreshableJwtAuth}'s refresh tick — and the future is inspected on the very next
     * statement. Once the read runs on a worker thread, the calling task cannot observe a completed
     * future: the worker pool would have to hand off a thread and finish the whole read within the
     * single field access that follows the call. When the read happens inline the future is a
     * {@code Future.succeededFuture(...)} and is complete before {@code fromJwksAsync} even returns,
     * which is the event-loop block this asserts against.
     *
     * @param vertx       the Vert.x instance supplying the calling context
     * @param testContext the async assertion sink
     * @param location    the JWKS location to load
     */
    private static void assertDoesNotCompleteOnCallingContext(
            Vertx vertx, VertxTestContext testContext, String location) {
        vertx.runOnContext(ignored -> {
            Future<JWTAuth> future = JwtAuthFactory.fromJwksAsync(vertx, location);
            boolean completeWhileCallerStillRunning = future.isComplete();

            future.onComplete(testContext.succeeding(auth -> testContext.verify(() -> {
                assertFalse(
                        completeWhileCallerStillRunning,
                        "fromJwksAsync must dispatch the JWKS read to a worker thread; the returned future "
                                + "was already complete on the calling event-loop task, so the read blocked it");
                assertNotNull(auth);
                testContext.completeNow();
            })));
        });
    }
}
