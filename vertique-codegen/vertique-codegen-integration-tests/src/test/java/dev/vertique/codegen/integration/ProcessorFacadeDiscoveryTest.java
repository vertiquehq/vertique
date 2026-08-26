// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.processing.Processor;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Contract tests for the dependency-only {@code vertique-codegen-all} processor facade.
 *
 * <p>The integration-test module depends only on the facade and discovers the processor leaves
 * through its transitive dependency graph. The POM assertion freezes that graph independently of
 * service discovery.
 */
class ProcessorFacadeDiscoveryTest {

    private static final Path FACADE_POM =
            Path.of("..", "vertique-codegen-all", "pom.xml").toAbsolutePath().normalize();

    private static final List<FacadeLeaf> FROZEN_LEAVES = List.of(
            new FacadeLeaf("vertique-codegen-application", "dev.vertique.codegen.application.VertiqueAppProcessor"),
            new FacadeLeaf("vertique-codegen-dagger", "dev.vertique.codegen.dagger.processor.AutoWireProcessor"),
            new FacadeLeaf("vertique-codegen-jaxrs", "dev.vertique.codegen.jaxrs.JaxRsPipelineProcessor"),
            new FacadeLeaf(
                    "vertique-codegen-rest-client", "dev.vertique.codegen.rest.client.processor.RestClientProcessor"),
            new FacadeLeaf(
                    "vertique-codegen-services", "dev.vertique.codegen.services.processor.ServiceContractProcessor"),
            new FacadeLeaf("vertique-codegen-kafka", "dev.vertique.codegen.kafka.processor.KafkaConsumerProcessor"),
            new FacadeLeaf(
                    "vertique-codegen-delayed-job",
                    "dev.vertique.codegen.delayed.processor.DelayedJobContractProcessor"),
            new FacadeLeaf(
                    "vertique-codegen-workflow", "dev.vertique.codegen.workflow.processor.WorkflowContractProcessor"),
            new FacadeLeaf("vertique-codegen-cron", "dev.vertique.codegen.cron.processor.CronJobProcessor"),
            new FacadeLeaf(
                    "vertique-codegen-sanitization",
                    "dev.vertique.codegen.sanitization.processor.SanitizationProcessor"),
            new FacadeLeaf("vertique-codegen-aop", "dev.vertique.codegen.aop.AopProcessor"),
            new FacadeLeaf("vertique-codegen-events", "dev.vertique.codegen.events.EventsProcessor"),
            new FacadeLeaf("vertique-cache-codegen", "dev.vertique.cache.codegen.CacheAnnotationProcessor"));

    @Test
    void facadePomDeclaresExactlyFrozenLeaves() throws Exception {
        requireFacadePom();

        Element project = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(FACADE_POM.toFile())
                .getDocumentElement();
        Element dependencies = directChild(project, "dependencies");
        assertTrue(dependencies != null, "Facade POM must declare a direct <dependencies> block");

        List<PomDependency> actual = directChildren(dependencies, "dependency").stream()
                .map(ProcessorFacadeDiscoveryTest::pomDependency)
                .toList();
        List<PomDependency> expected = FROZEN_LEAVES.stream()
                .map(leaf -> new PomDependency("dev.vertique", leaf.artifactId(), "compile", true))
                .toList();

        assertEquals(
                expected,
                actual,
                "Facade dependencies must be exactly the frozen ordered compile-scope leaves"
                        + " and must exclude transitive Lombok activation");
    }

    @Test
    void discoversExactlyAllProductionProcessors() {
        requireFacadePom();

        Map<String, Long> expected = FROZEN_LEAVES.stream()
                .map(FacadeLeaf::processorClassName)
                .collect(Collectors.toMap(Function.identity(), ignored -> 1L, Long::sum, LinkedHashMap::new));
        Map<String, Long> discovered = ServiceLoader.load(
                        Processor.class, getClass().getClassLoader())
                .stream()
                .map(provider -> provider.type().getName())
                .collect(Collectors.toMap(Function.identity(), ignored -> 1L, Long::sum, LinkedHashMap::new));

        assertEquals(expected, discovered, "Each frozen facade processor must be discoverable exactly once");
    }

    @Test
    void allProcessorsIgnoreEmptyCompilation() {
        requireFacadePom();

        List<Processor> processors = facadeProcessors();
        ProcessorTestHarness.Result result =
                ProcessorTestHarness.run(processors, SourceFiles.inline("dev.vertique.test.EmptyApplication", """
                        package dev.vertique.test;

                        public final class EmptyApplication {
                            private EmptyApplication() {}
                        }
                        """));

        result.assertSuccess();
        assertTrue(
                result.compilation().generatedSourceFiles().isEmpty(),
                () -> "Empty compilation must not generate Vertique sources, but generated "
                        + result.compilation().generatedSourceFiles());
    }

    private static List<Processor> facadeProcessors() {
        Map<String, List<Processor>> processorsByClass =
                ServiceLoader.load(Processor.class, ProcessorFacadeDiscoveryTest.class.getClassLoader()).stream()
                        .map(ServiceLoader.Provider::get)
                        .filter(processor -> FROZEN_LEAVES.stream().anyMatch(leaf -> leaf.processorClassName()
                                .equals(processor.getClass().getName())))
                        .collect(Collectors.groupingBy(
                                processor -> processor.getClass().getName(), LinkedHashMap::new, Collectors.toList()));

        List<Processor> processors = new ArrayList<>(FROZEN_LEAVES.size());
        for (FacadeLeaf leaf : FROZEN_LEAVES) {
            List<Processor> matches = processorsByClass.getOrDefault(leaf.processorClassName(), List.of());
            assertEquals(1, matches.size(), leaf.processorClassName() + " must be discoverable exactly once");
            processors.add(matches.getFirst());
        }
        return List.copyOf(processors);
    }

    private static void requireFacadePom() {
        assertTrue(
                Files.isRegularFile(FACADE_POM),
                () -> "Expected dependency-only processor facade POM at " + FACADE_POM);
    }

    private static PomDependency pomDependency(Element dependency) {
        String scope = directChildText(dependency, "scope");
        return new PomDependency(
                directChildText(dependency, "groupId"),
                directChildText(dependency, "artifactId"),
                scope.isBlank() ? "compile" : scope,
                excludesLombok(dependency));
    }

    private static boolean excludesLombok(Element dependency) {
        Element exclusions = directChild(dependency, "exclusions");
        if (exclusions == null) {
            return false;
        }
        return directChildren(exclusions, "exclusion").stream()
                .anyMatch(exclusion -> "org.projectlombok".equals(directChildText(exclusion, "groupId"))
                        && "lombok".equals(directChildText(exclusion, "artifactId")));
    }

    private static String directChildText(Element parent, String localName) {
        Element child = directChild(parent, localName);
        return child == null ? "" : child.getTextContent().trim();
    }

    private static Element directChild(Element parent, String localName) {
        return directChildren(parent, localName).stream().findFirst().orElse(null);
    }

    private static List<Element> directChildren(Element parent, String localName) {
        List<Element> matches = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element
                    && (localName.equals(element.getLocalName()) || localName.equals(element.getNodeName()))) {
                matches.add(element);
            }
        }
        return List.copyOf(matches);
    }

    private record FacadeLeaf(String artifactId, String processorClassName) {}

    private record PomDependency(String groupId, String artifactId, String scope, boolean excludesLombok) {}
}
