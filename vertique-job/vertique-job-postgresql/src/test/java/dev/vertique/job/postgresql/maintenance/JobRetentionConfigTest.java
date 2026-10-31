// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.exception.ConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests the defaults and validation of {@link JobRetentionConfig}. */
@DisplayName("JobRetentionConfig")
class JobRetentionConfigTest {

    @Test
    @DisplayName("defaults to seven days and batches of 1000, the same retention as published outbox rows")
    void defaults() {
        JobRetentionConfig config = JobRetentionConfig.builder().build();

        assertEquals(604_800L, config.retentionSeconds());
        assertEquals(JobRetentionConfig.DEFAULT_RETENTION_SECONDS, config.retentionSeconds());
        assertEquals(1_000, config.batchSize());
    }

    @Test
    @DisplayName("validate returns the configuration at the minimum retention and a positive batch size")
    void validateAcceptsPositiveValues() {
        JobRetentionConfig config = JobRetentionConfig.builder()
                .retentionSeconds(JobRetentionConfig.MIN_RETENTION_SECONDS)
                .batchSize(1)
                .build();

        assertSame(config, config.validate());
    }

    @Test
    @DisplayName("validate rejects a retention below one hour")
    void validateRejectsTooShortRetention() {
        JobRetentionConfig config =
                JobRetentionConfig.builder().retentionSeconds(3_599).build();

        ConfigurationException failure = assertThrows(ConfigurationException.class, config::validate);
        assertEquals("job.retention.retentionSeconds must be >= 3600, got 3599", failure.getMessage());
    }

    @Test
    @DisplayName("validate rejects a batch size that is not positive")
    void validateRejectsNonPositiveBatchSize() {
        JobRetentionConfig config = JobRetentionConfig.builder().batchSize(-5).build();

        ConfigurationException failure = assertThrows(ConfigurationException.class, config::validate);
        assertEquals("job.retention.batchSize must be > 0, got -5", failure.getMessage());
    }
}
