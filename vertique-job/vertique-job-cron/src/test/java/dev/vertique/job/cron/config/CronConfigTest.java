// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CronConfig} / {@link CronJobConfig} — boundary parsing of the {@code cron}
 * section into typed records, key injection into the job identity field, {@code parameters}
 * {@link JsonObject} round-trip, override-vs-absent ({@code null}) field semantics, and the
 * malformed-shape detection performed at the boundary factory.
 */
@DisplayName("CronConfig")
class CronConfigTest {

    // --- Helpers ---

    /**
     * Returns a lenient {@link ConfigParser} for use in test call-sites that need to parse config.
     *
     * @return a {@link DefaultConfigParser} with lenient mapper
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    // --- Boundary parsing ---

    @Nested
    @DisplayName("fromConfig")
    class FromConfig {

        @Test
        @DisplayName("absent cron section yields tracked=true and no jobs")
        void absentSectionDefaults() {
            CronConfig config = CronConfig.fromConfig(new JsonObject(), configParser());

            assertTrue(config.tracked(), "global tracked defaults to true");
            assertTrue(config.jobs().isEmpty(), "no jobs when section absent");
            assertTrue(config.jobIndex().isEmpty());
        }

        @Test
        @DisplayName("global tracked=false is read")
        void globalTrackedFalse() {
            JsonObject root = new JsonObject().put("cron", new JsonObject().put("tracked", false));

            CronConfig config = CronConfig.fromConfig(root, configParser());

            assertFalse(config.tracked());
        }

        @Test
        @DisplayName("each job key is injected into the job id identity field")
        void jobKeyInjectedIntoId() {
            JsonObject root = new JsonObject()
                    .put(
                            "cron",
                            new JsonObject()
                                    .put(
                                            "jobs",
                                            new JsonObject()
                                                    .put(
                                                            "report-weekly",
                                                            new JsonObject()
                                                                    .put("target", "service:a.b.c")
                                                                    .put("cron", "0 0 9 * * 1"))));

            CronConfig config = CronConfig.fromConfig(root, configParser());

            assertEquals(1, config.jobs().size());
            CronJobConfig job = config.jobs().get(0);
            assertEquals("report-weekly", job.id());
            assertEquals("service:a.b.c", job.target());
            assertEquals("0 0 9 * * 1", job.cron());
        }

        @Test
        @DisplayName("jobIndex maps each job id to its config")
        void jobIndexById() {
            JsonObject root = new JsonObject()
                    .put(
                            "cron",
                            new JsonObject()
                                    .put(
                                            "jobs",
                                            new JsonObject()
                                                    .put(
                                                            "job-a",
                                                            new JsonObject()
                                                                    .put("target", "eventbus:a/addr")
                                                                    .put("cron", "0 0 8 * * *"))
                                                    .put(
                                                            "job-b",
                                                            new JsonObject()
                                                                    .put("target", "eventbus:b/addr")
                                                                    .put("cron", "0 0 9 * * *"))));

            CronConfig config = CronConfig.fromConfig(root, configParser());

            assertEquals(2, config.jobIndex().size());
            assertEquals("eventbus:a/addr", config.jobIndex().get("job-a").target());
            assertEquals("eventbus:b/addr", config.jobIndex().get("job-b").target());
        }

        @Test
        @DisplayName("parameters JsonObject round-trips with values intact")
        void parametersRoundTrip() {
            JsonObject root = new JsonObject()
                    .put(
                            "cron",
                            new JsonObject()
                                    .put(
                                            "jobs",
                                            new JsonObject()
                                                    .put(
                                                            "with-params",
                                                            new JsonObject()
                                                                    .put("target", "eventbus:p/addr")
                                                                    .put("cron", "0 0 8 * * *")
                                                                    .put(
                                                                            "parameters",
                                                                            new JsonObject()
                                                                                    .put("days", 30)
                                                                                    .put("format", "pdf")))));

            CronConfig config = CronConfig.fromConfig(root, configParser());

            CronJobConfig job = config.jobs().get(0);
            assertEquals(30, job.parameters().getInteger("days"));
            assertEquals("pdf", job.parameters().getString("format"));
        }
    }

    // --- Field absence (null) vs presence semantics ---

    @Nested
    @DisplayName("override-vs-absent field semantics")
    class FieldSemantics {

        @Test
        @DisplayName("omitted override fields stay null so the registrar can fall through to annotation/defaults")
        void omittedOverrideFieldsAreNull() {
            JsonObject root = new JsonObject()
                    .put("cron", new JsonObject().put("jobs", new JsonObject().put("annotated-id", new JsonObject())));

            CronJobConfig job =
                    CronConfig.fromConfig(root, configParser()).jobIndex().get("annotated-id");

            assertNull(job.cron(), "cron null when absent");
            assertNull(job.timezone(), "timezone null when absent");
            assertNull(job.mode(), "mode null when absent");
            assertNull(job.overlapPolicy(), "overlapPolicy null when absent");
            assertNull(job.misfirePolicy(), "misfirePolicy null when absent");
            assertNull(job.tracked(), "tracked null (inherit global) when absent");
            assertNull(job.target(), "target null when absent");
        }

        @Test
        @DisplayName("enabled defaults to true; maxAttempts defaults to 3")
        void scalarDefaults() {
            JsonObject root = new JsonObject()
                    .put("cron", new JsonObject().put("jobs", new JsonObject().put("id", new JsonObject())));

            CronJobConfig job =
                    CronConfig.fromConfig(root, configParser()).jobIndex().get("id");

            assertTrue(job.enabled(), "enabled defaults to true");
            assertEquals(3, job.maxAttempts(), "maxAttempts defaults to 3");
        }

        @Test
        @DisplayName("explicit enabled=false and maxAttempts are read")
        void explicitScalars() {
            JsonObject root = new JsonObject()
                    .put(
                            "cron",
                            new JsonObject()
                                    .put(
                                            "jobs",
                                            new JsonObject()
                                                    .put(
                                                            "id",
                                                            new JsonObject()
                                                                    .put("enabled", false)
                                                                    .put("maxAttempts", 7))));

            CronJobConfig job =
                    CronConfig.fromConfig(root, configParser()).jobIndex().get("id");

            assertFalse(job.enabled());
            assertEquals(7, job.maxAttempts());
        }
    }

    // --- toString redaction (W4: parameters is an open user payload) ---

    @Nested
    @DisplayName("toString redaction")
    class ToStringRedaction {

        @Test
        @DisplayName("secret values in the open parameters payload are redacted in toString but intact live")
        void parametersSecretRedactedInToString() {
            // parameters is an open user payload (R9) that may carry secrets; the default record
            // toString() rendered it verbatim (gap W4), including via CronConfig.toString().
            JsonObject root = new JsonObject()
                    .put(
                            "cron",
                            new JsonObject()
                                    .put(
                                            "jobs",
                                            new JsonObject()
                                                    .put(
                                                            "with-secret",
                                                            new JsonObject()
                                                                    .put("target", "service:a.b.c")
                                                                    .put("cron", "0 0 8 * * *")
                                                                    .put(
                                                                            "parameters",
                                                                            new JsonObject()
                                                                                    .put("apiSecret", "SEKRIT")
                                                                                    .put("format", "pdf")))));

            CronConfig config = CronConfig.fromConfig(root, configParser());
            CronJobConfig job = config.jobIndex().get("with-secret");

            // The live parameters() still carries the real secret for runtime use.
            assertEquals("SEKRIT", job.parameters().getString("apiSecret"));

            // Per-job toString masks the secret but keeps non-secret values.
            String jobRendered = job.toString();
            assertFalse(
                    jobRendered.contains("SEKRIT"), "parameters secret must not appear in CronJobConfig.toString()");
            assertTrue(jobRendered.contains("pdf"), "non-secret parameter stays visible");

            // CronConfig.toString() inherits the per-job redaction.
            assertFalse(
                    config.toString().contains("SEKRIT"),
                    "parameters secret must not leak through CronConfig.toString()");
        }
    }

    // --- Malformed-shape detection at the boundary ---

    @Nested
    @DisplayName("malformed shapes")
    class MalformedShapes {

        @Test
        @DisplayName("scalar at 'cron' throws ConfigurationException naming the path")
        void scalarAtCron() {
            JsonObject root = new JsonObject().put("cron", "oops");

            ConfigurationException ex =
                    assertThrows(ConfigurationException.class, () -> CronConfig.fromConfig(root, configParser()));
            assertTrue(
                    ex.getMessage().contains("'cron'") && ex.getMessage().contains("JSON object"),
                    "Expected a 'cron must be a JSON object' message, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("scalar at 'cron.jobs' throws ConfigurationException naming the path")
        void scalarAtJobs() {
            JsonObject root = new JsonObject().put("cron", new JsonObject().put("jobs", "oops"));

            ConfigurationException ex =
                    assertThrows(ConfigurationException.class, () -> CronConfig.fromConfig(root, configParser()));
            assertTrue(
                    ex.getMessage().contains("'jobs'") && ex.getMessage().contains("JSON object"),
                    "Expected a 'jobs must be a JSON object' message, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("scalar at 'cron.jobs.<id>' throws ConfigurationException naming the full path")
        void scalarAtPerJobEntry() {
            JsonObject root = new JsonObject()
                    .put("cron", new JsonObject().put("jobs", new JsonObject().put("daily-report", "oops")));

            ConfigurationException ex =
                    assertThrows(ConfigurationException.class, () -> CronConfig.fromConfig(root, configParser()));
            assertTrue(
                    ex.getMessage().contains("'cron.jobs.daily-report'")
                            && ex.getMessage().contains("JSON object"),
                    "Expected a per-job 'must be a JSON object' message with full path, got: " + ex.getMessage());
        }
    }
}
