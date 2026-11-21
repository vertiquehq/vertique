// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.router;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The default stage of an {@link OperationHandlerContributor}. */
class OperationHandlerContributorStageTest {

    @Test
    @DisplayName("a contributor runs with the other contributors, after the request gates, unless it opts in")
    void defaultsToTheOperationStage() {
        OperationHandlerContributor contributor = new OperationHandlerContributor() {
            @Override
            public int priority() {
                return 0;
            }

            @Override
            public void contribute(OperationRegistrationContext context) {
                // not exercised
            }
        };

        assertFalse(contributor.authenticationStage());
    }
}
