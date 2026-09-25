// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

File generatedSources = new File(basedir, "target/generated-sources/annotations")
List<String> expected = [
        "dev/vertique/it/GeneratedJaxRsResourcesModule.java",
        "dev/vertique/it/GeneratedJaxRsResourcesModule_SingleFeatureAppBindingFactory.java",
        "dev/vertique/it/GeneratedJaxRsResourcesModule_SingleFeatureAppEntryFactory.java",
        "dev/vertique/it/SingleFeatureApp_Factory.java",
        "dev/vertique/it/SingleFeatureApp_JaxRsDescriptor.java",
        "dev/vertique/it/SingleFeatureApp_get_0_ExecutionPlan.java"
]
expected.each { relativePath ->
    assert new File(generatedSources, relativePath).isFile(): "Missing expected JAX-RS output ${relativePath}"
}

List<String> actual = []
generatedSources.eachFileRecurse { file ->
    if (file.isFile()) {
        String relativePath = generatedSources.toPath().relativize(file.toPath()).toString()
        actual.add(relativePath.replace(File.separatorChar, '/' as char))
    }
}
actual.sort()
assert actual == expected.sort(): "Single-feature fixture generated unrelated outputs: ${actual}"

expected.collect { it.replace(".java", ".class") }.each { relativePath ->
    assert new File(basedir, "target/classes/${relativePath}").isFile():
            "Missing compiled generated class ${relativePath}"
}

return true
