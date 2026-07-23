// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.spi;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.DedupClaim;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.StartDedupResult;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.repository.PgBranchTokenRepository;
import dev.vertique.workflow.postgresql.repository.PgJoinStateRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the workflow repository SPIs.
 *
 * <p>Verifies, by reflection only (no database), that each {@code Pg*Repository} (dialect, in this
 * module) implements its corresponding portable {@code spi} interface (now in the
 * {@code dev.vertique.workflow.engine.spi} package after the Phase-2 relocation), that the neutral
 * result records live in that SPI package, and that the dedup repository's claim methods return those
 * neutral records (confirming the old nested records are gone). These are structural assertions that
 * lock in the SPI extraction and the dialect-implements-portable-SPI boundary.
 */
class RepositorySpiContractTest {

    // --- Pg repos implement their SPI ---

    @Test
    @DisplayName("PgWorkflowInstanceRepository implements WorkflowInstanceRepository SPI")
    void pgWorkflowInstanceRepositoryImplementsSpi() {
        assertThat(WorkflowInstanceRepository.class.isAssignableFrom(PgWorkflowInstanceRepository.class))
                .isTrue();
    }

    @Test
    @DisplayName("PgWorkflowHistoryRepository implements WorkflowHistoryRepository SPI")
    void pgWorkflowHistoryRepositoryImplementsSpi() {
        assertThat(WorkflowHistoryRepository.class.isAssignableFrom(PgWorkflowHistoryRepository.class))
                .isTrue();
    }

    @Test
    @DisplayName("PgWorkflowDedupRepository implements WorkflowDedupRepository SPI")
    void pgWorkflowDedupRepositoryImplementsSpi() {
        assertThat(WorkflowDedupRepository.class.isAssignableFrom(PgWorkflowDedupRepository.class))
                .isTrue();
    }

    @Test
    @DisplayName("PgBranchTokenRepository implements BranchTokenRepository SPI")
    void pgBranchTokenRepositoryImplementsSpi() {
        assertThat(BranchTokenRepository.class.isAssignableFrom(PgBranchTokenRepository.class))
                .isTrue();
    }

    @Test
    @DisplayName("PgJoinStateRepository implements JoinStateRepository SPI")
    void pgJoinStateRepositoryImplementsSpi() {
        assertThat(JoinStateRepository.class.isAssignableFrom(PgJoinStateRepository.class))
                .isTrue();
    }

    // --- Neutral records live in the spi package ---

    @Test
    @DisplayName("StartDedupResult record lives in the engine spi package")
    void startDedupResultIsInSpiPackage() {
        assertThat(StartDedupResult.class.getPackageName()).isEqualTo("dev.vertique.workflow.engine.spi");
    }

    @Test
    @DisplayName("DedupClaim record lives in the engine spi package")
    void dedupClaimIsInSpiPackage() {
        assertThat(DedupClaim.class.getPackageName()).isEqualTo("dev.vertique.workflow.engine.spi");
    }

    // --- Dedup claim methods return the neutral records ---

    @Test
    @DisplayName("PgWorkflowDedupRepository.claimOrResolveStart resolves to the spi StartDedupResult")
    void pgWorkflowDedupRepositoryReturnsNeutralStartDedupResult() throws NoSuchMethodException {
        Method m = PgWorkflowDedupRepository.class.getMethod(
                "claimOrResolveStart",
                String.class,
                String.class,
                dev.vertique.workflow.ops.WorkflowInstanceId.class,
                String.class,
                io.vertx.sqlclient.SqlClient.class);
        assertThat(m.getReturnType()).isEqualTo(io.vertx.core.Future.class);
        assertThat(firstTypeArgument(m)).isEqualTo(StartDedupResult.class);
    }

    @Test
    @DisplayName("PgWorkflowDedupRepository.claimOrResolveTaskCompletion resolves to the spi DedupClaim")
    void pgWorkflowDedupRepositoryReturnsNeutralDedupClaim() throws NoSuchMethodException {
        Method m = PgWorkflowDedupRepository.class.getMethod(
                "claimOrResolveTaskCompletion",
                java.util.UUID.class,
                String.class,
                dev.vertique.workflow.ops.WorkflowInstanceId.class,
                String.class,
                io.vertx.sqlclient.SqlClient.class);
        assertThat(m.getReturnType()).isEqualTo(io.vertx.core.Future.class);
        assertThat(firstTypeArgument(m)).isEqualTo(DedupClaim.class);
    }

    /**
     * Extracts the first generic type argument of a method's {@code Future<X>} return type.
     *
     * @param m the method whose return type to inspect
     * @return the {@code X} in {@code Future<X>}
     */
    private static Class<?> firstTypeArgument(Method m) {
        ParameterizedType returnType = (ParameterizedType) m.getGenericReturnType();
        return (Class<?>) returnType.getActualTypeArguments()[0];
    }
}
