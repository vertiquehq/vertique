// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.validation;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Module;
import org.junit.jupiter.api.Test;

/**
 * Structural guards for the {@code vertique-rest-openapi-validation} module skeleton: a
 * {@code @Module}-annotated Dagger entry point exists in the module package, and the package is present
 * (package-info compiles).
 */
class ModuleStructureTest {

    @Test
    void openApiContractModuleHasDaggerModule() {
        assertTrue(
                OpenApiContractValidationModule.class.isAnnotationPresent(Module.class),
                "OpenApiContractValidationModule must be annotated with @dagger.Module");
    }

    @Test
    void openApiContractModuleHasPackageInfo() {
        Package pkg = OpenApiContractValidationModule.class.getPackage();
        assertNotNull(pkg, "package dev.vertique.rest.openapi.validation must be present");
        assertTrue(
                "dev.vertique.rest.openapi.validation".equals(pkg.getName()),
                "OpenApiContractValidationModule must live in dev.vertique.rest.openapi.validation");
    }
}
