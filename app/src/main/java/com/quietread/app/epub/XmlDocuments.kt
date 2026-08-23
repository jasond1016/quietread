package com.quietread.app.epub

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

internal object XmlDocuments {
    fun parse(file: File): Document = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isXIncludeAware = false }
            runCatching { setExpandEntityReferences(false) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        factory.newDocumentBuilder().parse(file)
    } catch (error: Exception) {
        throw InvalidEpubException("无法解析 EPUB 文档：${file.name}", error)
    }
}

internal fun Node.localTag(): String = (localName ?: nodeName.substringAfter(':')).lowercase()

internal fun Node.childElements(): List<Element> = buildList {
    val children = childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child is Element) add(child)
    }
}

internal fun Node.descendants(tag: String): List<Element> = buildList {
    fun visit(node: Node) {
        node.childElements().forEach { child ->
            if (child.localTag() == tag.lowercase()) add(child)
            visit(child)
        }
    }
    visit(this@descendants)
}

internal fun Node.firstDescendant(tag: String): Element? = descendants(tag).firstOrNull()
