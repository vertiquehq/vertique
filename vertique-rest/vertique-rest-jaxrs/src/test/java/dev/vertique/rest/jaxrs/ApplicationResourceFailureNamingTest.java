// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.application.RestApplications;
import dev.vertique.rest.jaxrs.application.unita.ExtraResource;
import dev.vertique.rest.jaxrs.application.unitb.ManagementApi;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceEntry;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.jaxrs.validation.NoneValidationStrategy;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An application evaluation failure names the application and its declaring interface. */
class ApplicationResourceFailureNamingTest {

    @Test
    @DisplayName("A resource provider that throws fails the composition naming the application and its declaring"
            + " interface")
    void throwingResourceProviderFailureNamesTheApplication() {
        JaxRsConfig config = JaxRsConfig.builder()
                .validationStrategy(NoneValidationStrategy.ID)
                .build();
        GeneratedRestApplicationRegistration registration = GeneratedRestApplicationRegistration.of(
                ManagementApi.class, "mgmt", "/api/mgmt", List.of(ExtraResource.class), false, "", true);
        Set<GeneratedRestApplicationRegistration> registrations = Set.of(registration);
        RestApplications view = RestApplicationsBuilder.build(registrations, List.of(), config, Optional.empty());
        GeneratedJaxRsResourceEntry throwingEntry = GeneratedJaxRsResourceEntry.of(ExtraResource.class, true, () -> {
            throw new IllegalStateException("resource construction failed");
        });

        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> RestModule.jaxRsRouterMount(
                        TestFactories.builder().build(),
                        view,
                        registrations,
                        () -> Set.<Object>of(),
                        () -> Set.of(throwingEntry),
                        config));

        String message = String.valueOf(failure.getMessage());
        assertTrue(
                message.contains("mgmt") && message.contains(ManagementApi.class.getName()),
                () -> "expected the failure to name the application and its declaring interface: " + message);
    }
}
