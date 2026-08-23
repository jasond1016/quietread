package com.quietread.app.epub

import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

object EpubExtractor {
    private const val MAX_ENTRIES = 10_000
    private const val MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L

    fun extract(epub: File, destination: File) {
        destination.mkdirs()
        val root = destination.canonicalFile
        var entryCount = 0
        var totalBytes = 0L
        val seen = mutableSetOf<String>()

        ZipInputStream(FileInputStream(epub).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > MAX_ENTRIES) throw InvalidEpubException("EPUB 文件包含过多资源")

                val output = File(root, entry.name).canonicalFile
                if (output != root && !output.path.startsWith(root.path + File.separator)) {
                    throw InvalidEpubException("EPUB 包含非法路径")
                }
                if (!seen.add(output.path)) throw InvalidEpubException("EPUB 包含重复资源路径")

                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    output.outputStream().buffered().use { target ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            totalBytes += read
                            if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                                throw InvalidEpubException("EPUB 解压后体积过大")
                            }
                            target.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        if (entryCount == 0) throw InvalidEpubException("EPUB 文件为空")
    }
}
