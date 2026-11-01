// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import static dev.vertique.workflow.services.source.PlanFixture.INVOICE_SETTLED;
import static dev.vertique.workflow.services.source.PlanFixture.SHIPMENT_DELIVERED;
import static dev.vertique.workflow.services.source.PlanFixture.confirmations;
import static dev.vertique.workflow.services.source.PlanFixture.envelope;
import static dev.vertique.workflow.services.source.PlanFixture.message;
import static dev.vertique.workflow.services.source.PlanFixture.shipments;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowSignalNoMatchException;
import dev.vertique.workflow.services.source.PlanFixture.BillingContract;
import dev.vertique.workflow.services.source.PlanFixture.DeliveryConfirmed;
import dev.vertique.workflow.services.source.PlanFixture.OrdersContract;
import dev.vertique.workflow.services.source.PlanFixture.ShipmentDelivered;
import dev.vertique.workflow.services.source.SourceDisposition.Fate;
import dev.vertique.workflow.source.SignalNoMatch;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.Nullable;
import java.lang.reflect.Method;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies what building a {@link SignalSourcePlan} accepts and refuses, through the plan builder and
 * through a transport-style builder that extends {@link AbstractSignalSourceBuilder}: a signal needs
 * a key source, a message type that differs from the signal's needs a payload mapping, a business key
 * needs a dedup key, scope and any-scope are exclusive, the lookup bound has a floor, and
 * {@code build()} returns one instance.
 *
 * <p>Every failure names the source. Nothing here needs a transport, a broker or a database.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class SignalSourcePlanBuildTest {

    /** The few lines a transport's own builder adds: one stage of its own and its {@code build()}. */
    static final class FakeTransportBuilder
            extends AbstractSignalSourceBuilder<
                    DeliveryConfirmed, FakeTransportBuilder, SignalSourcePlan<DeliveryConfirmed>> {
        private final String sourceName;
        private String topic;

        FakeTransportBuilder(String sourceName, @Nullable Class<DeliveryConfirmed> messageType) {
            super(messageType);
            this.sourceName = sourceName;
        }

        FakeTransportBuilder topic(String topic) {
            this.topic = topic;
            return this;
        }

        String topic() {
            return topic;
        }

        @Override
        public SignalSourcePlan<DeliveryConfirmed> build() {
            return plan(sourceName);
        }
    }

    @Test
    @DisplayName("a signal with no key source fails at build and the message names the source and the signal")
    void aSignalWithoutAKeySourceFailsAtBuildNamingTheSource() {
        // Given a plan for source carriers with one signal and nothing after it
        SignalSourcePlan.Builder<DeliveryConfirmed> builder = confirmations();
        builder.signal(OrdersContract.class, SHIPMENT_DELIVERED);

        // When the plan is built
        // Then construction fails with a message that names the source and the signal
        assertThatThrownBy(builder::build)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("carriers")
                .hasMessageContaining(SHIPMENT_DELIVERED);
    }

    @Test
    @DisplayName("a keyed signal offers no second key source but still offers the next signal, a payload and build")
    void aKeyedSignalOffersNoSecondKeySource() throws Exception {
        // Given the type the plan returns after a key source
        // When its public methods are listed
        Set<String> names = methodNames(KeyedTargetStage.class);

        // Then no key source is among them, so a second one cannot be written
        assertThat(names).doesNotContain("key", "keyFrom", "businessKey", "businessKeyFrom");

        // and the next signal, a payload mapping and build are
        assertThat(KeyedTargetStage.class.getMethod("signal", Class.class, String.class))
                .isNotNull();
        assertThat(KeyedTargetStage.class.getMethod("payload", Function.class)).isNotNull();
        assertThat(names).contains("build");

        // and onNoMatch(SignalNoMatch) is among them, returning the keyed stage
        Method keyedPolicy = KeyedTargetStage.class.getMethod("onNoMatch", SignalNoMatch.class);
        assertThat(keyedPolicy.getReturnType()).isEqualTo(KeyedTargetStage.class);

        // and the shared stages declare no onNoMatch, while the source stages declare one returning SELF
        assertThat(methodNames(SignalStages.class)).doesNotContain("onNoMatch");
        Method sourcePolicy = SourceStages.class.getDeclaredMethod("onNoMatch", SignalNoMatch.class);
        assertThat(sourcePolicy.getGenericReturnType())
                .isInstanceOfSatisfying(TypeVariable.class, variable -> assertThat(variable.getName())
                        .isEqualTo("SELF"));

        // and the keyed stage is not a source stage, so the two onNoMatch never clash on one type
        assertThat(SourceStages.class.isAssignableFrom(KeyedTargetStage.class)).isFalse();
    }

    @Test
    @DisplayName("a message type that differs from the signal's type needs a payload mapping")
    void messageTypeDifferingFromTheSignalTypeWithoutPayloadFailsNamingBothTypes() {
        // Given a signal that takes DeliveryConfirmed
        // When a plan over ShipmentDelivered has no payload mapping
        // Then construction fails and the message names both types
        assertThatThrownBy(() -> shipments()
                        .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                        .key(ShipmentDelivered::carrierRef)
                        .build())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("ShipmentDelivered")
                .hasMessageContaining("DeliveryConfirmed");

        // and the same plan with a payload mapping builds
        assertThat(shipments()
                        .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                        .key(ShipmentDelivered::carrierRef)
                        .payload(PlanFixture::confirmation)
                        .build())
                .isNotNull();

        // and a plan over DeliveryConfirmed builds without one
        assertThat(confirmations()
                        .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                        .key(DeliveryConfirmed::carrierRef)
                        .build())
                .isNotNull();
    }

    @Test
    @DisplayName("naming both scope and any-scope fails at build in either order")
    void scopeAndAnyScopeTogetherFailAtBuild() {
        // Given a plan with a scope function and any-scope, written in either order
        // When each is built
        // Then both fail at construction and name the source
        assertThatThrownBy(() -> confirmations()
                        .scope(message -> Optional.of("acme"))
                        .anyScope()
                        .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                        .key(DeliveryConfirmed::carrierRef)
                        .build())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("carriers");
        assertThatThrownBy(() -> confirmations()
                        .anyScope()
                        .scope(message -> Optional.of("acme"))
                        .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                        .key(DeliveryConfirmed::carrierRef)
                        .build())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("carriers");

        // and a plan with any-scope only builds
        assertThat(confirmations()
                        .anyScope()
                        .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                        .key(DeliveryConfirmed::carrierRef)
                        .build())
                .isNotNull();
    }

    @Test
    @DisplayName("a business-key source without a dedup key fails at build and a key source without one builds")
    void businessKeySourceWithoutDedupByFailsAtBuildAndAKeySourceWithoutItBuilds() {
        // Given a business-key plan with no dedup key, the same with one, and a key plan with none
        // When each is built
        // Then the first fails and names the source
        assertThatThrownBy(() -> confirmations()
                        .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                        .businessKey(DeliveryConfirmed::carrierRef)
                        .build())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("carriers");

        // and so does a business-key lookup with none
        assertThatThrownBy(() -> confirmations()
                        .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                        .businessKeyFrom(message -> Future.succeededFuture(Optional.of("ORDER-5")))
                        .build())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("carriers");

        // and the other two build
        assertThat(confirmations()
                        .dedupBy(DeliveryConfirmed::carrierRef)
                        .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                        .businessKey(DeliveryConfirmed::carrierRef)
                        .build())
                .isNotNull();
        assertThat(confirmations()
                        .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                        .key(DeliveryConfirmed::carrierRef)
                        .build())
                .isNotNull();
    }

    @Test
    @DisplayName("the lookup timeout defaults to 5000, builds at 1 and is refused at 0 and below")
    void lookupTimeoutDefaultsToFiveThousandAndIsRefusedBelowOne() {
        // Given a plan with no lookup timeout, and plans with 1, 0 and -5
        SignalSourcePlan<DeliveryConfirmed> unset = confirmations()
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .key(DeliveryConfirmed::carrierRef)
                .build();
        SignalSourcePlan<DeliveryConfirmed> one = confirmations()
                .lookupTimeoutMs(1)
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .key(DeliveryConfirmed::carrierRef)
                .build();

        // When each is built and the plan's effective timeout is read
        // Then the default reads 5000 and 1 builds
        assertThat(unset.lookupTimeoutMs()).isEqualTo(5000);
        assertThat(one.lookupTimeoutMs()).isEqualTo(1);

        // and 0 and -5 fail at construction and name lookupTimeoutMs
        for (long refused : new long[] {0, -5}) {
            assertThatThrownBy(() -> confirmations()
                            .lookupTimeoutMs(refused)
                            .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                            .key(DeliveryConfirmed::carrierRef)
                            .build())
                    .as("lookupTimeoutMs(%d)", refused)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("lookupTimeoutMs");
        }
    }

    @Test
    @DisplayName("a builder that extends the abstract builder inherits every stage and the construction checks")
    void aBuilderExtendingTheAbstractBuilderInheritsEveryStageAndTheConstructionChecks(Vertx vertx) {
        // Given a transport-style builder that adds one stage of its own
        FakeTransportBuilder fake = new FakeTransportBuilder("carriers", DeliveryConfirmed.class);

        // When every shared stage is written on it
        FakeTransportBuilder afterTopic = fake.topic("carrier.events");
        FakeTransportBuilder afterWhen = afterTopic.when(body -> true);
        FakeTransportBuilder afterScope = afterWhen.scope(message -> Optional.of("acme"));
        FakeTransportBuilder afterDedup = afterScope.dedupBy(DeliveryConfirmed::carrierRef);
        FakeTransportBuilder afterTimeout = afterDedup.lookupTimeoutMs(250);
        FakeTransportBuilder afterIdentity =
                afterTimeout.dispatchAs(message -> Future.succeededFuture(Optional.empty()));
        FakeTransportBuilder afterRestore = afterIdentity.restoreCorrelationFromHeaders();
        FakeTransportBuilder afterPolicy = afterRestore.onNoMatch(SignalNoMatch.RETRY);
        SignalSourcePlan<DeliveryConfirmed> everyStage = afterPolicy
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .key(DeliveryConfirmed::carrierRef)
                .build();

        // Then each returned the transport's own builder, so the chain compiled, and a plan was built
        assertThat(afterPolicy).isInstanceOf(FakeTransportBuilder.class);
        assertThat(afterTopic.topic()).isEqualTo("carrier.events");
        assertThat(everyStage).isNotNull();
        assertThat(everyStage.lookupTimeoutMs()).isEqualTo(250);

        // and a chain that ends in the signal's own policy and a payload builds a plan holding the source
        // default, the signal's own policy and the dedup stage
        SignalSourcePlan<DeliveryConfirmed> plan = new FakeTransportBuilder("carriers", DeliveryConfirmed.class)
                .topic("carrier.events")
                .dedupBy(body -> "D-" + body.carrierRef())
                .onNoMatch(SignalNoMatch.RETRY)
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .key(DeliveryConfirmed::carrierRef)
                .onNoMatch(SignalNoMatch.IGNORE)
                .payload(body -> body)
                .signal(BillingContract.class, INVOICE_SETTLED)
                .key(DeliveryConfirmed::carrierRef)
                .build();
        DeliveryConfirmed body = new DeliveryConfirmed("PAY-77", "delivered");
        try (DeliveryRig rig = DeliveryRig.over(vertx)) {
            // the first signal finds no instance: its own IGNORE wins over the source default, and the second records
            rig.operations(
                    DeliveryRig.raising(new WorkflowSignalNoMatchException("orders", SHIPMENT_DELIVERED)),
                    DeliveryRig.recorded());
            SourceDisposition ignoredOwnPolicy = rig.deliver(plan, message(body), envelope());
            assertThat(ignoredOwnPolicy.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.signals()).extracting(signal -> signal.dedupKey()).containsExactly("D-PAY-77", "D-PAY-77");

            // the second signal finds no instance: the source default applies, so the message retries
            rig.operations(
                    DeliveryRig.recorded(),
                    DeliveryRig.raising(new WorkflowSignalNoMatchException("billing", INVOICE_SETTLED)));
            SourceDisposition retriedByDefault =
                    rig.deliver(plan, message(new DeliveryConfirmed("PAY-78", "delivered")), envelope());
            assertThat(retriedByDefault.fate()).isEqualTo(Fate.RETRY);
        }

        // and a signal with no key source fails at build, naming the source, as the plan builder does
        FakeTransportBuilder noKey = new FakeTransportBuilder("carriers", DeliveryConfirmed.class);
        noKey.signal(OrdersContract.class, SHIPMENT_DELIVERED);
        assertThatThrownBy(noKey::build).isInstanceOf(RuntimeException.class).hasMessageContaining("carriers");

        // and so does a scope function beside any-scope
        FakeTransportBuilder bothScopes = new FakeTransportBuilder("carriers", DeliveryConfirmed.class)
                .scope(message -> Optional.of("acme"))
                .anyScope();
        bothScopes.signal(OrdersContract.class, SHIPMENT_DELIVERED).key(DeliveryConfirmed::carrierRef);
        assertThatThrownBy(bothScopes::build)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("carriers");
    }

    @Test
    @DisplayName("hasSharedStages is true once any shared stage or a signal target is set")
    void hasSharedStagesIsTrueOnceAnySharedStageOrASignalTargetIsSet() {
        // Given a builder that has had nothing written on it
        assertThat(new FakeTransportBuilder("carriers", DeliveryConfirmed.class).hasSharedStages())
                .isFalse();

        // When each shared stage is written alone on a fresh builder, then each one makes it true
        Map<String, Function<FakeTransportBuilder, FakeTransportBuilder>> stages = new LinkedHashMap<>();
        stages.put("when", builder -> builder.when(body -> true));
        stages.put("scope", builder -> builder.scope(message -> Optional.of("acme")));
        stages.put("anyScope", FakeTransportBuilder::anyScope);
        stages.put("dispatchAs", builder -> builder.dispatchAs(message -> Future.succeededFuture(Optional.empty())));
        stages.put("onNoMatch", builder -> builder.onNoMatch(SignalNoMatch.RETRY));
        stages.put("lookupTimeoutMs", builder -> builder.lookupTimeoutMs(250));
        stages.forEach((name, stage) -> assertThat(
                        stage.apply(new FakeTransportBuilder("carriers", null)).hasSharedStages())
                .as(name)
                .isTrue());

        // and a signal target alone makes it true
        FakeTransportBuilder signalled = new FakeTransportBuilder("carriers", DeliveryConfirmed.class);
        signalled.signal(OrdersContract.class, SHIPMENT_DELIVERED);
        assertThat(signalled.hasSharedStages()).isTrue();

        // and a transport's own stage does not
        assertThat(new FakeTransportBuilder("carriers", DeliveryConfirmed.class)
                        .topic("carrier.events")
                        .hasSharedStages())
                .isFalse();
    }

    @Test
    @DisplayName("a source with no stated message type needs a payload mapping on every signal")
    void aSourceWithNoMessageTypeNeedsAPayloadMappingOnEverySignal() {
        // Given a transport-style builder with a null message type and two signals
        // When the payload mapping follows both signals
        FakeTransportBuilder mappedTwice = new FakeTransportBuilder("carriers", null);
        mappedTwice
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .key(DeliveryConfirmed::carrierRef)
                .payload(body -> body)
                .signal(BillingContract.class, INVOICE_SETTLED)
                .key(DeliveryConfirmed::carrierRef)
                .payload(body -> body);

        // and when it follows the first only
        FakeTransportBuilder mappedOnce = new FakeTransportBuilder("carriers", null);
        mappedOnce
                .signal(OrdersContract.class, SHIPMENT_DELIVERED)
                .key(DeliveryConfirmed::carrierRef)
                .payload(body -> body)
                .signal(BillingContract.class, INVOICE_SETTLED)
                .key(DeliveryConfirmed::carrierRef);

        // Then the first builds
        assertThat(mappedTwice.build()).isNotNull();

        // and the second fails, naming the source and the signal that lacks the mapping
        assertThatThrownBy(mappedOnce::build)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("carriers")
                .hasMessageContaining(INVOICE_SETTLED);

        // and the public builder refuses a null message type
        assertThatThrownBy(() -> SignalSourcePlan.<DeliveryConfirmed>builder("carriers", null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("build returns the same instance twice and a stage called after it fails")
    void buildIsIdempotentAndAStageCalledAfterItFails(Vertx vertx) {
        // Given a plan builder with a complete source
        SignalSourcePlan.Builder<DeliveryConfirmed> builder = confirmations().dedupBy(body -> "D-" + body.carrierRef());
        builder.signal(OrdersContract.class, SHIPMENT_DELIVERED).key(DeliveryConfirmed::carrierRef);

        // and a transport-style builder with a complete source
        FakeTransportBuilder fake =
                new FakeTransportBuilder("carriers", DeliveryConfirmed.class).dedupBy(body -> "D-" + body.carrierRef());
        fake.signal(OrdersContract.class, SHIPMENT_DELIVERED).key(DeliveryConfirmed::carrierRef);

        // When build is called twice on each
        SignalSourcePlan<DeliveryConfirmed> builtOnce = builder.build();
        SignalSourcePlan<DeliveryConfirmed> fakeOnce = fake.build();

        // Then the two calls return the same instance on each builder
        assertThat(builder.build()).isSameAs(builtOnce);
        assertThat(fake.build()).isSameAs(fakeOnce);

        // and a stage called after the first build fails, naming the source
        assertThatThrownBy(() -> builder.dedupBy(body -> "OTHER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carriers");
        assertThatThrownBy(() -> builder.signal(BillingContract.class, INVOICE_SETTLED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carriers");
        assertThatThrownBy(() -> builder.onNoMatch(SignalNoMatch.RETRY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carriers");
        assertThatThrownBy(() -> fake.dedupBy(body -> "OTHER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carriers");
        assertThatThrownBy(() -> fake.signal(BillingContract.class, INVOICE_SETTLED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carriers");
        assertThatThrownBy(() -> fake.onNoMatch(SignalNoMatch.RETRY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carriers");

        // and the built plans are unchanged: still the same instance, one signal, the first dedup key and no retry
        assertThat(builder.build()).isSameAs(builtOnce);
        assertThat(fake.build()).isSameAs(fakeOnce);
        assertDeliversAsBuilt(vertx, builtOnce);
        assertDeliversAsBuilt(vertx, fakeOnce);
    }

    /** One no-match answer: the original dedup key, one signal, and the default policy, which ignores it. */
    private static void assertDeliversAsBuilt(Vertx vertx, SignalSourcePlan<DeliveryConfirmed> plan) {
        try (DeliveryRig rig = DeliveryRig.over(vertx)) {
            rig.operationsRaising(new WorkflowSignalNoMatchException("orders", SHIPMENT_DELIVERED));

            SourceDisposition disposition =
                    rig.deliver(plan, message(new DeliveryConfirmed("PAY-77", "delivered")), envelope());

            assertThat(disposition.fate()).isEqualTo(Fate.DONE);
            assertThat(rig.signals()).hasSize(1);
            assertThat(rig.signals().get(0).dedupKey()).isEqualTo("D-PAY-77");
        }
    }

    private static Set<String> methodNames(Class<?> type) {
        return Arrays.stream(type.getMethods()).map(Method::getName).collect(Collectors.toSet());
    }
}
