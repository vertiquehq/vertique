// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.cron.processor;

/**
 * Fully-qualified annotation names referenced by the cron processor and its validators.
 * Centralised here so a relocation in another module shows up as one edit, not five.
 */
public final class CronAnnotations {

    /** {@code dev.vertique.job.cron.CronJob}. */
    public static final String CRON_JOB = "dev.vertique.job.cron.CronJob";

    /** {@code dev.vertique.services.ServiceHandler}. */
    public static final String SERVICE_HANDLER = "dev.vertique.services.ServiceHandler";

    /** {@code dev.vertique.services.ServiceContract}. */
    public static final String SERVICE_CONTRACT = "dev.vertique.services.ServiceContract";

    /** {@code dev.vertique.services.ServiceOperation}. */
    public static final String SERVICE_OPERATION = "dev.vertique.services.ServiceOperation";

    private CronAnnotations() {}
}
