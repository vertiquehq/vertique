// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.adapter;

import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import java.util.Objects;

/** Runtime-owned framework facade for constructing structured resilience pipelines. */
public final class ResilienceAdapterSupport {

    private final Resilience resilience;

    private ResilienceAdapterSupport(Resilience resilience) {
        this.resilience = Objects.requireNonNull(resilience, "resilience");
    }

    /**
     * Creates the support facade for the owning runtime.
     *
     * <p>This factory is used by {@link Resilience}; application code should obtain the facade
     * from that runtime instead of constructing adapter support directly.
     *
     * @param resilience owning runtime
     * @return runtime-owned adapter support
     */
    public static ResilienceAdapterSupport create(Resilience resilience) {
        return new ResilienceAdapterSupport(resilience);
    }

    /**
     * Constructs a pipeline from one structured adapter identity and resolved policy.
     *
     * @param identity structured adapter operation identity
     * @param policy complete resolved policy
     * @return executable timeout/retry pipeline
     * @throws IllegalStateException if the policy is empty
     * @throws dev.vertique.resilience.exception.ResiliencePolicyException if the policy contains
     *     an unsupported breaker or bulkhead concern
     */
    public ResiliencePipeline pipeline(AdapterOperationIdentity identity, ResolvedResiliencePolicy policy) {
        return resilience.adapterPipeline(
                Objects.requireNonNull(identity, "identity"), Objects.requireNonNull(policy, "policy"));
    }
}
