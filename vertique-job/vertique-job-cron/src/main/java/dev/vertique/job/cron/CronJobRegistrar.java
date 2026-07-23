// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import dev.vertique.job.CronJobSchedule;
import dev.vertique.job.JobRepository;
import dev.vertique.job.cron.config.CronConfig;
import dev.vertique.job.cron.config.CronJobConfig;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceTargetResolver;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Scans all registered service implementations for methods annotated with {@link CronJob} and
 * registers them with the {@link CronScheduler}.
 *
 * <p>Called once at startup. Validates that:
 * <ul>
 *   <li>The {@code @CronJob} annotation is on the <em>implementation</em> method (not the
 *       contract interface).</li>
 *   <li>The annotated method's operation exists in the service contract registry.</li>
 *   <li>The cron expression is valid.</li>
 *   <li>The timezone ID is valid.</li>
 *   <li>No duplicate job IDs are present.</li>
 *   <li>{@link ExecutionMode#SINGLE_INSTANCE} is only used when a {@link JobRepository} is
 *       available and the overlap policy is {@link OverlapPolicy#SKIP}.</li>
 * </ul>
 *
 * <p>Configuration overrides are supplied via the typed {@link CronConfig} (parsed from the
 * {@code cron} subtree at the module boundary). All per-job keys are optional — if not present, the
 * annotation's value is used:
 * <pre>{@code
 * {
 *   "cron": {
 *     "tracked": true,
 *     "jobs": {
 *       "my-job-id": {
 *         "enabled": false,
 *         "cron": "0 0 9 * * *",
 *         "timezone": "Europe/Helsinki",
 *         "mode": "EVERY_INSTANCE",
 *         "overlapPolicy": "QUEUE_ONE",
 *         "tracked": false
 *       }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Config-only jobs reference a target using the {@code target} field:
 * <pre>{@code
 * {
 *   "cron": {
 *     "jobs": {
 *       "report-weekly": {
 *         "target": "service:reporting.report-service.generate",
 *         "cron": "0 0 9 * * 1",
 *         "timezone": "Europe/Helsinki",
 *         "parameters": { "days": 30, "format": "pdf" }
 *       },
 *       "report-monthly": {
 *         "target": "eventbus:myapp/reporting/generateReport",
 *         "cron": "0 0 9 1 * *"
 *       }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Config key reference:
 * <ul>
 *   <li>{@code target} — target reference ({@code "service:..."} or {@code "eventbus:..."}); required
 *       for a config-only job (an entry with no {@code target} and no matching annotation is skipped)
 *   <li>{@code enabled} — {@code false} to disable the job entirely (default: {@code true})
 *   <li>{@code cron} — override the cron expression from the annotation
 *   <li>{@code timezone} — override the timezone (IANA zone ID, e.g., {@code "Europe/Helsinki"})
 *   <li>{@code mode} — override the execution mode ({@code "EVERY_INSTANCE"} or
 *       {@code "SINGLE_INSTANCE"})
 *   <li>{@code overlapPolicy} — override the overlap policy ({@code "SKIP"} or {@code "QUEUE_ONE"})
 *   <li>{@code tracked} — override whether executions are persisted (default: global
 *       {@code cron.tracked} setting)
 *   <li>{@code maxAttempts} — maximum attempts per execution (config-only jobs only; annotated jobs
 *       use the annotation's {@code maxAttempts})
 *   <li>{@code parameters} — key-value metadata passed to the handler (config-only jobs)
 * </ul>
 */
@Slf4j
public class CronJobRegistrar {

    private final CronScheduler scheduler;
    private final ServiceContractRegistry registry;
    private final CronConfig cronConfig;
    private final Map<String, CronJobConfig> jobIndex;

    /**
     * Optional repository for persisting schedule definitions and enabling SINGLE_INSTANCE mode.
     * May be {@code null} for in-memory-only operation.
     */
    private final JobRepository repository;

    /**
     * Creates a new registrar.
     *
     * @param scheduler      the scheduler to register discovered jobs with
     * @param registry       the service contract registry to scan for {@link CronJob} annotations
     * @param targetResolver the service target resolver; accepted for API symmetry with
     *                       {@link CronJobDispatcher} but target id derivation for annotation jobs
     *                       uses {@link ServiceMethodMeta#stableTargetId()} directly
     * @param cronConfig     the typed cron configuration (parsed from the {@code cron} subtree at the
     *                       module boundary)
     * @param repository     optional job repository for schedule persistence and SINGLE_INSTANCE
     *                       mode; may be {@code null} for in-memory-only operation
     */
    public CronJobRegistrar(
            CronScheduler scheduler,
            ServiceContractRegistry registry,
            @SuppressWarnings("unused") ServiceTargetResolver targetResolver,
            CronConfig cronConfig,
            JobRepository repository) {
        this.scheduler = scheduler;
        this.registry = registry;
        this.cronConfig = cronConfig;
        this.jobIndex = cronConfig.jobIndex();
        this.repository = repository;
    }

    /**
     * Scans all service implementations for {@link CronJob} annotations and registers
     * discovered jobs with the {@link CronScheduler}. After all annotation-discovered jobs are
     * processed, also registers any config-only jobs (entries in {@code cron.jobs.*} that carry a
     * {@code target} and have no matching annotation). If a {@link JobRepository} is available,
     * persists all schedule definitions for dashboard visibility.
     *
     * <p>Transactional: validation happens first, registration second. If any violation is
     * collected during validation the scheduler is left untouched and a
     * {@link CronRegistrationException} is thrown — there is no partial-registration state
     * for callers to undo.
     *
     * @throws CronRegistrationException if any validation violations are found
     */
    public void scan() {
        List<String> violations = new ArrayList<>();
        Set<String> registeredIds = new HashSet<>();
        // Validated definitions queued for registration; drained into the scheduler only if
        // the violation list is empty.
        List<PendingRegistration> pending = new ArrayList<>();

        boolean globalTracked = cronConfig.tracked();

        // --- Annotation-discovered jobs ---
        for (ServiceContractRegistry.ContractEntry<?> contractEntry : registry.entries()) {
            // Validate that @CronJob is not placed on contract interface methods
            for (Method ifaceMethod : contractEntry.contract().getMethods()) {
                if (ifaceMethod.getDeclaringClass() == Object.class) {
                    continue;
                }
                if (ifaceMethod.isAnnotationPresent(CronJob.class)) {
                    violations.add("Place @CronJob on the implementation method, not the contract interface: "
                            + contractEntry.contract().getSimpleName() + "." + ifaceMethod.getName() + "()");
                }
            }

            // Scan implementation methods
            Object impl = contractEntry.serviceInstance();
            Class<?> implClass = impl.getClass();
            for (Method implMethod : implClass.getDeclaredMethods()) {
                CronJob annotation = implMethod.getAnnotation(CronJob.class);
                if (annotation == null) {
                    continue;
                }

                String jobId = annotation.id();
                if (jobId == null || jobId.isBlank()) {
                    violations.add("@CronJob on " + implClass.getSimpleName() + "." + implMethod.getName()
                            + "() has blank id");
                    continue;
                }

                // Check for duplicate IDs
                if (registeredIds.contains(jobId)) {
                    violations.add("Duplicate @CronJob id '" + jobId + "' found on " + implClass.getSimpleName() + "."
                            + implMethod.getName() + "()");
                    continue;
                }

                // Per-job config override (may be absent → null)
                CronJobConfig jobConfig = jobIndex.get(jobId);

                // Check config: enabled?
                boolean enabled = jobConfig == null || jobConfig.enabled();
                if (!enabled) {
                    log.info("Cron job '{}' is disabled via config — skipping", jobId);
                    continue;
                }

                // Find the service method meta for this implementation method
                ServiceMethodMeta meta = findMetaForMethod(contractEntry, implMethod);
                if (meta == null) {
                    violations.add("@CronJob on " + implClass.getSimpleName() + "." + implMethod.getName()
                            + "() does not correspond to any registered operation on contract "
                            + contractEntry.contract().getSimpleName());
                    continue;
                }

                // Resolve cron expression (config override or annotation)
                String cronExpr = override(jobConfig != null ? jobConfig.cron() : null, annotation.cron());
                if (cronExpr == null || cronExpr.isBlank()) {
                    violations.add("@CronJob '" + jobId + "' has blank cron expression");
                    continue;
                }

                CronExpression parsedCron;
                try {
                    parsedCron = new CronExpression(cronExpr);
                } catch (IllegalArgumentException e) {
                    violations.add("@CronJob '" + jobId + "' has invalid cron expression '" + cronExpr + "': "
                            + e.getMessage());
                    continue;
                }

                // Resolve timezone (config override or annotation)
                String timezoneStr = override(jobConfig != null ? jobConfig.timezone() : null, annotation.timezone());
                ZoneId timezone;
                try {
                    timezone = ZoneId.of(timezoneStr);
                } catch (Exception e) {
                    violations.add("@CronJob '" + jobId + "' has invalid timezone '" + timezoneStr + "'");
                    continue;
                }

                // Resolve execution mode (config override or annotation)
                ExecutionMode mode;
                String modeStr = jobConfig != null ? jobConfig.mode() : null;
                if (modeStr != null) {
                    try {
                        mode = ExecutionMode.valueOf(modeStr.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        violations.add("@CronJob '" + jobId + "' has invalid mode in config: '" + modeStr
                                + "' (expected EVERY_INSTANCE or SINGLE_INSTANCE)");
                        continue;
                    }
                } else {
                    mode = annotation.mode();
                }

                // Resolve overlap policy (config override or annotation)
                OverlapPolicy overlapPolicy;
                String overlapPolicyStr = jobConfig != null ? jobConfig.overlapPolicy() : null;
                if (overlapPolicyStr != null) {
                    try {
                        overlapPolicy = OverlapPolicy.valueOf(overlapPolicyStr.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        violations.add("@CronJob '" + jobId + "' has invalid overlapPolicy in config: '"
                                + overlapPolicyStr + "' (expected SKIP or QUEUE_ONE)");
                        continue;
                    }
                } else {
                    overlapPolicy = annotation.overlapPolicy();
                }

                // Validate SINGLE_INSTANCE constraints
                if (mode == ExecutionMode.SINGLE_INSTANCE) {
                    if (repository == null) {
                        violations.add("@CronJob '" + jobId
                                + "': SINGLE_INSTANCE requires a JobRepository binding"
                                + " (include CronPersistenceModule instead of CronModule)");
                        continue;
                    }
                    if (overlapPolicy != OverlapPolicy.SKIP) {
                        violations.add("@CronJob '" + jobId
                                + "': SINGLE_INSTANCE requires SKIP overlap policy (QUEUE_ONE needs"
                                + " cross-node coordination which is not supported)");
                        continue;
                    }
                }

                // Resolve tracked flag
                // Precedence: per-job config > annotation > global config
                // SINGLE_INSTANCE is always tracked (required for leader election)
                boolean tracked;
                if (mode == ExecutionMode.SINGLE_INSTANCE) {
                    tracked = true;
                } else if (jobConfig != null && jobConfig.tracked() != null) {
                    tracked = jobConfig.tracked();
                } else if (!annotation.tracked().isEmpty()) {
                    tracked = Boolean.parseBoolean(annotation.tracked());
                } else {
                    tracked = globalTracked;
                }

                // Resolve misfire policy
                // Precedence: per-job config > annotation (adjusted for mode default)
                // Default: FIRE_NOW for SINGLE_INSTANCE, SKIP for EVERY_INSTANCE
                MisfirePolicy misfirePolicy = resolveMisfirePolicy(
                        jobId, jobConfig != null ? jobConfig.misfirePolicy() : null, annotation.misfirePolicy(), mode);

                // --- Resolve target reference ---
                // Annotated cron jobs must derive their target from service contract metadata
                // (FR-TGT-081). This requires @ServiceOperation on the service method so a
                // stable target id exists. If absent, reject at registration time rather than
                // silently degrading to an eventbus: target with a mutable address.
                String stableTargetId = meta.stableTargetId();
                if (stableTargetId == null) {
                    violations.add("@CronJob on "
                            + implClass.getSimpleName() + "." + implMethod.getName()
                            + "() requires the corresponding service contract method to have"
                            + " @ServiceOperation — cron jobs must have a stable service target");
                    continue;
                }
                CronTargetReference target = new CronTargetReference.ServiceTarget(stableTargetId);
                String handlerAddress = null;

                CronJobDefinition definition = new CronJobDefinition(
                        jobId,
                        parsedCron,
                        target,
                        handlerAddress,
                        mode,
                        timezone,
                        annotation.maxAttempts(),
                        null,
                        overlapPolicy,
                        tracked,
                        Map.of(),
                        misfirePolicy);

                registeredIds.add(jobId);
                pending.add(annotatedRegistration(
                        definition,
                        implClass,
                        implMethod,
                        target,
                        cronExpr,
                        mode,
                        overlapPolicy,
                        tracked,
                        misfirePolicy));
            }
        }

        // --- Config-only jobs (entries with a 'target' field not yet registered) ---
        for (CronJobConfig jobConfig : cronConfig.jobs()) {
            String configJobId = jobConfig.id();
            if (registeredIds.contains(configJobId)) {
                // Already registered via annotation — skip
                continue;
            }

            // An entry without a 'target' is only a config override for an (absent) annotation job
            // and should be skipped. The deprecated 'handler' field has been removed.
            if (jobConfig.target() == null) {
                continue;
            }

            boolean enabled = jobConfig.enabled();
            if (!enabled) {
                log.info("Config-only cron job '{}' is disabled — skipping", configJobId);
                continue;
            }

            // --- Resolve target reference ---
            String targetStr = jobConfig.target();
            if (targetStr.isBlank()) {
                violations.add("Config-only cron job '" + configJobId + "' has blank target");
                continue;
            }
            CronTargetReference target;
            try {
                target = CronTargetReference.parse(targetStr);
            } catch (IllegalArgumentException e) {
                violations.add("Config-only cron job '" + configJobId + "' has invalid target '" + targetStr + "': "
                        + e.getMessage());
                continue;
            }
            String handlerAddress = (target instanceof CronTargetReference.EventBusTarget et) ? et.address() : null;

            String cronExpr = jobConfig.cron();
            if (cronExpr == null || cronExpr.isBlank()) {
                violations.add("Config-only cron job '" + configJobId + "' has no cron expression");
                continue;
            }

            CronExpression parsedCron;
            try {
                parsedCron = new CronExpression(cronExpr);
            } catch (IllegalArgumentException e) {
                violations.add("Config-only cron job '" + configJobId + "' has invalid cron expression '" + cronExpr
                        + "': " + e.getMessage());
                continue;
            }

            String timezoneStr = jobConfig.timezone() != null ? jobConfig.timezone() : "UTC";
            ZoneId timezone;
            try {
                timezone = ZoneId.of(timezoneStr);
            } catch (Exception e) {
                violations.add("Config-only cron job '" + configJobId + "' has invalid timezone '" + timezoneStr + "'");
                continue;
            }

            ExecutionMode mode = ExecutionMode.EVERY_INSTANCE;
            String modeStr = jobConfig.mode();
            if (modeStr != null) {
                try {
                    mode = ExecutionMode.valueOf(modeStr.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    violations.add("Config-only cron job '" + configJobId + "' has invalid mode: '" + modeStr
                            + "' (expected EVERY_INSTANCE or SINGLE_INSTANCE)");
                    continue;
                }
            }

            OverlapPolicy overlapPolicy = OverlapPolicy.SKIP;
            String overlapPolicyStr = jobConfig.overlapPolicy();
            if (overlapPolicyStr != null) {
                try {
                    overlapPolicy = OverlapPolicy.valueOf(overlapPolicyStr.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    violations.add("Config-only cron job '" + configJobId + "' has invalid overlapPolicy: '"
                            + overlapPolicyStr + "' (expected SKIP or QUEUE_ONE)");
                    continue;
                }
            }

            // Validate SINGLE_INSTANCE constraints
            if (mode == ExecutionMode.SINGLE_INSTANCE) {
                if (repository == null) {
                    violations.add("Config-only cron job '" + configJobId
                            + "': SINGLE_INSTANCE requires a JobRepository binding");
                    continue;
                }
                if (overlapPolicy != OverlapPolicy.SKIP) {
                    violations.add(
                            "Config-only cron job '" + configJobId + "': SINGLE_INSTANCE requires SKIP overlap policy");
                    continue;
                }
            }

            int maxAttempts = jobConfig.maxAttempts();

            // Resolve tracked flag
            boolean tracked;
            if (mode == ExecutionMode.SINGLE_INSTANCE) {
                tracked = true;
            } else if (jobConfig.tracked() != null) {
                tracked = jobConfig.tracked();
            } else {
                tracked = globalTracked;
            }

            // Resolve parameters
            Map<String, Object> parameters = resolveParameters(jobConfig.parameters());

            // Resolve misfire policy — config-only jobs have no annotation, use mode-based default
            MisfirePolicy misfirePolicy = resolveMisfirePolicy(configJobId, jobConfig.misfirePolicy(), null, mode);

            CronJobDefinition definition = new CronJobDefinition(
                    configJobId,
                    parsedCron,
                    target,
                    handlerAddress,
                    mode,
                    timezone,
                    maxAttempts,
                    null,
                    overlapPolicy,
                    tracked,
                    parameters,
                    misfirePolicy);

            registeredIds.add(configJobId);
            pending.add(configRegistration(
                    definition, configJobId, target, cronExpr, mode, overlapPolicy, tracked, misfirePolicy));
        }

        if (!violations.isEmpty()) {
            // Fail fast — scheduler has not been touched yet, so no rollback is needed.
            throw new CronRegistrationException(violations);
        }

        // --- Validation passed: register every definition with the scheduler. ---
        for (PendingRegistration job : pending) {
            scheduler.register(job.definition());
            job.log().run();
        }

        // --- Persist schedule definitions to DB (fire-and-forget, best-effort) ---
        if (repository != null) {
            for (PendingRegistration job : pending) {
                persistSchedule(job.definition());
            }
        }
    }

    /**
     * Returns {@code override} when non-{@code null}, otherwise {@code annotationDefault}. Mirrors
     * the {@code jobConfig.getString(key, annotationDefault)} fall-through the raw-{@link JsonObject}
     * path used: a configured value wins, an absent ({@code null}) value falls back to the
     * annotation's value.
     *
     * @param override the per-job configured value, or {@code null} when absent
     * @param annotationDefault the annotation's value to fall back to
     * @return the override when present, else the annotation default
     */
    private static String override(String override, String annotationDefault) {
        return override != null ? override : annotationDefault;
    }

    /**
     * Pairs a validated {@link CronJobDefinition} with the log line to emit once the scheduler
     * accepts it. Built during validation; drained on the success path.
     */
    private record PendingRegistration(CronJobDefinition definition, Runnable log) {}

    private PendingRegistration annotatedRegistration(
            CronJobDefinition definition,
            Class<?> implClass,
            Method implMethod,
            CronTargetReference target,
            String cronExpr,
            ExecutionMode mode,
            OverlapPolicy overlapPolicy,
            boolean tracked,
            MisfirePolicy misfirePolicy) {
        Runnable logLine = () -> log.info(
                "Registered cron job '{}' → {}.{}() [{}] target={} cron='{}' tz={} overlapPolicy={}"
                        + " tracked={} misfirePolicy={}",
                definition.id(),
                implClass.getSimpleName(),
                implMethod.getName(),
                mode,
                target.toCanonical(),
                cronExpr,
                definition.timezone(),
                overlapPolicy,
                tracked,
                misfirePolicy);
        return new PendingRegistration(definition, logLine);
    }

    private PendingRegistration configRegistration(
            CronJobDefinition definition,
            String configJobId,
            CronTargetReference target,
            String cronExpr,
            ExecutionMode mode,
            OverlapPolicy overlapPolicy,
            boolean tracked,
            MisfirePolicy misfirePolicy) {
        Runnable logLine = () -> log.info(
                "Registered config-only cron job '{}' → {} [{}] cron='{}' tz={} overlapPolicy={}"
                        + " tracked={} misfirePolicy={}",
                configJobId,
                target.toCanonical(),
                mode,
                cronExpr,
                definition.timezone(),
                overlapPolicy,
                tracked,
                misfirePolicy);
        return new PendingRegistration(definition, logLine);
    }

    /**
     * Persists a cron job schedule definition to the repository for dashboard visibility.
     * This is a fire-and-forget operation — failures are logged as warnings but do not
     * prevent the job from running.
     *
     * <p>Wrapped in a top-level {@code try/catch (Exception)} so a synchronous throw from a custom
     * {@link JobRepository} (e.g. NPE, {@code IllegalStateException} from a closed pool) cannot
     * escape into {@link #scan()} and fail the lifecycle verticle's start promise — that would
     * leave the scheduler with all jobs registered but {@code start()} never called, opening the
     * partial-state window the lifecycle rollback is meant to close. {@code Error}s are not
     * caught: they indicate JVM-level problems and should propagate.
     *
     * @param def the cron job definition to persist
     */
    private void persistSchedule(CronJobDefinition def) {
        try {
            persistScheduleUnchecked(def);
        } catch (Exception t) {
            log.warn("Failed to persist schedule for '{}' (best-effort, ignored)", def.id(), t);
        }
    }

    private void persistScheduleUnchecked(CronJobDefinition def) {
        Instant nextFire;
        try {
            nextFire = def.cronExpression().computeNextFireTime(Instant.now(), def.timezone());
        } catch (Exception e) {
            log.warn("Failed to compute next fire time for '{}' — schedule will not be persisted", def.id(), e);
            return;
        }
        CronJobSchedule schedule = new CronJobSchedule(
                def.id(),
                def.cronExpression().expression(),
                def.handlerAddress(),
                def.target().toCanonical(),
                def.mode().name(),
                def.timezone().getId(),
                true,
                def.overlapPolicy().name(),
                def.maxAttempts(),
                def.tracked(),
                null,
                nextFire);
        repository
                .saveSchedule(schedule)
                .onFailure(err -> log.warn("Failed to persist schedule for '{}': {}", def.id(), err.getMessage()));
    }

    /**
     * Finds the {@link ServiceMethodMeta} for the given implementation method by looking up
     * the operation by method name.
     *
     * @param contractEntry the contract entry to search
     * @param implMethod    the implementation method to match
     * @return the matching meta, or {@code null} if not found
     */
    private ServiceMethodMeta findMetaForMethod(
            ServiceContractRegistry.ContractEntry<?> contractEntry, Method implMethod) {
        // Try direct lookup by method name (operation name)
        ServiceMethodMeta meta = contractEntry.operations().get(implMethod.getName());
        if (meta != null) {
            return meta;
        }
        // Fall back to scanning all operations for a handler method name match
        for (ServiceMethodMeta m : contractEntry.operations().values()) {
            if (m.method().name().equals(implMethod.getName())) {
                return m;
            }
        }
        return null;
    }

    /**
     * Resolves the effective {@link MisfirePolicy} for a job.
     *
     * <p>Precedence (highest to lowest):
     * <ol>
     *   <li>Per-job config: {@code cron.jobs.<id>.misfirePolicy}</li>
     *   <li>Annotation value (when not the default {@link MisfirePolicy#FIRE_NOW} and the mode
     *       would otherwise select a different default)</li>
     *   <li>Mode-based default: {@link MisfirePolicy#FIRE_NOW} for
     *       {@link ExecutionMode#SINGLE_INSTANCE}, {@link MisfirePolicy#SKIP} for
     *       {@link ExecutionMode#EVERY_INSTANCE}</li>
     * </ol>
     *
     * <p>For config-only jobs (no annotation), pass {@code null} for {@code annotationValue}.
     *
     * @param jobId           the job identifier (used for error messages)
     * @param configValue     the per-job configured misfire policy string, or {@code null} when absent
     * @param annotationValue the annotation's misfire policy value, or {@code null} for config-only jobs
     * @param mode            the resolved execution mode
     * @return the effective misfire policy
     */
    private MisfirePolicy resolveMisfirePolicy(
            String jobId, String configValue, MisfirePolicy annotationValue, ExecutionMode mode) {
        // 1. Config override takes highest precedence
        if (configValue != null) {
            try {
                return MisfirePolicy.valueOf(configValue.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                log.warn(
                        "Cron job '{}' has invalid misfirePolicy in config: '{}' (expected FIRE_NOW, SKIP, or FIRE_ALL)"
                                + " — using mode-based default",
                        jobId,
                        configValue);
            }
        }

        // 2. Annotation value (skip if null — config-only job)
        if (annotationValue != null) {
            // The annotation default is FIRE_NOW. For EVERY_INSTANCE, if the user explicitly
            // set FIRE_NOW on the annotation, respect it. If it equals the annotation default
            // and the mode is EVERY_INSTANCE, apply the mode-based default (SKIP) instead.
            // We cannot tell if the user explicitly wrote FIRE_NOW or just left it as the default,
            // so we apply the mode-based default for EVERY_INSTANCE when annotationValue == FIRE_NOW
            // (which is the Java default). This gives the expected UX: EVERY_INSTANCE jobs default
            // to SKIP, SINGLE_INSTANCE default to FIRE_NOW.
            if (mode == ExecutionMode.EVERY_INSTANCE && annotationValue == MisfirePolicy.FIRE_NOW) {
                return MisfirePolicy.SKIP;
            }
            return annotationValue;
        }

        // 3. Mode-based default
        return mode == ExecutionMode.SINGLE_INSTANCE ? MisfirePolicy.FIRE_NOW : MisfirePolicy.SKIP;
    }

    /**
     * Converts a {@code parameters} {@link JsonObject} (config-only jobs) to a
     * {@code Map<String, Object>}. Returns an empty map when there are no parameters.
     *
     * @param parametersObj the job's parameters object (never {@code null}; may be empty)
     * @return the parameters map (never {@code null})
     */
    private Map<String, Object> resolveParameters(JsonObject parametersObj) {
        if (parametersObj == null || parametersObj.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> params = new HashMap<>();
        for (String key : parametersObj.fieldNames()) {
            params.put(key, parametersObj.getValue(key));
        }
        return params;
    }
}
