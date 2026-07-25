// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

Map<String, byte[]> generatedTree(File root) {
    assert root.isDirectory(): "Missing generated source tree ${root}"
    Map<String, byte[]> files = new TreeMap<>()
    root.eachFileRecurse { file ->
        if (file.isFile()) {
            files.put(root.toPath().relativize(file.toPath()).toString(), file.bytes)
        }
    }
    return files
}

Map<String, byte[]> parentTree = generatedTree(new File(basedir, "comparison/parent"))
Map<String, byte[]> manualTree = generatedTree(new File(basedir, "comparison/manual"))

assert !parentTree.isEmpty(): "Parent compilation generated no sources"
assert parentTree.keySet() == manualTree.keySet():
        "Generated source paths differ: parent=${parentTree.keySet()}, manual=${manualTree.keySet()}"
parentTree.each { path, parentBytes ->
    assert Arrays.equals(parentBytes, manualTree[path]):
            "Generated source differs between parent and manual processor paths: ${path}"
}

return true
