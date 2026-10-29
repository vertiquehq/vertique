// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.workflow.engine.spi.TransactionalDefinitionReads.StartSelection;
import dev.vertique.workflow.engine.spi.TransactionalDefinitionReads.StartVersion;
import dev.vertique.workflow.exception.WorkflowDefinitionMissingException;
import dev.vertique.workflow.exception.WorkflowNoCurrentVersionException;
import dev.vertique.workflow.exception.WorkflowVersionNotRunnableException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import jakarta.annotation.Nullable;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses the version a first start runs from what one selection read returned, in the order the start has always
 * used.
 *
 * <p>A start that names a version and a target scope runs exactly that key, and only the start scope and the default
 * scope can be named. A start that names a version only runs it from the start scope when the start scope holds a
 * version of the id that implements the client's contract, and from the default scope otherwise. A start that names no
 * version runs what the activation records select for the client's contract: the start scope's record when the scope
 * holds versions of the client's contract, else the default scope's record, and the scope's own record also when it
 * names a version of the default scope. The choice reads only the rows and records of the selection; it consults no
 * registry and loads no plan.
 */
final class StartVersionSelection {

    /** The statuses in which a stored version counts for the contract its scope implements. */
    private static final Set<DefinitionStatus> LIVE = Set.of(DefinitionStatus.AVAILABLE, DefinitionStatus.DEPRECATED);

    /** The statuses of a version that was published at some point. */
    private static final Set<DefinitionStatus> PUBLISHED =
            Set.of(DefinitionStatus.AVAILABLE, DefinitionStatus.DEPRECATED, DefinitionStatus.RETIRED);

    private StartVersionSelection() {}

    /**
     * Selects the version.
     *
     * @param read         the rows and activation records of the id in the start scope and the default scope
     * @param startScope   the scope the start runs in
     * @param definitionId the definition id the start names
     * @param contractName the contract of the client that starts
     * @param version      the version the start names, or {@code null}
     * @param targetScope  the scope the start names for its version, or {@code null}
     * @return the stored version the start runs; it may be deprecated, which the caller refuses, or retired, which pin
     *     admission refuses
     * @throws WorkflowNoCurrentVersionException when no activation record applies to the client's contract
     * @throws WorkflowDefinitionMissingException when the named target scope is neither the start scope nor the
     *     default scope
     * @throws WorkflowVersionNotRunnableException when the selected key has no published row
     */
    static StartVersion select(
            StartSelection read,
            String startScope,
            String definitionId,
            String contractName,
            @Nullable Long version,
            @Nullable String targetScope) {
        boolean owns = !WorkflowScopes.DEFAULT.equals(startScope)
                && contractOf(read, startScope).filter(contractName::equals).isPresent();
        DefinitionKey key;
        if (version == null) {
            DefinitionActivation record = activationFor(read, startScope, contractName, owns);
            if (record == null) throw new WorkflowNoCurrentVersionException(startScope, definitionId);
            key = new DefinitionKey(record.targetScopeKey(), definitionId, record.version());
        } else if (targetScope != null) {
            if (!targetScope.equals(startScope) && !targetScope.equals(WorkflowScopes.DEFAULT)) {
                throw new WorkflowDefinitionMissingException(definitionId, targetScope);
            }
            key = requestedKey(targetScope, definitionId, version);
        } else {
            key = requestedKey(owns ? startScope : WorkflowScopes.DEFAULT, definitionId, version);
        }
        // A retired row is returned: pin admission refuses it with its typed error, the same refusal a version that
        // retired after this read gets. A row that was never published (candidate, publishing) is not runnable.
        return read.versions().stream()
                .filter(row -> row.key().equals(key) && PUBLISHED.contains(row.status()))
                .findFirst()
                .orElseThrow(() -> new WorkflowVersionNotRunnableException(key, null));
    }

    /** The key of an explicit start version. An id, scope or version that no key can hold names no stored version. */
    static DefinitionKey requestedKey(String scopeKey, String definitionId, long version) {
        try {
            return new DefinitionKey(scopeKey, definitionId, version);
        } catch (IllegalArgumentException e) {
            throw new WorkflowVersionPinUnavailableException(scopeKey, definitionId, version, null);
        }
    }

    /** The activation record a start without a version follows, or {@code null} when none applies. */
    private static @Nullable DefinitionActivation activationFor(
            StartSelection read, String startScope, String contractName, boolean owns) {
        DefinitionActivation own = activationOf(read, startScope);
        if (owns && own != null) return own;
        boolean defaultNamesContract = contractOf(read, WorkflowScopes.DEFAULT)
                .map(contractName::equals)
                .orElse(true);
        if (!defaultNamesContract) return null;
        // The scope's record counts here only when it names a version of the default scope.
        return own != null && WorkflowScopes.DEFAULT.equals(own.targetScopeKey())
                ? own
                : activationOf(read, WorkflowScopes.DEFAULT);
    }

    private static @Nullable DefinitionActivation activationOf(StartSelection read, String scopeKey) {
        return read.activations().stream()
                .filter(record -> record.scopeKey().equals(scopeKey))
                .findFirst()
                .orElse(null);
    }

    /** The contract the scope's available or deprecated versions implement, read from the lowest such version. */
    private static Optional<String> contractOf(StartSelection read, String scopeKey) {
        // A row in any status is returned: one that admits no new pin is refused by pin admission with its typed
        // error, the same refusal a version that changed after this read gets.
        return read.versions().stream()
                .filter(row -> row.key().scopeKey().equals(scopeKey) && LIVE.contains(row.status()))
                .min(Comparator.comparingLong((StartVersion row) -> row.key().definitionVersion()))
                .map(StartVersion::contractName);
    }
}
