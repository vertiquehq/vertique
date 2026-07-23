// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * Verifies that the Maven Enforcer {@code bannedDependencies} rule in
 * {@code vertique-workflow-services/pom.xml} is correctly declared and will reject both
 * banned artifacts if they are added as compile-time dependencies.
 *
 * <p>The rule (boundary 1, CG-WF-001 cycle 1) prohibits:
 * <ul>
 *   <li>{@code dev.vertique:vertique-workflow-postgresql} — workflow-services must not
 *       compile-depend on the PostgreSQL engine; it uses the generic
 *       {@code TransactionalWorkflowOperations&lt;SqlClient&gt;} SPI from workflow-core.</li>
 *   <li>{@code dev.vertique:vertique-inbox-outbox-services} — the SERVICE outbox destination
 *       handler is wired in at app-composition time, not via a compile dependency.</li>
 * </ul>
 *
 * <p>This test guards the Maven layer: if the enforcer block is accidentally removed,
 * mis-configured, or the excluded artifact coordinates are changed, this test fails
 * immediately, before a banned import can silently enter the compile classpath.
 *
 * <p>The test reads and parses the actual {@code pom.xml} of the
 * {@code vertique-workflow-services} module (located at {@code user.dir/pom.xml} during
 * Maven test execution) using the JDK's built-in {@link DocumentBuilder} and XPath.
 * No external processes or network access are required.
 *
 * <p>This suffix {@code IT} reflects that the test validates build-infrastructure
 * concerns (module boundary enforcement at the Maven layer) rather than a unit of
 * business logic. Maven Failsafe runs it during the {@code verify} phase.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class EnforcerBannedDepsIT {

    /** The artifacts that must be excluded by the enforcer rule. */
    private static final String BANNED_WORKFLOW_POSTGRESQL = "dev.vertique:vertique-workflow-postgresql";

    /** The inbox-outbox services artifact that must be excluded by the enforcer rule. */
    private static final String BANNED_INBOX_OUTBOX_SERVICES = "dev.vertique:vertique-inbox-outbox-services";

    /** Fragments that must appear in the enforcer rule's configured message. */
    private static final String MSG_FRAGMENT_POSTGRESQL = "workflow-postgresql";

    /** Fragment for the inbox-outbox message in the enforcer rule's configured message. */
    private static final String MSG_FRAGMENT_INBOX_OUTBOX = "inbox-outbox-services";

    // --- Parsed POM state ---

    /** Excluded artifacts declared in the enforcer {@code bannedDependencies} rule. */
    private static List<String> excludedArtifacts;

    /** The enforcer rule's {@code <message>} content. */
    private static String enforcerMessage;

    /**
     * Parses {@code pom.xml} from the module base directory and extracts the
     * {@code bannedDependencies} rule configuration.
     *
     * @throws Exception if the POM cannot be parsed or the enforcer block is absent
     */
    @BeforeAll
    static void parsePom() throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File pomFile = new File(moduleDir, "pom.xml");

        assertThat(pomFile)
                .as("pom.xml must exist at %s", pomFile.getAbsolutePath())
                .exists()
                .isFile();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(pomFile);
        doc.getDocumentElement().normalize();

        XPath xpath = XPathFactory.newInstance().newXPath();

        // Collect all <exclude> elements under any <bannedDependencies> rule
        NodeList excludeNodes =
                (NodeList) xpath.evaluate("//bannedDependencies/excludes/exclude", doc, XPathConstants.NODESET);

        excludedArtifacts = new ArrayList<>();
        for (int i = 0; i < excludeNodes.getLength(); i++) {
            excludedArtifacts.add(excludeNodes.item(i).getTextContent().trim());
        }

        // Extract the <message> from the bannedDependencies rule
        NodeList messageNodes = (NodeList) xpath.evaluate("//bannedDependencies/message", doc, XPathConstants.NODESET);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messageNodes.getLength(); i++) {
            sb.append(messageNodes.item(i).getTextContent().trim());
        }
        enforcerMessage = sb.toString();
    }

    // --- Tests ---

    @Test
    @DisplayName("bannedDependencies rule excludes vertique-workflow-postgresql")
    void bannedDependencies_excludes_workflowPostgresql() {
        assertThat(excludedArtifacts)
                .as(
                        "bannedDependencies <exclude> list must contain '%s' so that adding the "
                                + "workflow-postgresql compile dep causes the Enforcer rule to fail the build",
                        BANNED_WORKFLOW_POSTGRESQL)
                .contains(BANNED_WORKFLOW_POSTGRESQL);
    }

    @Test
    @DisplayName("bannedDependencies rule excludes vertique-inbox-outbox-services")
    void bannedDependencies_excludes_inboxOutboxServices() {
        assertThat(excludedArtifacts)
                .as(
                        "bannedDependencies <exclude> list must contain '%s' so that adding the "
                                + "inbox-outbox-services compile dep causes the Enforcer rule to fail the build",
                        BANNED_INBOX_OUTBOX_SERVICES)
                .contains(BANNED_INBOX_OUTBOX_SERVICES);
    }

    @Test
    @DisplayName("bannedDependencies message references workflow-postgresql")
    void enforcerMessage_referencesWorkflowPostgresql() {
        assertThat(enforcerMessage)
                .as("Enforcer <message> must reference 'workflow-postgresql' so that build "
                        + "failures are self-documenting — developers see WHY the dep is banned")
                .contains(MSG_FRAGMENT_POSTGRESQL);
    }

    @Test
    @DisplayName("bannedDependencies message references inbox-outbox-services")
    void enforcerMessage_referencesInboxOutboxServices() {
        assertThat(enforcerMessage)
                .as("Enforcer <message> must reference 'inbox-outbox-services' so that build "
                        + "failures are self-documenting — developers see WHY the dep is banned")
                .contains(MSG_FRAGMENT_INBOX_OUTBOX);
    }

    @Test
    @DisplayName("bannedDependencies excludes list is non-empty (rule is active)")
    void bannedDependencies_ruleIsActive() {
        assertThat(excludedArtifacts)
                .as("The bannedDependencies <excludes> list must be non-empty — the Enforcer rule must be active")
                .isNotEmpty();
    }
}
