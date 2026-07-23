// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.deploy.SupervisionConfig;
import dev.vertique.deploy.VerticleSupervisor;
import dev.vertique.services.config.ServiceConfig;
import dev.vertique.services.config.ServicesConfig.ServiceKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Thin adapter over {@link VerticleSupervisor} that adds service-contract keying and
 * per-service supervision config loading.
 *
 * <p>Service contracts (identified by a {@link Class}) are mapped to deployment names used by
 * {@link VerticleSupervisor}. All restart, backoff, and availability logic is delegated to the
 * underlying supervisor; this class handles only the contract-to-deployment-name translation and
 * config loading.
 *
 * <p>Intentional undeploys are distinguished from crashes by calling {@link #deregister(Class)}
 * before undeploying, which removes the contract mapping and tells the underlying supervisor to
 * suppress restart attempts.
 *
 * <p>Client proxies check availability via {@link #isAvailable(Class)}; health checks enumerate
 * per-service statuses via {@link #serviceAvailability()}.
 */
@Slf4j
public class ServiceSupervisor {

    // --- Nested Types ---

    /**
     * Maps a service contract to the deployer name and human-readable service name.
     *
     * @param deploymentName the deployer deployment name used with {@link VerticleSupervisor}
     * @param name the human-readable service name from {@link ServiceContract#value()}
     */
    private record ContractMapping(String deploymentName, String name) {}

    // --- Fields ---

    private final VerticleSupervisor supervisor;
    private final Map<ServiceKey, ServiceConfig> serviceConfigIndex;
    private final Map<Class<?>, ContractMapping> contracts = new ConcurrentHashMap<>();

    /**
     * Creates a new supervisor adapter.
     *
     * @param supervisor the underlying verticle supervisor that handles restart logic
     * @param serviceConfigIndex the {@code (namespace, name) -> ServiceConfig} index supplying
     *     per-service supervision overrides
     */
    public ServiceSupervisor(VerticleSupervisor supervisor, Map<ServiceKey, ServiceConfig> serviceConfigIndex) {
        this.supervisor = supervisor;
        this.serviceConfigIndex = serviceConfigIndex;
    }

    // --- Lifecycle ---

    /**
     * Starts watching a newly deployed service by registering with the underlying supervisor.
     *
     * <p>Called by {@link ServiceDeploymentManager} after each successful deployment. Loads
     * per-service supervision configuration (falling back to defaults), records the
     * contract-to-deployment-name mapping, and delegates to
     * {@link VerticleSupervisor#supervise(String, String, SupervisionConfig, Runnable)}.
     *
     * @param contract the contract interface identifying the service
     * @param namespace the service namespace segment from {@link ServiceContract#namespace()}
     * @param name the human-readable service name from {@link ServiceContract#value()}
     * @param deploymentName the deployer name as registered with {@link VerticleSupervisor}
     * @param deploymentId the Vert.x deployment ID returned by the deployer
     * @param redeployAction the action to redeploy the service verticle on restart
     */
    public void watch(
            Class<?> contract,
            String namespace,
            String name,
            String deploymentName,
            String deploymentId,
            Runnable redeployAction) {
        SupervisionConfig sc = loadConfig(namespace, name);
        // Call supervise first — if coherence check throws, no stale mapping is left behind
        supervisor.supervise(deploymentName, deploymentId, sc, redeployAction);
        contracts.put(contract, new ContractMapping(deploymentName, name));
    }

    /**
     * Deregisters a service before intentional undeploy, suppressing any restart attempt.
     *
     * <p>Must be called before triggering the actual undeploy to distinguish intentional
     * shutdown from an unexpected crash.
     *
     * @param contract the contract interface to deregister
     */
    public void deregister(Class<?> contract) {
        ContractMapping mapping = contracts.remove(contract);
        if (mapping != null) {
            supervisor.unsupervise(mapping.deploymentName());
        }
    }

    /**
     * Returns {@code true} if the service is available.
     *
     * <p>Returns {@code false} while a restart is in progress or after the restart budget has
     * been exhausted. Returns {@code true} for unregistered services (fail-open).
     *
     * @param contract the contract interface to check
     * @return {@code true} if the service is available or not yet registered
     */
    public boolean isAvailable(Class<?> contract) {
        ContractMapping mapping = contracts.get(contract);
        if (mapping == null) {
            return true; // fail-open
        }
        return supervisor.isAvailable(mapping.deploymentName());
    }

    /**
     * Returns a snapshot of the availability status of all supervised services.
     *
     * <p>The map keys are service names (from {@link ServiceContract#value()}) and values
     * indicate whether the service is currently available. Services that are restarting or have
     * exhausted their restart budget will show as unavailable.
     *
     * @return an unmodifiable map of service name to availability status; empty if no services
     *     are supervised
     */
    public Map<String, Boolean> serviceAvailability() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        contracts.forEach(
                (contract, mapping) -> result.put(mapping.name(), supervisor.isAvailable(mapping.deploymentName())));
        return Collections.unmodifiableMap(result);
    }

    // --- Error Reporting ---

    /**
     * Reports a fatal error from dispatch and delegates to the underlying supervisor.
     *
     * <p>If the contract is not registered, this call is silently ignored.
     *
     * @param contract the contract interface of the service that encountered the error
     * @param error the fatal error
     */
    public void reportFatalError(Class<?> contract, Throwable error) {
        ContractMapping mapping = contracts.get(contract);
        if (mapping == null) {
            return;
        }
        supervisor.reportFatalError(mapping.deploymentName(), error);
    }

    /**
     * Reports a failed redeploy and delegates to the underlying supervisor to clear the
     * restart-in-progress flag and schedule another attempt if the budget allows.
     *
     * <p>If the contract is not registered, this call is silently ignored.
     *
     * @param contract the contract interface of the service whose redeploy failed
     * @param cause the cause of the redeploy failure
     */
    public void reportRedeployFailure(Class<?> contract, Throwable cause) {
        ContractMapping mapping = contracts.get(contract);
        if (mapping == null) {
            return;
        }
        supervisor.reportRedeployFailure(mapping.deploymentName(), cause);
    }

    // --- Helpers ---

    /**
     * Loads per-service supervision configuration from the typed services index.
     *
     * <p>Config path: {@code services.contracts.{namespace}.{name}.supervision.{field}}. The
     * {@link ServiceConfig#supervision()} record is already defaulted per field at parse time (its
     * {@code @JsonCreator} fills missing fields from {@link SupervisionConfig#DEFAULT}), so a service
     * with no supervision block carries {@link SupervisionConfig#DEFAULT}. A service absent from the
     * index falls back to {@link SupervisionConfig#DEFAULT}.
     *
     * @param namespace the service namespace segment
     * @param name the service name segment
     * @return the resolved supervision configuration
     */
    SupervisionConfig loadConfig(String namespace, String name) {
        ServiceConfig serviceConfig = serviceConfigIndex.get(new ServiceKey(namespace, name));
        if (serviceConfig == null) {
            return SupervisionConfig.DEFAULT;
        }
        return serviceConfig.supervision();
    }
}
