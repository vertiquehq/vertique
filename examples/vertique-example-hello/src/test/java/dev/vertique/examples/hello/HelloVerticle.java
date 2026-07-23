// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.hello;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HelloVerticle extends AbstractVerticle {
    public static final String ADDRESS = "hello.service";

    private final HelloConfig config;

    @Inject
    public HelloVerticle(HelloConfig config) {
        log.info("Instantiating HelloVerticle");
        this.config = config;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        vertx.eventBus().<String>consumer(ADDRESS, message -> {
            String name = message.body();
            log.info("Received message: {}", name);
            String response = String.format(config.hello(), name);
            message.reply(response);
        });

        startPromise.complete();
    }
}
