// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

import groovy.xml.XmlSlurper

// --- Compile-time proof: the public processors ran and produced the application factory ---

[
        "target/generated-sources/annotations/dev/vertique/it/services/ServicesStarterConsumerVertiqueComponentFactory.java",
        "target/generated-sources/annotations/dev/vertique/it/services/DaggerServicesStarterConsumer.java",
        "target/classes/dev/vertique/it/services/ServicesStarterConsumerVertiqueComponentFactory.class",
        "target/classes/dev/vertique/it/services/DaggerServicesStarterConsumer.class",
        "target/classes/META-INF/services/dev.vertique.core.VertiqueComponentFactory",
        "target/test-classes/dev/vertique/it/services/ServicesStarterConsumerTest.class"
].each { String path ->
    assert new File(basedir, path).isFile(): "Missing required fixture output ${path}"
}

// --- Test proof: the consumer's four contract tests ran and passed ---

File surefireReport =
        new File(basedir, "target/surefire-reports/TEST-dev.vertique.it.services.ServicesStarterConsumerTest.xml")
assert surefireReport.isFile(): "Missing surefire report for ServicesStarterConsumerTest"
def testSuite = new XmlSlurper(false, false).parse(surefireReport)
assert testSuite.@tests.text() == "4":
        "ServicesStarterConsumerTest must run exactly four tests, ran ${testSuite.@tests.text()}"
assert testSuite.@failures.text() == "0": "ServicesStarterConsumerTest reported failures"
assert testSuite.@errors.text() == "0": "ServicesStarterConsumerTest reported errors"
assert testSuite.@skipped.text() == "0": "ServicesStarterConsumerTest skipped tests"

// --- Dependency ledger: exactly the services starter closure, nothing else ---

File dependencyList = new File(basedir, "target/dependency-list.txt")
assert dependencyList.isFile(): "Missing materialized dependency list"

List<Map<String, String>> resolved = []
dependencyList.readLines("UTF-8").each { String rawLine ->
    String line = rawLine.trim()
    if (line.isEmpty()) {
        return
    }
    List<String> coordinate = line.split(/\s+/)[0].split(":").toList()
    if (coordinate.size() < 4) {
        return
    }
    resolved.add([groupId: coordinate[0], artifactId: coordinate[1], scope: coordinate[-1]])
}
assert !resolved.isEmpty(): "Dependency list contained no resolvable coordinates"

List<Map<String, String>> classpath = resolved.findAll { it.scope == "compile" || it.scope == "runtime" }
Set<String> classpathCoordinates =
        classpath.collect { "${it.groupId}:${it.artifactId}".toString() }.toSet()

[
        // The services starter itself plus its exact direct ledger.
        "dev.vertique:vertique-starter-services",
        "dev.vertique:vertique-starter-core",
        "dev.vertique:vertique-management",
        "dev.vertique:vertique-services",
        "com.google.dagger:dagger",
        // The core starter closure the services starter transitively requires.
        "dev.vertique:vertique-application",
        "dev.vertique:vertique-core",
        "dev.vertique:vertique-config-core",
        "dev.vertique:vertique-deploy"
].each { String required ->
    assert classpathCoordinates.contains(required):
            "Services starter closure must supply ${required} at compile/runtime scope: ${classpathCoordinates}"
}

Map<String, Closure<Boolean>> forbidden = [
        "vertique-launcher"               : { String a -> a == "vertique-launcher" },
        "vertique-application-test"       : { String a -> a == "vertique-application-test" },
        "vertique-codegen artifacts"      : { String a -> a.startsWith("vertique-codegen") },
        "other vertique-starter artifacts": { String a ->
            a.startsWith("vertique-starter") && a != "vertique-starter-services" && a != "vertique-starter-core"
        },
        "vertique-rest artifacts"         : { String a -> a.startsWith("vertique-rest") },
        "vertique-db artifacts"           : { String a -> a.startsWith("vertique-db") }
]

List<String> vertiqueOnClasspath =
        classpath.findAll { it.groupId == "dev.vertique" }.collect { it.artifactId.toString() }
forbidden.each { String description, Closure<Boolean> matches ->
    List<String> leaked = vertiqueOnClasspath.findAll { matches(it) }
    assert leaked.isEmpty():
            "Services starter leaks ${description} onto the compile/runtime classpath: ${leaked}"
}

// The exact dev.vertique closure this starter puts on a consumer's compile/runtime classpath. This
// set is the release line's compatibility surface: any addition or removal is consumer-visible and
// must be a deliberate, reviewed change to the ledger below.
Set<String> expectedVertique = [
        "vertique-application",
        "vertique-config-core",
        "vertique-context",
        "vertique-core",
        "vertique-correlation",
        "vertique-deploy",
        "vertique-json",
        "vertique-logging",
        "vertique-management",
        "vertique-security-core",
        "vertique-security-runtime",
        "vertique-services",
        "vertique-starter-core",
        "vertique-starter-services"
] as Set
Set<String> actualVertique = vertiqueOnClasspath.toSet()
assert actualVertique == expectedVertique:
        "Services starter dev.vertique closure drifted from the frozen ledger: " +
                "unexpected=${actualVertique - expectedVertique}, missing=${expectedVertique - actualVertique}"

// Token-mechanism neutrality: the starter selects no token mechanism for the consumer, so no
// artifact of the JWT/JOSE token-mechanism category may reach the compile or runtime classpath,
// from any group.
List<String> tokenMechanismOnClasspath =
        classpathCoordinates.findAll { it =~ /(?i)(jwt|jose|jwks)/ }.toList()
assert tokenMechanismOnClasspath.isEmpty():
        "Starter leaks JWT/JOSE token-mechanism artifacts onto the compile/runtime classpath: ${tokenMechanismOnClasspath}"

// No database test infrastructure may reach the bounded services starter closure either.
List<String> testcontainersOnClasspath =
        classpath.findAll { it.groupId.startsWith("org.testcontainers") }
                .collect { "${it.groupId}:${it.artifactId}".toString() }
assert testcontainersOnClasspath.isEmpty():
        "Services starter leaks Testcontainers artifacts onto the compile/runtime classpath: ${testcontainersOnClasspath}"

return true
