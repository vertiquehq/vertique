// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Module;
import org.junit.jupiter.api.Test;

/**
 * Structural guards for the {@code vertique-rest-validation} module skeleton: a {@code @Module}-annotated
 * Dagger entry point exists in the module package, and the package is present (package-info compiles).
 */
class ModuleStructureTest {

    @Test
    void restValidationModuleHasDaggerModule() {
        assertTrue(
                RestValidationModule.class.isAnnotationPresent(Module.class),
                "RestValidationModule must be annotated with @dagger.Module");
    }

    @Test
    void restValidationModuleHasPackageInfo() {
        Package pkg = RestValidationModule.class.getPackage();
        assertNotNull(pkg, "package dev.vertique.rest.validation must be present");
        assertTrue(
                "dev.vertique.rest.validation".equals(pkg.getName()),
                "RestValidationModule must live in dev.vertique.rest.validation");
    }
}
