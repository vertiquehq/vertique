// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.service;

import dev.vertique.examples.workflow.order.command.AuthorizePayment;
import dev.vertique.examples.workflow.order.command.CreateShipment;
import dev.vertique.examples.workflow.order.command.ReleaseInventory;
import dev.vertique.examples.workflow.order.command.ReserveInventory;
import dev.vertique.examples.workflow.order.command.ScreenFraud;
import dev.vertique.examples.workflow.order.command.VoidAuthorization;
import dev.vertique.services.ServiceContractContributor;
import dev.vertique.services.ServiceContractEntries;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Method;
import java.util.List;

/**
 * {@link ServiceContractContributor} that registers all stub service implementations for the
 * order-fulfillment integration tests.
 *
 * <p>Registered services and their stable target ids:
 * <ul>
 *   <li>{@code inventory.reserve} — {@link StubInventoryService#reserve(ReserveInventory)}</li>
 *   <li>{@code inventory.release} — {@link StubInventoryService#release(ReleaseInventory)}</li>
 *   <li>{@code payment.authorize} — {@link StubPaymentService#authorize(AuthorizePayment)}</li>
 *   <li>{@code payment.void-authorization} —
 *       {@link StubPaymentService#voidAuthorization(VoidAuthorization)}</li>
 *   <li>{@code shipping.create-shipment} —
 *       {@link StubShippingService#createShipment(CreateShipment)}</li>
 *   <li>{@code fraud.screen} — {@link StubFraudService#screen(ScreenFraud)}</li>
 * </ul>
 *
 * <p>Each service type matches the target id prefix used in the workflow definitions
 * ({@link dev.vertique.examples.workflow.order.OrderFulfillmentDefinition} and
 * {@link dev.vertique.examples.workflow.order.OrderFulfillmentFanOutDefinition}).
 */
@Singleton
public final class StubServicesContributor implements ServiceContractContributor {

    // --- Reflective method handles resolved once at class-load ---

    private static final Method INVENTORY_RESERVE;
    private static final Method INVENTORY_RELEASE;
    private static final Method PAYMENT_AUTHORIZE;
    private static final Method PAYMENT_VOID;
    private static final Method SHIPPING_CREATE;
    private static final Method FRAUD_SCREEN;

    static {
        try {
            INVENTORY_RESERVE = StubInventoryService.class.getDeclaredMethod("reserve", ReserveInventory.class);
            INVENTORY_RELEASE = StubInventoryService.class.getDeclaredMethod("release", ReleaseInventory.class);
            PAYMENT_AUTHORIZE = StubPaymentService.class.getDeclaredMethod("authorize", AuthorizePayment.class);
            PAYMENT_VOID = StubPaymentService.class.getDeclaredMethod("voidAuthorization", VoidAuthorization.class);
            SHIPPING_CREATE = StubShippingService.class.getDeclaredMethod("createShipment", CreateShipment.class);
            FRAUD_SCREEN = StubFraudService.class.getDeclaredMethod("screen", ScreenFraud.class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final StubInventoryService inventoryService;
    private final StubPaymentService paymentService;
    private final StubShippingService shippingService;
    private final StubFraudService fraudService;

    /**
     * Creates a new contributor.
     *
     * @param inventoryService the stub inventory service instance
     * @param paymentService   the stub payment service instance
     * @param shippingService  the stub shipping service instance
     * @param fraudService     the stub fraud-screening service instance
     */
    @Inject
    public StubServicesContributor(
            StubInventoryService inventoryService,
            StubPaymentService paymentService,
            StubShippingService shippingService,
            StubFraudService fraudService) {
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
        this.shippingService = shippingService;
        this.fraudService = fraudService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns six contract entries — two for inventory, two for payment, one for shipping,
     * and one for fraud screening.
     *
     * @param config root application configuration; deployment options are read from it
     * @return list of contract entries for all stub services
     */
    @Override
    public List<ServiceContractRegistry.ContractEntry<?>> contribute(JsonObject config) {
        return List.of(
                // --- InventoryService ---
                ServiceContractEntries.deployable()
                        .contract(InventoryService.class)
                        .serviceInstance(inventoryService)
                        .name("inventory")
                        .operation("reserve")
                        .method(INVENTORY_RESERVE)
                        .payloadType(ReserveInventory.class)
                        .returnType(Void.class)
                        .param("payload", ParamSource.PAYLOAD, ReserveInventory.class)
                        .done()
                        .operation("release")
                        .method(INVENTORY_RELEASE)
                        .payloadType(ReleaseInventory.class)
                        .returnType(Void.class)
                        .param("payload", ParamSource.PAYLOAD, ReleaseInventory.class)
                        .done()
                        .deploymentOptions(config, "services", "inventory")
                        .build(),
                // --- PaymentService ---
                ServiceContractEntries.deployable()
                        .contract(PaymentService.class)
                        .serviceInstance(paymentService)
                        .name("payment")
                        .operation("authorize")
                        .method(PAYMENT_AUTHORIZE)
                        .payloadType(AuthorizePayment.class)
                        .returnType(Void.class)
                        .param("payload", ParamSource.PAYLOAD, AuthorizePayment.class)
                        .done()
                        .operation("void-authorization")
                        .method(PAYMENT_VOID)
                        .payloadType(VoidAuthorization.class)
                        .returnType(Void.class)
                        .param("payload", ParamSource.PAYLOAD, VoidAuthorization.class)
                        .done()
                        .deploymentOptions(config, "services", "payment")
                        .build(),
                // --- ShippingService ---
                ServiceContractEntries.deployable()
                        .contract(ShippingService.class)
                        .serviceInstance(shippingService)
                        .name("shipping")
                        .operation("create-shipment")
                        .method(SHIPPING_CREATE)
                        .payloadType(CreateShipment.class)
                        .returnType(Void.class)
                        .param("payload", ParamSource.PAYLOAD, CreateShipment.class)
                        .done()
                        .deploymentOptions(config, "services", "shipping")
                        .build(),
                // --- FraudService ---
                ServiceContractEntries.deployable()
                        .contract(StubFraudService.class)
                        .serviceInstance(fraudService)
                        .name("fraud")
                        .operation("screen")
                        .method(FRAUD_SCREEN)
                        .payloadType(ScreenFraud.class)
                        .returnType(Void.class)
                        .param("payload", ParamSource.PAYLOAD, ScreenFraud.class)
                        .done()
                        .deploymentOptions(config, "services", "fraud")
                        .build());
    }
}
