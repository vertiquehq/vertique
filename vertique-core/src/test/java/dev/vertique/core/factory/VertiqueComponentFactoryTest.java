// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import dev.vertique.core.VertiqueComponentFactory;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.core.VertxModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AC-12 positive proof for the neutral graph-input seam: a fake embedded host builds its Dagger
 * graph through a {@link VertiqueComponentFactory} fed only a {@link VertiqueRuntime}, and a
 * host-native bean ({@link FakeDataSource}) reaches a framework consumer by <em>typed</em> Dagger
 * injection — no host-bean locator (FR-APP-004).
 *
 * <p>This test lives in {@code vertique-core} test scope, which structurally has no
 * {@code vertique-launcher} on its classpath, proving the seam is launcher-free.
 *
 * <p>A Mockito mock {@link Vertx} is sufficient: the reached bindings ({@code consumer}, {@code
 * vertx}, {@code config}) never invoke {@code vertx.eventBus()}, so no event loop is needed.
 */
class VertiqueComponentFactoryTest {

    @Test
    @DisplayName("factory builds the host graph from a runtime; host bean reaches the consumer by type")
    void factoryBuildsGraph_typedHostBeanReachesConsumer() {
        Vertx vertx = mock(Vertx.class);
        JsonObject config = new JsonObject().put("app.name", "fake-host");
        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, config);

        // The app/bridge-supplied factory: build the framework's VertxModule from the neutral
        // runtime, plus the host's typed adapter module — no locator anywhere.
        VertiqueComponentFactory<FakeHostComponent> factory = rt -> DaggerFakeHostComponent.builder()
                .vertxModule(new VertxModule(rt.vertx(), rt.config()))
                .fakeHostAdapterModule(new FakeHostAdapterModule())
                .build();

        FakeHostComponent component = factory.build(runtime);

        // The consumer received the host-native bean via constructor injection (typed, not located).
        FakeDataSourceConsumer consumer = component.consumer();
        assertNotNull(consumer.dataSource());
        assertEquals("host-provided", consumer.dataSource().label());

        // The runtime's inputs flowed through the graph unchanged.
        assertSame(vertx, component.vertx());
        assertSame(config, component.config());
    }
}
