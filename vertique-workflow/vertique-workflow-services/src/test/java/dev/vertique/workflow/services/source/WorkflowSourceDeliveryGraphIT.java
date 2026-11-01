// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import static dev.vertique.workflow.services.source.DeliveryFixture.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.services.ServiceContractRegistry.ContractEntry;
import dev.vertique.workflow.services.signal.WorkflowSignalRequest;
import dev.vertique.workflow.services.source.ObserverFixture.RecordingObserver;
import dev.vertique.workflow.services.source.PlanFixture.ShipmentDelivered;
import dev.vertique.workflow.services.source.SourceDisposition.Fate;
import dev.vertique.workflow.source.SignalMessage;
import dev.vertique.workflow.source.SignalSourceEvent;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

    @Test
    @DisplayName("an observer contributed to the set receives outcomes with names only")
    void anObserverContributedToTheSetReceivesOutcomesWithNamesOnly() {
        // Given a component with an observer contributed to the set, and a second with none
        RecordingObserver observer = new RecordingObserver();
        GraphFixture.ObservedComponent observed = GraphFixture.component(vertx, observer);
        GraphFixture.UnobservedComponent unobserved = GraphFixture.componentWithoutObservers(vertx);

        // and a message with key PAY-77, header x-token: secret-7 and a payload holding secret-9
        SignalSourcePlan<ShipmentDelivered> plan = PlanFixture.keyedPlan();
        SignalMessage<ShipmentDelivered> message = PlanFixture.message(
                new ShipmentDelivered("PAY-77", "ORDER-5", "secret-9"), Map.of("x-token", "secret-7"));

        // When each component's delivery delivers the message, its operations answering RECORDED with orders-v2
        SourceDisposition withoutObserver = await(unobserved.delivery().deliver(plan, message, PlanFixture.envelope()));
        SourceDisposition withObserver = await(observed.delivery().deliver(plan, message, PlanFixture.envelope()));

        // Then the second component resolves with an empty observer set and delivers
        assertThat(unobserved.observers()).isEmpty();
        assertThat(withoutObserver.fate()).isEqualTo(Fate.DONE);
        assertThat(observed.observers()).containsExactly(observer);
        assertThat(withObserver.fate()).isEqualTo(Fate.DONE);

        // and the observer received one RECORDED event with the names and the definition id
        assertThat(observer.events()).hasSize(1);
        SignalSourceEvent event = observer.events().get(0);
        assertThat(event.outcome()).isEqualTo(SignalSourceEvent.Outcome.RECORDED);
        assertThat(event.source()).isEqualTo("carriers");
        assertThat(event.transport()).isEqualTo("kafka");
        assertThat(event.contractName()).isEqualTo("orders");
        assertThat(event.signalName()).isEqualTo(PlanFixture.SHIPMENT_DELIVERED);
        assertThat(event.definitionId()).isEqualTo("orders-v2");

        // and neither the event's text nor any of its fields holds the key, the header or the payload
        List<String> everything = List.of(
                event.toString(),
                event.source(),
                event.transport(),
                event.contractName(),
                event.signalName(),
                event.definitionId(),
                event.outcome().name());
        for (String secret : List.of("PAY-77", "secret-7", "secret-9")) {
            assertThat(everything).as("nothing in the event holds %s", secret).noneMatch(text -> text.contains(secret));
        }
    }
}
