// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.exception.ConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link WorkflowClientProxyLinkageException}: both constructors populate the message and
 * cause, and the type is a {@link WorkflowConfigurationException} (and transitively a
 * {@link ConfigurationException}).
 */
class WorkflowClientProxyLinkageExceptionTest {

    private static final String MESSAGE = "Generated proxy com.example.Foo_WorkflowClientProxy present but broken";

    @Test
    @DisplayName("message constructor populates getMessage()")
    void messageConstructor() {
        WorkflowClientProxyLinkageException ex = new WorkflowClientProxyLinkageException(MESSAGE);
        assertThat(ex.getMessage()).isEqualTo(MESSAGE);
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("message+cause constructor populates getMessage() and getCause()")
    void messageAndCauseConstructor() {
        Throwable cause = new NoSuchMethodException("no (WorkflowOperations) ctor");
        WorkflowClientProxyLinkageException ex = new WorkflowClientProxyLinkageException(MESSAGE, cause);
        assertThat(ex.getMessage()).isEqualTo(MESSAGE);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("is a subtype of WorkflowConfigurationException")
    void isSubtypeOfWorkflowConfigurationException() {
        assertThat(new WorkflowClientProxyLinkageException(MESSAGE))
                .isInstanceOf(WorkflowConfigurationException.class)
                .isInstanceOf(ConfigurationException.class);
    }
}
