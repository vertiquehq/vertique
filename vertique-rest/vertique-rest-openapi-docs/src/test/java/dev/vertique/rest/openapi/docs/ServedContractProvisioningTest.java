// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.jaxrs.application.RestApplications;
import dev.vertique.rest.jaxrs.application.RestApplications.ContractOrigin;
import dev.vertique.rest.openapi.docs.fixture.contract.ContractFiles;
import dev.vertique.rest.openapi.docs.fixture.contract.PartnerApi;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit proof that the selection of enabled documents, which every component runs before any
 * mount is built, refuses a documented application whose entry in the declared-application view says
 * it serves its own contract but names no contract location.
 *
 * <p>The selection {@link EnabledDocumentsResolver#select} is called directly with a hand-built
 * {@link RestApplications} view, because the view the JAX-RS module builds never pairs a configured
 * or declared contract origin with a missing location; the view is the selection's only source of the
 * contract origin and location, so this is the narrowest seam on the real provisioning path. The one
 * application is the fixture {@link PartnerApi}, active and documented with a public {@link ApiDocs},
 * with an empty configuration and the default {@code apidocs} section. A control row gives the same
 * entry a location and must resolve, so a refusal can only come from the missing location.
 */
@DisplayName("Provisioning of a served document without a contract location")
class ServedContractProvisioningTest {

    /** How the refusal names the application, compared ignoring letter case. */
    private static final String APPLICATION_NAMED = "application 'partner'";

    /** The statement of the refusal. */
    private static final String NO_CONTRACT_LOCATION = "has no contract location";

    /** The application's name. */
    private static final String PARTNER = "partner";

    /** The application's mount path as its registration reports it. */
    private static final String MOUNT_PATH = "/api/partner/*";

    @ParameterizedTest(name = "contract origin {0}")
    @EnumSource(
            value = ContractOrigin.class,
            names = {"CONFIGURATION", "ANNOTATION"})
    @DisplayName(
            "A served document whose application has no contract location fails provisioning naming the application")
    void servedDocumentWithoutLocationFailsProvisioning(ContractOrigin origin) {
        // Given: partner, documented, whose contract origin is its own but whose location is missing
        RestApplications applications = view(origin, null);

        // When: the enabled documents are provisioned
        ConfigurationException failure = assertThrows(ConfigurationException.class, () -> provision(applications));

        // Then: the refusal names the application and the missing location
        String message = String.valueOf(failure.getMessage());
        assertAll(
                "the refusal: " + message,
                () -> assertTrue(
                        message.toLowerCase(Locale.ROOT).contains(APPLICATION_NAMED), "names " + APPLICATION_NAMED),
                () -> assertTrue(message.contains(NO_CONTRACT_LOCATION), "says " + NO_CONTRACT_LOCATION));
    }

    @ParameterizedTest(name = "contract origin {0}")
    @EnumSource(
            value = ContractOrigin.class,
            names = {"CONFIGURATION", "ANNOTATION"})
    @DisplayName("Control: the same served document with a contract location is provisioned")
    void servedDocumentWithLocationIsProvisioned(ContractOrigin origin) {
        // Given: the same entry with partner's own contract location
        RestApplications applications = view(origin, ContractFiles.PARTNER);

        // When: the enabled documents are provisioned
        EnabledDocuments documents = assertDoesNotThrow(() -> provision(applications));

        // Then: partner's document is enabled with its contract origin
        assertEquals(1, documents.all().size(), () -> "enabled documents: " + documents);
        assertEquals(PARTNER, documents.all().get(0).name());
        assertEquals(origin, documents.all().get(0).contractOrigin());
    }

    /** Builds the declared-application view of the one active, documented application partner. */
    private static RestApplications view(ContractOrigin origin, String location) {
        return new RestApplications(
                List.of(new RestApplications.Entry(PARTNER, PartnerApi.class, true, MOUNT_PATH, location, origin)));
    }

    /** Runs the selection with an empty configuration and the default, enabled section. */
    private static EnabledDocuments provision(RestApplications applications) {
        ApidocsConfig apidocsConfig = new ApidocsConfig(EnabledDocuments.DEFAULT_PATH, true, List.of());
        return EnabledDocumentsResolver.select(new JsonObject(), apidocsConfig, applications);
    }
}
