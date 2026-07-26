// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

import groovy.xml.XmlSlurper

// --- Compile-time proof: the aggregate is consumable with no application framework present ---
//
// This fixture has no @VertiqueApp component in production sources, so no application factory is
// generated there. The Dagger graph proof lives in test sources, where the test-local component is
// composed with the test-scope core starter.

[
        "target/classes/dev/vertique/it/postgresql/PostgresqlStarterConsumer.class",
        "target/generated-test-sources/test-annotations/dev/vertique/it/postgresql/DaggerPostgresqlStarterConsumerTest_PersistenceGraph.java",
        "target/test-classes/dev/vertique/it/postgresql/DaggerPostgresqlStarterConsumerTest_PersistenceGraph.class",
        "target/test-classes/dev/vertique/it/postgresql/PostgresqlStarterConsumerTest.class"
].each { String path ->
    assert new File(basedir, path).isFile(): "Missing required fixture output ${path}"
}

// No application factory service file may exist: this consumer composes no application.
assert !new File(basedir, "target/classes/META-INF/services/dev.vertique.core.VertiqueComponentFactory").exists():
        "PostgreSQL consumer must not register an application component factory"

// --- Test proof: the consumer's four contract tests ran and passed ---

File surefireReport =
        new File(basedir, "target/surefire-reports/TEST-dev.vertique.it.postgresql.PostgresqlStarterConsumerTest.xml")
assert surefireReport.isFile(): "Missing surefire report for PostgresqlStarterConsumerTest"
def testSuite = new XmlSlurper(false, false).parse(surefireReport)
assert testSuite.@tests.text() == "4":
        "PostgresqlStarterConsumerTest must run exactly four tests, ran ${testSuite.@tests.text()}"
assert testSuite.@failures.text() == "0": "PostgresqlStarterConsumerTest reported failures"
assert testSuite.@errors.text() == "0": "PostgresqlStarterConsumerTest reported errors"
assert testSuite.@skipped.text() == "0": "PostgresqlStarterConsumerTest skipped tests"

// --- Dependency ledger: exactly the PostgreSQL starter closure, nothing else ---

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
        // The PostgreSQL starter itself plus its exact direct ledger.
        "dev.vertique:vertique-starter-postgresql",
        "dev.vertique:vertique-db-core",
        "dev.vertique:vertique-db-postgresql",
        "dev.vertique:vertique-db-flyway",
        "com.google.dagger:dagger",
        // The database closure the starter transitively requires.
        "dev.vertique:vertique-core",
        "io.vertx:vertx-sql-client",
        "io.vertx:vertx-pg-client",
        "org.flywaydb:flyway-core",
        "org.postgresql:postgresql"
].each { String required ->
    assert classpathCoordinates.contains(required):
            "PostgreSQL starter closure must supply ${required} at compile/runtime scope: ${classpathCoordinates}"
}

// The core starter and the application runtime are graph-composition fixtures, so they must appear
// at test scope only — never on the production classpath of a persistence-only consumer.
Set<String> testScopedVertique = resolved.findAll { it.groupId == "dev.vertique" && it.scope == "test" }
        .collect { it.artifactId.toString() }
        .toSet()
[
        "vertique-starter-core",
        "vertique-application"
].each { String staged ->
    assert testScopedVertique.contains(staged):
            "Graph-composition fixture ${staged} must resolve at test scope: ${testScopedVertique}"
}

Map<String, Closure<Boolean>> forbidden = [
        "vertique-launcher"               : { String a -> a == "vertique-launcher" },
        "vertique-application-test"       : { String a -> a == "vertique-application-test" },
        "vertique-codegen artifacts"      : { String a -> a.startsWith("vertique-codegen") },
        "other vertique-starter artifacts": { String a ->
            a.startsWith("vertique-starter") && a != "vertique-starter-postgresql"
        },
        "vertique-application"            : { String a -> a == "vertique-application" },
        "vertique-management"             : { String a -> a == "vertique-management" },
        "vertique-services"               : { String a -> a == "vertique-services" },
        "vertique-rest artifacts"         : { String a -> a.startsWith("vertique-rest") }
]

List<String> vertiqueOnClasspath =
        classpath.findAll { it.groupId == "dev.vertique" }.collect { it.artifactId.toString() }
forbidden.each { String description, Closure<Boolean> matches ->
    List<String> leaked = vertiqueOnClasspath.findAll { matches(it) }
    assert leaked.isEmpty():
            "PostgreSQL starter leaks ${description} onto the compile/runtime classpath: ${leaked}"
}

// The starter ships production persistence, never database test infrastructure: no Testcontainers
// artifact from any group may reach the compile or runtime classpath.
List<String> testcontainersOnClasspath =
        classpath.findAll { it.groupId.startsWith("org.testcontainers") }
                .collect { "${it.groupId}:${it.artifactId}".toString() }
assert testcontainersOnClasspath.isEmpty():
        "PostgreSQL starter leaks Testcontainers artifacts onto the compile/runtime classpath: ${testcontainersOnClasspath}"

return true
