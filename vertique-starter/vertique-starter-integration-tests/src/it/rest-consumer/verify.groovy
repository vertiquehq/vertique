// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

import groovy.xml.XmlSlurper

// --- Compile-time proof: the public processors ran and produced the application factory ---

[
        "target/generated-sources/annotations/dev/vertique/it/rest/RestStarterConsumerVertiqueComponentFactory.java",
        "target/generated-sources/annotations/dev/vertique/it/rest/DaggerRestStarterConsumer.java",
        "target/classes/dev/vertique/it/rest/RestStarterConsumerVertiqueComponentFactory.class",
        "target/classes/dev/vertique/it/rest/DaggerRestStarterConsumer.class",
        "target/classes/META-INF/services/dev.vertique.core.VertiqueComponentFactory",
        "target/test-classes/dev/vertique/it/rest/RestStarterConsumerTest.class"
].each { String path ->
    assert new File(basedir, path).isFile(): "Missing required fixture output ${path}"
}

// --- Test proof: the consumer's four contract tests ran and passed ---

File surefireReport =
        new File(basedir, "target/surefire-reports/TEST-dev.vertique.it.rest.RestStarterConsumerTest.xml")
assert surefireReport.isFile(): "Missing surefire report for RestStarterConsumerTest"
def testSuite = new XmlSlurper(false, false).parse(surefireReport)
assert testSuite.@tests.text() == "4":
        "RestStarterConsumerTest must run exactly four tests, ran ${testSuite.@tests.text()}"
assert testSuite.@failures.text() == "0": "RestStarterConsumerTest reported failures"
assert testSuite.@errors.text() == "0": "RestStarterConsumerTest reported errors"
assert testSuite.@skipped.text() == "0": "RestStarterConsumerTest skipped tests"

// --- Dependency ledger: exactly the REST starter closure, nothing else ---

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
        // The REST starter itself plus its exact direct ledger.
        "dev.vertique:vertique-starter-rest",
        "dev.vertique:vertique-starter-core",
        "dev.vertique:vertique-management",
        "dev.vertique:vertique-rest-jaxrs",
        "dev.vertique:vertique-rest-security",
        "dev.vertique:vertique-rest-validation",
        "com.google.dagger:dagger",
        // The core starter closure the REST starter transitively requires.
        "dev.vertique:vertique-application",
        "dev.vertique:vertique-core",
        "dev.vertique:vertique-config-core",
        "dev.vertique:vertique-deploy"
].each { String required ->
    assert classpathCoordinates.contains(required):
            "REST starter closure must supply ${required} at compile/runtime scope: ${classpathCoordinates}"
}

Map<String, Closure<Boolean>> forbidden = [
        "vertique-launcher"               : { String a -> a == "vertique-launcher" },
        "vertique-application-test"       : { String a -> a == "vertique-application-test" },
        "vertique-codegen artifacts"      : { String a -> a.startsWith("vertique-codegen") },
        "other vertique-starter artifacts": { String a ->
            a.startsWith("vertique-starter") && a != "vertique-starter-rest" && a != "vertique-starter-core"
        },
        "vertique-services"               : { String a -> a == "vertique-services" },
        "vertique-db artifacts"           : { String a -> a.startsWith("vertique-db") }
]

List<String> vertiqueOnClasspath =
        classpath.findAll { it.groupId == "dev.vertique" }.collect { it.artifactId.toString() }
forbidden.each { String description, Closure<Boolean> matches ->
    List<String> leaked = vertiqueOnClasspath.findAll { matches(it) }
    assert leaked.isEmpty():
            "REST starter leaks ${description} onto the compile/runtime classpath: ${leaked}"
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
        "vertique-input-processing",
        "vertique-json",
        "vertique-json-schema",
        "vertique-logging",
        "vertique-management",
        "vertique-rest-core",
        "vertique-rest-jaxrs",
        "vertique-rest-security",
        "vertique-rest-validation",
        "vertique-security-core",
        "vertique-security-runtime",
        "vertique-starter-core",
        "vertique-starter-rest"
] as Set
Set<String> actualVertique = vertiqueOnClasspath.toSet()
assert actualVertique == expectedVertique:
        "REST starter dev.vertique closure drifted from the frozen ledger: " +
                "unexpected=${actualVertique - expectedVertique}, missing=${expectedVertique - actualVertique}"

// Token-mechanism neutrality: the starter selects no token mechanism for the consumer, so no
// artifact of the JWT/JOSE token-mechanism category may reach the compile or runtime classpath,
// from any group.
List<String> tokenMechanismOnClasspath =
        classpathCoordinates.findAll { it =~ /(?i)(jwt|jose|jwks)/ }.toList()
assert tokenMechanismOnClasspath.isEmpty():
        "Starter leaks JWT/JOSE token-mechanism artifacts onto the compile/runtime classpath: ${tokenMechanismOnClasspath}"

return true
