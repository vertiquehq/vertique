// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.types;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that the action returned by an add releases its names once, however often it is run. */
class WorkflowTypeNamesUndoIdempotenceTest {

    @WorkflowType("order")
    record Alpha(String id) {}

    @Test
    @DisplayName("running the release action twice does not take away a name another add still holds")
    void releaseActionRunTwiceKeepsTheNameAnotherAddHolds() {
        // Given: two adds that both hold Alpha
        DefaultWorkflowTypeNames names = new DefaultWorkflowTypeNames();
        Runnable first = names.add(null, List.of(Alpha.class));
        names.add(null, List.of(Alpha.class));

        // When: the first add is released twice
        first.run();
        first.run();

        // Then: the second add still holds the name
        assertThat(names.find("order")).hasValue(Alpha.class);
        assertThat(names.find(Alpha.class.getName())).hasValue(Alpha.class);
    }

    @Test
    @DisplayName("running the release action of the only holder twice leaves the index empty")
    void releaseActionRunTwiceOnTheOnlyHolderLeavesTheIndexEmpty() {
        DefaultWorkflowTypeNames names = new DefaultWorkflowTypeNames();
        Runnable only = names.add(null, List.of(Alpha.class));

        only.run();
        only.run();

        assertThat(names.find("order")).isEmpty();
        assertThat(names.find(Alpha.class.getName())).isEmpty();
    }
}
