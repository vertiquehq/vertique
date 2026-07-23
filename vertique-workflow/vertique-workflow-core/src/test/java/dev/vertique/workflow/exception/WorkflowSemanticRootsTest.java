// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.exception.NotFoundException;
import dev.vertique.core.exception.TechnicalException;
import dev.vertique.core.exception.UnavailableException;
import org.junit.jupiter.api.Test;

class WorkflowSemanticRootsTest {

    @Test
    void notFoundRootExtendsCoreNotFound() {
        assertTrue(NotFoundException.class.isAssignableFrom(WorkflowNotFoundException.class));
    }

    @Test
    void configurationRootExtendsCoreConfiguration() {
        assertTrue(ConfigurationException.class.isAssignableFrom(WorkflowConfigurationException.class));
    }

    @Test
    void technicalRootExtendsCoreTechnical() {
        assertTrue(TechnicalException.class.isAssignableFrom(WorkflowTechnicalException.class));
    }

    @Test
    void unavailableRootExtendsCoreUnavailable() {
        assertTrue(UnavailableException.class.isAssignableFrom(WorkflowUnavailableException.class));
    }

    @Test
    void rootsPreserveCause() {
        Throwable c = new IllegalStateException("x");
        assertSame(c, new WorkflowNotFoundException("m", c).getCause());
        assertSame(c, new WorkflowConfigurationException("m", c).getCause());
        assertSame(c, new WorkflowTechnicalException("m", c).getCause());
        assertSame(c, new WorkflowUnavailableException("m", c).getCause());
    }
}
