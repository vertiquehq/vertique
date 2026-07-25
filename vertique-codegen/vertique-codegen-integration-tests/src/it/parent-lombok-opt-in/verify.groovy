// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

import groovy.xml.XmlSlurper

File lombokClass = new File(basedir, "target/classes/dev/vertique/it/LombokApp.class")
assert lombokClass.isFile(): "Lombok opt-in fixture did not compile"

File effectivePomFile = new File(basedir, "target/effective-pom.xml")
assert effectivePomFile.isFile(): "Missing effective POM"
def effectivePom = new XmlSlurper(false, false).parse(effectivePomFile)
def compilerPlugins = effectivePom.build.plugins.plugin.findAll {
    it.groupId.text() == "org.apache.maven.plugins" && it.artifactId.text() == "maven-compiler-plugin"
}
assert compilerPlugins.size() == 1: "Expected one effective compiler plugin"

def processorPaths = compilerPlugins[0].configuration.annotationProcessorPaths.path
assert processorPaths.every { it.version.text().isEmpty() }:
        "Lombok opt-in processor paths must remain versionless and use dependency management"
List<String> processorCoordinates =
        processorPaths.collect {
            "${it.groupId.text()}:${it.artifactId.text()}"
        }
List<String> expectedProcessorCoordinates = [
        "com.google.dagger:dagger-compiler",
        "dev.vertique:vertique-codegen-all",
        "org.projectlombok:lombok"
]
assert processorCoordinates == expectedProcessorCoordinates:
        "Lombok opt-in must retain exactly versionless managed Dagger, facade, and Lombok coordinates: ${processorCoordinates}"

Map<String, String> expectedManagedVersions = [
        "com.google.dagger:dagger-compiler": "2.59.2",
        "dev.vertique:vertique-codegen-all": vertiqueVersion.toString(),
        "org.projectlombok:lombok": "1.18.42"
]
expectedManagedVersions.each { String coordinate, String expectedVersion ->
    List<String> parts = coordinate.split(":").toList()
    def matches = effectivePom.dependencyManagement.dependencies.dependency.findAll {
        it.groupId.text() == parts[0] && it.artifactId.text() == parts[1]
    }
    assert matches.size() == 1:
            "Consumer effective POM must manage ${coordinate} exactly once, found ${matches.size()}"
    assert matches[0].version.text() == expectedVersion:
            "Consumer effective POM manages ${coordinate} at ${matches[0].version.text()} instead of ${expectedVersion}"
}

File targetDirectory = new File(basedir, "target")
File javacDebugScript = new File(targetDirectory, "javac.sh")
assert javacDebugScript.isFile():
        "Forked compiler debug output must retain target/javac.sh"
List<File> javacArgumentFiles = targetDirectory.listFiles().findAll {
    it.isFile()
            && it.name.startsWith("org.codehaus.plexus.compiler.javac.JavacCompiler")
            && it.name.endsWith("arguments")
}
assert javacArgumentFiles.size() == 1:
        "Expected one retained forked-javac argument file, found ${javacArgumentFiles}"
List<String> javacArguments = javacArgumentFiles[0].readLines("UTF-8").collect {
    String argument = it.trim()
    if (argument.startsWith('"') && argument.endsWith('"')) {
        return argument.substring(1, argument.length() - 1)
    }
    return argument
}
int processorPathOption = javacArguments.findIndexOf {
    it == "-processorpath" || it == "--processor-path"
}
assert processorPathOption >= 0 && processorPathOption + 1 < javacArguments.size():
        "Captured javac arguments do not contain a materialized processor path: ${javacArguments}"
String materializedProcessorPath = javacArguments[processorPathOption + 1]
List<String> materializedProcessorNames =
        materializedProcessorPath
                .split(java.util.regex.Pattern.quote(File.pathSeparator))
                .collect { new File(it).name }
assert materializedProcessorNames.count("dagger-compiler-2.59.2.jar") == 1:
        "Materialized processor path must contain Dagger exactly once: ${materializedProcessorNames}"
assert materializedProcessorNames.count("vertique-codegen-all-${vertiqueVersion}.jar") == 1:
        "Materialized processor path must contain the facade exactly once: ${materializedProcessorNames}"
assert materializedProcessorNames.count("lombok-1.18.42.jar") == 1:
        "Materialized processor path must contain explicitly opted-in Lombok exactly once: ${materializedProcessorNames}"

return true
