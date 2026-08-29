// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.resilience.Resilience;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Contract tests for explicit REST builder and application-runtime ownership. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RestClientResilienceLifecycleTest {

    @RestClient(name = "lifecycle-client")
    interface LifecycleClient {

        @GET
        @Path("/resource")
        Future<String> resource();
    }

    @Test
    void closesOnlyOwnedRuntimeAndContexts(Vertx vertx, VertxTestContext testContext) {
        RestClientBuilder ownedBuilder = RestClientBuilder.create(vertx).baseUrl("http://localhost:1");
        ownedBuilder.build(LifecycleClient.class);
        ownedBuilder.build(LifecycleClient.class);

        Future<Void> firstClose = ownedBuilder.close();
        Future<Void> secondClose = ownedBuilder.close();
        assertThat(secondClose).isSameAs(firstClose);

        firstClose.onComplete(testContext.succeeding(ignored -> testContext.verify(() -> {
            assertThatThrownBy(() -> ownedBuilder.build(LifecycleClient.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("REST client builder is closed");
            testContext.completeNow();
        })));
    }

    @Test
    void suppliedRuntimeRemainsOpenAfterBuilderClose(Vertx vertx, VertxTestContext testContext) {
        Resilience supplied = Resilience.create(vertx);
        RestClientBuilder builder = RestClientBuilder.create(vertx, supplied).baseUrl("http://localhost:1");
        builder.build(LifecycleClient.class);

        builder.close()
                .onComplete(testContext.succeeding(ignored -> testContext.verify(() -> {
                    assertThat(Arrays.stream(RestClientBuilder.class.getDeclaredConstructors())
                                    .map(Constructor::getParameterTypes)
                                    .anyMatch(parameters ->
                                            Arrays.asList(parameters).contains(Resilience.class)))
                            .isFalse();
                    var context = supplied.adapterSupport().newContext();
                    context.close().onComplete(testContext.succeeding(ignoredContext -> supplied.close()
                            .onComplete(testContext.succeeding(ignoredRuntime -> testContext.completeNow()))));
                })));
    }
}
