// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

import groovy.xml.XmlSlurper

String stagedVersion = vertiqueVersion.toString()

[
        "target/generated-sources/annotations/dev/vertique/it/AppComponentVertiqueComponentFactory.java",
        "target/generated-sources/annotations/dev/vertique/it/CodegenApp_JaxRsDescriptor.java",
        "target/generated-sources/annotations/dev/vertique/it/CodegenApp_get_0_ExecutionPlan.java",
        "target/generated-sources/annotations/dev/vertique/it/GeneratedJaxRsResourcesModule.java",
        "target/generated-sources/annotations/dev/vertique/it/DaggerAppComponent.java",
        "target/classes/dev/vertique/it/AppComponentVertiqueComponentFactory.class",
        "target/classes/dev/vertique/it/CodegenApp_JaxRsDescriptor.class",
        "target/classes/dev/vertique/it/CodegenApp_get_0_ExecutionPlan.class",
        "target/classes/dev/vertique/it/GeneratedJaxRsResourcesModule.class",
        "target/classes/dev/vertique/it/DaggerAppComponent.class",
        "target/classes/META-INF/services/dev.vertique.core.VertiqueComponentFactory"
].each { path ->
    assert new File(basedir, path).isFile(): "Missing required generated output ${path}"
}

File effectivePomFile = new File(basedir, "target/effective-pom.xml")
assert effectivePomFile.isFile(): "Missing effective POM"
def effectivePom = new XmlSlurper(false, false).parse(effectivePomFile)
def compilerPlugins = effectivePom.build.plugins.plugin.findAll {
    String groupId = it.groupId.text()
    (groupId.isEmpty() || groupId == "org.apache.maven.plugins")
            && it.artifactId.text() == "maven-compiler-plugin"
}
assert compilerPlugins.size() == 1: "Expected one effective compiler plugin, found ${compilerPlugins.size()}"

def processorPaths = compilerPlugins[0].configuration.annotationProcessorPaths.path
assert processorPaths.every { it.version.text().isEmpty() }:
        "Effective processor paths must remain versionless and use dependency management"
List<String> processorCoordinates =
        processorPaths.collect { "${it.groupId.text()}:${it.artifactId.text()}" }
List<String> expectedProcessorCoordinates = [
        "com.google.dagger:dagger-compiler",
        "dev.vertique:vertique-codegen-all"
]
assert processorCoordinates == expectedProcessorCoordinates:
        "Public parent must expose exactly the versionless managed Dagger and facade coordinates: ${processorCoordinates}"

Map<String, String> expectedManagedVersions = [
        "com.google.dagger:dagger-compiler": "2.60.1",
        "dev.vertique:vertique-codegen-all": stagedVersion
]
def assertManagedVersions = { pom, String modelDescription ->
    expectedManagedVersions.each { String coordinate, String expectedVersion ->
        List<String> parts = coordinate.split(":").toList()
        def matches = pom.dependencyManagement.dependencies.dependency.findAll {
            it.groupId.text() == parts[0] && it.artifactId.text() == parts[1]
        }
        assert matches.size() == 1:
                "${modelDescription} must manage ${coordinate} exactly once, found ${matches.size()}"
        assert matches[0].version.text() == expectedVersion:
                "${modelDescription} manages ${coordinate} at ${matches[0].version.text()} instead of ${expectedVersion}"
    }
}
assertManagedVersions(effectivePom, "Consumer effective POM")

effectivePom.depthFirst().findAll {
    it.name() == "dependency" && it.groupId.text() == "dev.vertique"
}.each {
    assert it.version.text() == stagedVersion:
            "Dependency ${it.artifactId.text()} used ${it.version.text()} instead of ${stagedVersion}"
}

File stagedParentPom = new File(
        localRepositoryPath,
        "dev/vertique/vertique-app-parent/${stagedVersion}/vertique-app-parent-${stagedVersion}.pom")
assert stagedParentPom.isFile(): "Missing staged public parent POM ${stagedParentPom}"
String stagedParentText = stagedParentPom.getText("UTF-8")
def stagedParent = new XmlSlurper(false, false).parse(stagedParentPom)
assert stagedParent.parent.isEmpty(): "Published application parent must not inherit an internal parent"
assert !stagedParentText.contains('${revision}'): "Published parent contains unresolved revision"
assert !stagedParentText.contains('${project.version}'): "Published parent contains unresolved project.version"
def stagedManagedDependencies = stagedParent.dependencyManagement.dependencies.dependency
List<String> stagedManagedCoordinates = stagedManagedDependencies.collect {
    "${it.groupId.text()}:${it.artifactId.text()}:${it.version.text()}:${it.type.text()}:${it.scope.text()}"
}
assert stagedManagedCoordinates == ["dev.vertique:vertique-bom:${stagedVersion}:pom:import"]:
        "Published application parent must manage dependencies only through the concrete BOM import: ${stagedManagedCoordinates}"

def stagedCompilerPlugins = stagedParent.build.plugins.plugin.findAll {
    String groupId = it.groupId.text()
    (groupId.isEmpty() || groupId == "org.apache.maven.plugins")
            && it.artifactId.text() == "maven-compiler-plugin"
}
assert stagedCompilerPlugins.size() == 1:
        "Published parent must retain exactly one compiler plugin configuration"
def stagedCompilerPlugin = stagedCompilerPlugins[0]
assert stagedCompilerPlugin.version.text() == "3.15.0":
        "Published parent must pin Maven Compiler Plugin 3.15.0"
assert stagedCompilerPlugin.dependencies.dependency.isEmpty():
        "Published parent compiler plugin must not declare plugin dependencies"
assert stagedCompilerPlugin.configuration.children().collect { it.name() } == ["annotationProcessorPaths"]:
        "Published parent compiler configuration must contain only annotationProcessorPaths"
def stagedProcessorPaths = stagedCompilerPlugin.configuration.annotationProcessorPaths.path
assert stagedProcessorPaths.every { it.version.text().isEmpty() }:
        "Published parent processor paths must remain versionless and use dependency management"
List<String> stagedProcessorCoordinates =
        stagedProcessorPaths.collect {
            "${it.groupId.text()}:${it.artifactId.text()}"
        }
assert stagedProcessorCoordinates == expectedProcessorCoordinates:
        "Published parent compiler paths must be exactly versionless managed Dagger and facade: ${stagedProcessorCoordinates}"
assert stagedParent.properties.'maven.compiler.release'.text() == "21":
        "Published parent must set Java release 21"
assert stagedParent.properties.'project.build.sourceEncoding'.text() == "UTF-8":
        "Published parent must set UTF-8 source encoding"
assert stagedParent.properties.'project.reporting.outputEncoding'.text() == "UTF-8":
        "Published parent must set UTF-8 reporting encoding"

// The application parent is a third-party contract published exactly as
// authored: a literal version, the compiler wiring, a formatter for the
// application to use, and none of the framework's release machinery.
List<String> stagedPluginCoordinates = stagedParent.build.plugins.plugin.collect {
    "${it.groupId.text()}:${it.artifactId.text()}"
}
assert stagedPluginCoordinates == [
        "org.apache.maven.plugins:maven-compiler-plugin",
        "com.diffplug.spotless:spotless-maven-plugin"
]: "Published parent must declare exactly the compiler and formatter plugins, found ${stagedPluginCoordinates}"
assert stagedParent.properties.revision.isEmpty():
        "Published parent must not declare a CI-friendly revision property"
assert stagedParent.version.text() == stagedVersion:
        "Published parent must carry the literal version ${stagedVersion}, found ${stagedParent.version.text()}"

assert effectivePom.build.plugins.plugin.findAll { it.artifactId.text() == "flatten-maven-plugin" }.isEmpty():
        "Consumer effective POM must not inherit the framework's flatten plugin"
def inheritedSpotless = effectivePom.build.plugins.plugin.findAll { it.artifactId.text() == "spotless-maven-plugin" }
assert inheritedSpotless.size() == 1:
        "Consumer must inherit the formatter from the published parent, found ${inheritedSpotless.size()}"
assert !inheritedSpotless[0].configuration.java.palantirJavaFormat.isEmpty():
        "Inherited formatter must carry the framework style"
assert inheritedSpotless[0].executions.execution.isEmpty():
        "Inherited formatter must not be bound to the consumer's lifecycle"

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
List<String> materializedProcessors =
        materializedProcessorPath.split(java.util.regex.Pattern.quote(File.pathSeparator)).toList()
List<String> materializedProcessorNames = materializedProcessors.collect { new File(it).name }
assert materializedProcessorNames.count("dagger-compiler-2.60.1.jar") == 1:
        "Materialized processor path must contain Dagger exactly once: ${materializedProcessorNames}"
assert materializedProcessorNames.count("vertique-codegen-all-${stagedVersion}.jar") == 1:
        "Materialized processor path must contain the facade exactly once: ${materializedProcessorNames}"
assert !materializedProcessorNames.any { it.startsWith("lombok-") }:
        "Materialized processor path resolves Lombok without explicit opt-in: ${materializedProcessorNames}"

["compile", "runtime", "test"].each { scope ->
    File classpathFile = new File(basedir, "target/${scope}-classpath.txt")
    assert classpathFile.isFile(): "Missing materialized ${scope} classpath"
    String classpath = classpathFile.getText("UTF-8")
    List<String> artifacts = classpath.split(java.util.regex.Pattern.quote(File.pathSeparator)).toList()
    assert !artifacts.any { new File(it).name.startsWith("vertique-codegen-") }:
            "${scope} classpath leaks a Vertique codegen JAR: ${artifacts}"
    assert !artifacts.any { new File(it).name.startsWith("lombok-") }:
            "${scope} classpath resolves Lombok without explicit opt-in: ${classpath}"
}

File pluginArtifactsFile = new File(basedir, "target/plugin-artifacts.txt")
assert pluginArtifactsFile.isFile(): "Missing resolved plugin artifact report"
String pluginArtifacts = pluginArtifactsFile.getText("UTF-8")
assert !pluginArtifacts.contains("org.projectlombok:lombok"):
        "Build plugins resolve Lombok without explicit opt-in: ${pluginArtifacts}"
assert !pluginArtifacts.contains("${File.separator}lombok-"):
        "Build plugin artifact paths contain Lombok without explicit opt-in: ${pluginArtifacts}"

return true
