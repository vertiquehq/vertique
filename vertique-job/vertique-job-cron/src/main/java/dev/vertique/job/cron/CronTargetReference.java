// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

/**
 * A sealed type hierarchy representing the target of a cron job dispatch.
 *
 * <p>Cron jobs can target either a registered service operation (resolved at dispatch time via
 * the {@link dev.vertique.services.ServiceTargetResolver}) or a raw event bus address. The
 * sealed hierarchy makes the two cases explicit and exhaustively pattern-matchable.
 *
 * <p>Canonical string forms:
 * <ul>
 *   <li>{@code "service:<stableTargetId>"} — e.g. {@code "service:reporting.report-service.generate"}</li>
 *   <li>{@code "eventbus:<address>"} — e.g. {@code "eventbus:myapp/reporting/generateReport"}</li>
 * </ul>
 *
 * <p>Use {@link #parse(String)} to deserialize a canonical string (e.g., from config or the
 * {@code job_schedules} table). Use {@link #toCanonical()} to serialize for storage or logging.
 */
public sealed interface CronTargetReference {

    /**
     * Returns the canonical string representation of this target reference.
     *
     * @return canonical form, e.g. {@code "service:my.op"} or {@code "eventbus:some/address"}
     */
    String toCanonical();

    /**
     * Parses a canonical target reference string into the appropriate {@link CronTargetReference}
     * variant.
     *
     * <p>Supported schemes:
     * <ul>
     *   <li>{@code "service:<stableTargetId>"} → {@link ServiceTarget}</li>
     *   <li>{@code "eventbus:<address>"} → {@link EventBusTarget}</li>
     * </ul>
     *
     * @param value the canonical target reference string to parse; must not be {@code null}
     * @return the parsed {@link CronTargetReference}
     * @throws IllegalArgumentException if the value is blank, has an unknown scheme, or the
     *                                  scheme-specific part is blank
     */
    static CronTargetReference parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Target reference must not be blank");
        }
        if (value.startsWith("service:")) {
            String stableTargetId = value.substring("service:".length());
            if (stableTargetId.isBlank()) {
                throw new IllegalArgumentException(
                        "service: target reference must specify a non-blank stable target id");
            }
            return new ServiceTarget(stableTargetId);
        }
        if (value.startsWith("eventbus:")) {
            String address = value.substring("eventbus:".length());
            if (address.isBlank()) {
                throw new IllegalArgumentException("eventbus: target reference must specify a non-blank address");
            }
            return new EventBusTarget(address);
        }
        throw new IllegalArgumentException(
                "Unknown target reference scheme in '" + value + "' — expected 'service:' or 'eventbus:'");
    }

    // --- Variants ---

    /**
     * A cron target that references a registered service operation by its stable target id.
     *
     * <p>The runtime event bus address is resolved at dispatch time via
     * {@link dev.vertique.services.ServiceTargetResolver#resolve(String)}, decoupling
     * the persisted reference from the mutable transport address.
     *
     * @param stableTargetId durable dot-delimited identity of the target service operation
     *                       (e.g. {@code "reporting.report-service.generate"})
     */
    record ServiceTarget(String stableTargetId) implements CronTargetReference {

        /**
         * {@inheritDoc}
         *
         * @return {@code "service:<stableTargetId>"}
         */
        @Override
        public String toCanonical() {
            return "service:" + stableTargetId;
        }
    }

    /**
     * A cron target that references a raw event bus address directly.
     *
     * <p>The address is used verbatim at dispatch time. This variant is appropriate for
     * config-only jobs that target an arbitrary address not backed by a registered service
     * contract.
     *
     * @param address the event bus address to dispatch to (e.g. {@code "myapp/reporting/generateReport"})
     */
    record EventBusTarget(String address) implements CronTargetReference {

        /**
         * {@inheritDoc}
         *
         * @return {@code "eventbus:<address>"}
         */
        @Override
        public String toCanonical() {
            return "eventbus:" + address;
        }
    }
}
