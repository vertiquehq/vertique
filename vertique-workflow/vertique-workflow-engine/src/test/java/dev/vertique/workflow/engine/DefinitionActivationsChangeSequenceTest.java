// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionActivation;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a refresh from committed records read before a lifecycle change never undoes that change, and that
 * the next refresh, taken after the change, settles the view.
 */
class DefinitionActivationsChangeSequenceTest {

    private static final String ID = "order";
    private static final Instant AT = Instant.parse("2026-10-21T12:00:00Z");
    private final WorkflowRegistry registry = mock(WorkflowRegistry.class);
    private final DefinitionActivations view =
            new DefinitionActivations(Optional.of(mock(WorkflowDefinitionRegistryStore.class)));

    @Test
    @DisplayName("a record written after the mark survives a refresh from stale committed records")
    void putAfterMarkSurvivesStaleRefresh() {
        // Given a committed record at version 1 held by the view
        view.put(record(1));
        long mark = view.mark();

        // When the lifecycle replaces it and a refresh then applies the records read before that
        view.put(record(2));
        view.refresh(ID, List.of(record(1)), mark);

        // Then the lifecycle's record stands
        assertThat(versions()).containsExactly(2L);

        // And the next refresh, with a mark taken after the change, settles the view with the committed record
        view.refresh(ID, List.of(record(3)), view.mark());
        assertThat(versions()).containsExactly(3L);
    }

    @Test
    @DisplayName("a record removed after the mark stays removed through a refresh from stale committed records")
    void removeAfterMarkSurvivesStaleRefresh() {
        // Given a committed record held by the view
        view.put(record(1));
        long mark = view.mark();

        // When the lifecycle removes it and a refresh applies the records read before the removal
        view.remove(WorkflowScopes.DEFAULT, ID);
        view.refresh(ID, List.of(record(1)), mark);

        // Then the record stays removed
        assertThat(versions()).isEmpty();

        // And the next refresh with a fresh mark settles the view with the committed record
        view.refresh(ID, List.of(record(1)), view.mark());
        assertThat(versions()).containsExactly(1L);
    }

    @Test
    @DisplayName("a record written after the mark is not discarded by a refresh whose committed records lack it")
    void putAfterMarkSurvivesStaleEmptyRefresh() {
        // Given a mark taken before any record was committed
        long mark = view.mark();

        // When the lifecycle writes a record and a refresh applies the empty records read before it
        view.put(record(1));
        view.refresh(ID, List.of(), mark);

        // Then the record stands
        assertThat(versions()).containsExactly(1L);

        // And a refresh with a fresh mark and no committed record removes it
        view.refresh(ID, List.of(), view.mark());
        assertThat(versions()).isEmpty();
    }

    private List<Long> versions() {
        return view.list(registry, null).stream()
                .map(DefinitionActivation::version)
                .toList();
    }

    private static DefinitionActivation record(long version) {
        return new DefinitionActivation(WorkflowScopes.DEFAULT, ID, WorkflowScopes.DEFAULT, version, AT);
    }
}
