// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import dev.vertique.core.util.GeneratedCompanions;
import dev.vertique.job.delayed.config.DelayedJobContractConfig;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * Factory for obtaining proxies that implement typed {@link DelayedJobClient} contract interfaces.
 *
 * <p>Callers inject this factory and call {@link #create(Class)} to obtain a proxy for a specific
 * contract interface. When the {@code vertique-codegen-delayed-job} annotation processor has
 * generated a static {@code {Contract}_DelayedJobProxy} for the contract, that zero-reflection proxy
 * is used; otherwise the factory falls back to a JDK dynamic proxy ({@link DelayedJobClientProxy}).
 * Both routes behave identically. The proxy routes all {@code enqueue} calls to
 * {@link DelayedJobService},
 * applying defaults from {@link DelayedJobContract} (or their config overrides) and merging
 * any per-call {@link DelayedJobOptions}.
 *
 * <p>Effective configuration priority (highest to lowest):
 * <ol>
 *   <li>Per-enqueue {@link DelayedJobOptions}</li>
 *   <li>Application config under {@code delayedJob.contracts.{name}.*}</li>
 *   <li>{@link DelayedJobContract} annotation defaults</li>
 * </ol>
 *
 * <p>Example:
 * <pre>{@code
 * @Inject DelayedJobClientFactory factory;
 *
 * DeliverWebhookJob client = factory.create(DeliverWebhookJob.class);
 * client.enqueue(new WebhookPayload("pay_123"));
 * }</pre>
 *
 * @see DelayedJobClient
 * @see DelayedJobContract
 * @see DelayedJobClientProxy
 */
@Singleton
public class DelayedJobClientFactory {

    private static final String PROXY_SUFFIX = "_DelayedJobProxy";

    private final DelayedJobService jobService;
    private final Map<String, DelayedJobContractConfig> contractConfigs;

    /**
     * Creates a new factory.
     *
     * @param jobService      the delayed job service used by generated proxies to enqueue jobs
     * @param contractConfigs the typed per-contract override index (keyed by contract name), parsed
     *                        and validated at the {@code DelayedJobModule} boundary; the factory never
     *                        sees the raw root config
     */
    @Inject
    public DelayedJobClientFactory(
            DelayedJobService jobService, Map<String, DelayedJobContractConfig> contractConfigs) {
        this.jobService = jobService;
        this.contractConfigs = contractConfigs;
    }

    /**
     * Creates a JDK dynamic proxy for the given {@link DelayedJobClient} contract interface.
     *
     * <p>The contract interface must:
     * <ul>
     *   <li>Be an interface (not a class or abstract class).</li>
     *   <li>Extend {@link DelayedJobClient}.</li>
     *   <li>Be annotated with {@link DelayedJobContract}.</li>
     * </ul>
     *
     * @param <T>               the contract interface type
     * @param contractInterface the contract interface class token
     * @return a proxy implementing {@code T} that routes enqueue calls to {@link DelayedJobService}
     * @throws IllegalArgumentException if the interface does not meet the requirements above
     */
    @SuppressWarnings("unchecked")
    public <T extends DelayedJobClient<?>> T create(Class<T> contractInterface) {
        if (!contractInterface.isInterface()) {
            throw new IllegalArgumentException(
                    "DelayedJobClient contract must be an interface: " + contractInterface.getName());
        }

        DelayedJobContract annotation = contractInterface.getAnnotation(DelayedJobContract.class);
        if (annotation == null) {
            throw new IllegalArgumentException(
                    contractInterface.getName() + " must be annotated with @DelayedJobContract");
        }

        JsonObject contractConfig = resolveContractConfig(annotation);

        // Prefer the generated static proxy when present (zero reflection); fall back to the JDK
        // dynamic proxy otherwise. A present-but-broken generated class fails loudly. See ADR-0070.
        return GeneratedCompanions.instantiate(
                        contractInterface,
                        PROXY_SUFFIX,
                        new Class<?>[] {DelayedJobService.class, DelayedJobContract.class, JsonObject.class},
                        new Object[] {jobService, annotation, contractConfig},
                        (fqn, e) -> new IllegalStateException(
                                "Generated proxy %s is present but could not be instantiated".formatted(fqn), e))
                .orElseGet(() -> (T) Proxy.newProxyInstance(
                        contractInterface.getClassLoader(),
                        new Class<?>[] {contractInterface},
                        new DelayedJobClientProxy(jobService, annotation, contractConfig)));
    }

    /**
     * Resolves the per-contract config overrides for the given contract from the typed override
     * index.
     *
     * <p>Looks up {@code contractConfigs} by the annotation's {@code name}. When the contract has a
     * typed override entry, only the operator-set keys are rendered (via
     * {@link DelayedJobContractConfig#toContractConfigJson()}); absent overrides are omitted so the
     * proxy falls back to the annotation value per field. When no entry is present, an empty
     * {@link JsonObject} is returned so the proxy uses annotation defaults throughout.
     *
     * @param annotation the contract annotation providing the name key
     * @return the per-contract override config containing only operator-set keys; never {@code null}
     */
    private JsonObject resolveContractConfig(DelayedJobContract annotation) {
        DelayedJobContractConfig contractConfig = contractConfigs.get(annotation.name());
        return contractConfig != null ? contractConfig.toContractConfigJson() : new JsonObject();
    }
}
