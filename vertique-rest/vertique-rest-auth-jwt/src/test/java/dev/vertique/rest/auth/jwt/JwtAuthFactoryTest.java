// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.junit5.VertxExtension;
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
        Path jwksFile = tempDir.resolve("jwks.json");
        try (var is = getClass().getResourceAsStream("/test-jwks.json")) {
            assertNotNull(is, "test-jwks.json must exist on test classpath");
            Files.copy(is, jwksFile);
        }

        JWTAuth auth = JwtAuthFactory.fromJwks(vertx, jwksFile.toString());
        assertNotNull(auth);
    }
}
