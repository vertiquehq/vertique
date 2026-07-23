// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.events.build;

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
 * {@code vertique-workflow-events/pom.xml} is correctly declared and will reject the
 * banned sibling adapter artifacts if they are added as compile-time dependencies.
 *
 * <p>The rule (boundary, cycle 4) prohibits:
 * <ul>
 *   <li>{@code dev.vertique:vertique-workflow-postgresql} — workflow-events must not
 *       compile-depend on the PostgreSQL engine; it uses the generic
 *       {@code WorkflowSideEffectRecorder&lt;SqlClient&gt;} SPI from workflow-core.</li>
 *   <li>{@code dev.vertique:vertique-workflow-services} — peer adapter module must not be
 *       pulled in transitively.</li>
 *   <li>{@code dev.vertique:vertique-workflow-delayed} — peer adapter module must not be
 *       pulled in transitively.</li>
 *   <li>{@code dev.vertique:vertique-workflow-tasks} — peer adapter module must not be
 *       pulled in transitively.</li>
 *   <li>{@code dev.vertique:vertique-inbox-outbox-services} — the outbox destination handler is
 *       wired at app-composition time, not via a compile dependency on any adapter module.</li>
 *   <li>{@code dev.vertique:vertique-inbox-outbox-postgresql} — storage adapter must not be
 *       reachable transitively.</li>
 *   <li>{@code dev.vertique:vertique-inbox-outbox-kafka} — Kafka adapter must not be
 *       reachable transitively.</li>
 *   <li>{@code dev.vertique:vertique-inbox-outbox-delayed-job} — delayed-job adapter must not
 *       be reachable transitively.</li>
 *   <li>{@code dev.vertique:vertique-services} — services module must not be pulled in.</li>
 * </ul>
 *
 * <p>This test reads and parses the actual {@code pom.xml} of the
 * {@code vertique-workflow-events} module (located at {@code user.dir/pom.xml} during Maven test
 * execution) using the JDK's built-in {@link DocumentBuilder} and XPath. No external processes
 * or network access are required.
 *
 * <p>The {@code IT} suffix reflects that the test validates build-infrastructure concerns (module
 * boundary enforcement at the Maven layer) rather than a unit of business logic. Maven Failsafe
 * runs it during the {@code verify} phase.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class EnforcerBannedDepsIT {

    // --- Expected banned artifact coordinates ---

    private static final String BANNED_WORKFLOW_POSTGRESQL = "dev.vertique:vertique-workflow-postgresql";
    private static final String BANNED_WORKFLOW_SERVICES = "dev.vertique:vertique-workflow-services";
    private static final String BANNED_WORKFLOW_DELAYED = "dev.vertique:vertique-workflow-delayed";
    private static final String BANNED_WORKFLOW_TASKS = "dev.vertique:vertique-workflow-tasks";
    private static final String BANNED_INBOX_OUTBOX_SERVICES = "dev.vertique:vertique-inbox-outbox-services";
    private static final String BANNED_INBOX_OUTBOX_POSTGRESQL = "dev.vertique:vertique-inbox-outbox-postgresql";
    private static final String BANNED_INBOX_OUTBOX_KAFKA = "dev.vertique:vertique-inbox-outbox-kafka";
    private static final String BANNED_INBOX_OUTBOX_DELAYED_JOB = "dev.vertique:vertique-inbox-outbox-delayed-job";
    private static final String BANNED_SERVICES = "dev.vertique:vertique-services";

    // --- Message fragments expected in the enforcer rule ---

    private static final String MSG_FRAGMENT_ADAPTER_MODULES = "workflow-events";
    private static final String MSG_FRAGMENT_WORKFLOW_CORE = "workflow-core";

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

        NodeList excludeNodes =
                (NodeList) xpath.evaluate("//bannedDependencies/excludes/exclude", doc, XPathConstants.NODESET);

        excludedArtifacts = new ArrayList<>();
        for (int i = 0; i < excludeNodes.getLength(); i++) {
            excludedArtifacts.add(excludeNodes.item(i).getTextContent().trim());
        }

        NodeList messageNodes = (NodeList) xpath.evaluate("//bannedDependencies/message", doc, XPathConstants.NODESET);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messageNodes.getLength(); i++) {
            sb.append(messageNodes.item(i).getTextContent().trim());
        }
        enforcerMessage = sb.toString();
    }

    // --- Tests: excluded artifacts ---

    @Test
    @DisplayName("bannedDependencies rule excludes vertique-workflow-postgresql")
    void bannedDependencies_excludes_workflowPostgresql() {
        assertThat(excludedArtifacts)
                .as("bannedDependencies must exclude '%s'", BANNED_WORKFLOW_POSTGRESQL)
                .contains(BANNED_WORKFLOW_POSTGRESQL);
    }

    @Test
    @DisplayName("bannedDependencies rule excludes vertique-workflow-services")
    void bannedDependencies_excludes_workflowServices() {
        assertThat(excludedArtifacts)
                .as("bannedDependencies must exclude '%s'", BANNED_WORKFLOW_SERVICES)
                .contains(BANNED_WORKFLOW_SERVICES);
    }

    @Test
    @DisplayName("bannedDependencies rule excludes vertique-workflow-delayed")
    void bannedDependencies_excludes_workflowDelayed() {
        assertThat(excludedArtifacts)
                .as("bannedDependencies must exclude '%s'", BANNED_WORKFLOW_DELAYED)
                .contains(BANNED_WORKFLOW_DELAYED);
    }

    @Test
    @DisplayName("bannedDependencies rule excludes vertique-workflow-tasks")
    void bannedDependencies_excludes_workflowTasks() {
        assertThat(excludedArtifacts)
                .as("bannedDependencies must exclude '%s'", BANNED_WORKFLOW_TASKS)
                .contains(BANNED_WORKFLOW_TASKS);
    }

    @Test
    @DisplayName("bannedDependencies rule excludes vertique-inbox-outbox-services")
    void bannedDependencies_excludes_inboxOutboxServices() {
        assertThat(excludedArtifacts)
                .as("bannedDependencies must exclude '%s'", BANNED_INBOX_OUTBOX_SERVICES)
                .contains(BANNED_INBOX_OUTBOX_SERVICES);
    }

    @Test
    @DisplayName("bannedDependencies rule excludes vertique-inbox-outbox-postgresql")
    void bannedDependencies_excludes_inboxOutboxPostgresql() {
        assertThat(excludedArtifacts)
                .as("bannedDependencies must exclude '%s'", BANNED_INBOX_OUTBOX_POSTGRESQL)
                .contains(BANNED_INBOX_OUTBOX_POSTGRESQL);
    }

    @Test
    @DisplayName("bannedDependencies rule excludes vertique-inbox-outbox-kafka")
    void bannedDependencies_excludes_inboxOutboxKafka() {
        assertThat(excludedArtifacts)
                .as("bannedDependencies must exclude '%s'", BANNED_INBOX_OUTBOX_KAFKA)
                .contains(BANNED_INBOX_OUTBOX_KAFKA);
    }

    @Test
    @DisplayName("bannedDependencies rule excludes vertique-inbox-outbox-delayed-job")
    void bannedDependencies_excludes_inboxOutboxDelayedJob() {
        assertThat(excludedArtifacts)
                .as("bannedDependencies must exclude '%s'", BANNED_INBOX_OUTBOX_DELAYED_JOB)
                .contains(BANNED_INBOX_OUTBOX_DELAYED_JOB);
    }

    @Test
    @DisplayName("bannedDependencies rule excludes vertique-services")
    void bannedDependencies_excludes_services() {
        assertThat(excludedArtifacts)
                .as("bannedDependencies must exclude '%s'", BANNED_SERVICES)
                .contains(BANNED_SERVICES);
    }

    // --- Tests: message content ---

    @Test
    @DisplayName("bannedDependencies message references workflow-events module context")
    void enforcerMessage_referencesWorkflowEventsContext() {
        assertThat(enforcerMessage)
                .as("Enforcer <message> must reference 'workflow-events' so failures are self-documenting")
                .contains(MSG_FRAGMENT_ADAPTER_MODULES);
    }

    @Test
    @DisplayName("bannedDependencies message references workflow-core as the permitted dependency")
    void enforcerMessage_referencesWorkflowCore() {
        assertThat(enforcerMessage)
                .as(
                        "Enforcer <message> must reference 'workflow-core' to guide developers toward the correct dependency")
                .contains(MSG_FRAGMENT_WORKFLOW_CORE);
    }

    // --- Tests: rule is active ---

    @Test
    @DisplayName("bannedDependencies excludes list is non-empty (rule is active)")
    void bannedDependencies_ruleIsActive() {
        assertThat(excludedArtifacts)
                .as("The bannedDependencies <excludes> list must be non-empty — the Enforcer rule must be active")
                .isNotEmpty();
    }
}
