package com.quietread.app.epub

import java.io.File

object EpubResourceResolver {
    fun resolve(contentRoot: File, relativePath: String): File? {
        if (relativePath.isBlank()) return null
        return runCatching {
            val root = contentRoot.canonicalFile
            val resource = File(root, relativePath).canonicalFile
            resource.takeIf {
                it != root &&
                    it.path.startsWith(root.path + File.separator) &&
                    it.isFile
            }
        }.getOrNull()
    }
}
