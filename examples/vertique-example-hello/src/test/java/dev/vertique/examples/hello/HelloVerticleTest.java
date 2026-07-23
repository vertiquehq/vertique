// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.hello;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that {@link HelloVerticle} replies to event-bus requests with the configured greeting.
 */
@ExtendWith(VertxExtension.class)
class HelloVerticleTest {

    @BeforeAll
    static void deployVerticle(Vertx vertx, VertxTestContext testContext) {
        HelloConfig config = HelloConfig.builder().hello("Hello, %s!").build();

        vertx.deployVerticle(new HelloVerticle(config))
                .onComplete(testContext.succeeding(id -> testContext.completeNow()));
    }

    @Test
    @DisplayName("Should reply with hello message")
    void shouldReplyWithHelloMessage(Vertx vertx, VertxTestContext testContext) {
        String name = "Alice";
        vertx.eventBus().<String>request(HelloVerticle.ADDRESS, name).onComplete(testContext.succeeding(reply -> {
            testContext.verify(() -> {
                assertEquals("Hello, Alice!", reply.body());
                testContext.completeNow();
            });
        }));
    }
}
