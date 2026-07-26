// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

import groovy.xml.XmlSlurper

// --- Compile-time proof: the public processors ran and produced the application factory ---

[
        "target/generated-sources/annotations/dev/vertique/it/core/CoreStarterConsumerVertiqueComponentFactory.java",
        "target/generated-sources/annotations/dev/vertique/it/core/DaggerCoreStarterConsumer.java",
        "target/classes/dev/vertique/it/core/CoreStarterConsumerVertiqueComponentFactory.class",
        "target/classes/dev/vertique/it/core/DaggerCoreStarterConsumer.class",
        "target/classes/META-INF/services/dev.vertique.core.VertiqueComponentFactory",
        "target/test-classes/dev/vertique/it/core/CoreStarterConsumerTest.class"
].each { String path ->
    assert new File(basedir, path).isFile(): "Missing required fixture output ${path}"
}

// --- Test proof: the consumer's four contract tests ran and passed ---

File surefireReport =
        new File(basedir, "target/surefire-reports/TEST-dev.vertique.it.core.CoreStarterConsumerTest.xml")
assert surefireReport.isFile(): "Missing surefire report for CoreStarterConsumerTest"
def testSuite = new XmlSlurper(false, false).parse(surefireReport)
assert testSuite.@tests.text() == "4":
        "CoreStarterConsumerTest must run exactly four tests, ran ${testSuite.@tests.text()}"
assert testSuite.@failures.text() == "0": "CoreStarterConsumerTest reported failures"
assert testSuite.@errors.text() == "0": "CoreStarterConsumerTest reported errors"
assert testSuite.@skipped.text() == "0": "CoreStarterConsumerTest skipped tests"

// --- Dependency ledger: exactly the core starter closure, nothing else ---

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
        "dev.vertique:vertique-starter-core",
        "dev.vertique:vertique-application",
        "dev.vertique:vertique-core",
        "dev.vertique:vertique-config-core",
        "dev.vertique:vertique-deploy",
        "com.google.dagger:dagger"
].each { String required ->
    assert classpathCoordinates.contains(required):
            "Core starter closure must supply ${required} at compile/runtime scope: ${classpathCoordinates}"
}

Map<String, Closure<Boolean>> forbidden = [
        "vertique-launcher"                : { String a -> a == "vertique-launcher" },
        "vertique-application-test"        : { String a -> a == "vertique-application-test" },
        "vertique-codegen artifacts"       : { String a -> a.startsWith("vertique-codegen") },
        "other vertique-starter artifacts" : { String a -> a.startsWith("vertique-starter") && a != "vertique-starter-core" },
        "vertique-rest artifacts"          : { String a -> a.startsWith("vertique-rest") },
        "vertique-services"                : { String a -> a == "vertique-services" },
        "vertique-management"              : { String a -> a == "vertique-management" },
        "vertique-db artifacts"            : { String a -> a.startsWith("vertique-db") }
]

List<String> vertiqueOnClasspath =
        classpath.findAll { it.groupId == "dev.vertique" }.collect { it.artifactId.toString() }
forbidden.each { String description, Closure<Boolean> matches ->
    List<String> leaked = vertiqueOnClasspath.findAll { matches(it) }
    assert leaked.isEmpty():
            "Core starter leaks ${description} onto the compile/runtime classpath: ${leaked}"
}

return true
