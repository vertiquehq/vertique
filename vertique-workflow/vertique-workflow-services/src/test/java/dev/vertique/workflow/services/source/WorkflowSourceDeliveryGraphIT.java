// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.services.ServiceContractRegistry.ContractEntry;
import dev.vertique.workflow.services.signal.WorkflowSignalRequest;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies, at the dependency-injection boundary, that {@link WorkflowSourceDelivery} is an
 * in-process bean: the services module adds no service operation for it, the source module alone
 * resolves it without any outbox or inbox binding, and its constructor stays free of {@code @Inject}
 * so a graph may declare it optional.
 *
 * <p>Each component is a Dagger component generated for this test class; none needs a database or a
 * broker. The {@link Vertx} the components share is closed after the last test.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WorkflowSourceDeliveryGraphIT {

    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() throws Exception {
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("the delivery is an in-process bean and adds no service operation")
    void deliveryIsAnInProcessBeanAndAddsNoServiceOperation() {
        // Given a component that includes the services module, which includes the source module
        GraphFixture.ServicesComponent component = GraphFixture.component(vertx);

        // When the bean and the service contributors are requested, and every contributor is asked for its entries
        WorkflowSourceDelivery delivery = component.delivery();
        List<ContractEntry<?>> entries = component.contributors().stream()
                .flatMap(contributor -> contributor.contribute(new JsonObject()).stream())
                .toList();

        // Then the bean resolves and is the same instance on a second request
        assertThat(delivery).isNotNull();
        assertThat(component.delivery()).isSameAs(delivery);

        // and the only entry is the signal post, with no entry, operation or address for correlated delivery
        assertThat(entries).hasSize(1);
        ContractEntry<?> entry = entries.get(0);
        assertThat(entry.namespace()).isEqualTo("workflow");
        assertThat(entry.name()).isEqualTo("signals");
        assertThat(entry.operations()).containsOnlyKeys("post");
        assertThat(entry.operations().get("post").payloadType()).isEqualTo(WorkflowSignalRequest.class);
        assertThat(entries).noneMatch(candidate -> candidate.serviceInstance() instanceof WorkflowSourceDelivery);
    }

    @Test
    @DisplayName("a component of the source module alone resolves the delivery with no outbox or inbox binding")
    void sourceModuleAloneResolvesTheDeliveryWithoutOutboxOrInboxBindings() {
        // Given a component of the source module and stubs for operations, pool, Vert.x and the context scope only
        GraphFixture.SourceOnlyComponent component = GraphFixture.sourceOnlyComponent(vertx);

        // When the bean and the observer set are requested
        WorkflowSourceDelivery delivery = component.delivery();

        // Then the component compiled, the bean resolves and is the same instance on a second request
        assertThat(delivery).isNotNull();
        assertThat(component.delivery()).isSameAs(delivery);

        // and the observer set resolves empty
        assertThat(component.observers()).isEmpty();
    }

    @Test
    @DisplayName("the delivery bean stays bindable as an optional")
    void deliveryBeanStaysBindableAsAnOptional() {
        // Given a component that declares the bean optional beside the source module
        GraphFixture.OptionalComponent component = GraphFixture.optionalComponent(vertx);

        // When the optional and the bean are requested, and the bean's constructors are read
        Optional<WorkflowSourceDelivery> optional = component.optionalDelivery();
        WorkflowSourceDelivery direct = component.delivery();
        Constructor<?>[] publicConstructors = WorkflowSourceDelivery.class.getConstructors();
        Constructor<?>[] allConstructors = WorkflowSourceDelivery.class.getDeclaredConstructors();

        // Then the optional is present and holds the same instance as the direct request
        assertThat(optional).isPresent();
        assertThat(optional.get()).isSameAs(direct);

        // and the class has a public constructor and no constructor annotated @Inject
        assertThat(publicConstructors).isNotEmpty();
        assertThat(Arrays.stream(allConstructors))
                .noneMatch(constructor -> constructor.isAnnotationPresent(Inject.class));
    }
}
