// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

File classes = new File(basedir, "target/classes")
assert new File(classes, "dev/vertique/it/PlainApp.class").isFile()

File generatedSources = new File(basedir, "target/generated-sources/annotations")
List<File> generatedJava = []
if (generatedSources.isDirectory()) {
    generatedSources.eachFileRecurse { file ->
        if (file.isFile() && file.name.endsWith(".java")) {
            generatedJava.add(file)
        }
    }
}
assert generatedJava.isEmpty(): "Plain Java fixture generated unexpected sources: ${generatedJava}"

return true
