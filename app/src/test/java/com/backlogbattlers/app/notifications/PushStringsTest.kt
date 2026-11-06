package com.backlogbattlers.app.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// the words of every push, in all three languages: nothing is missing in Afrikaans or isiZulu,
// and each translation fills in the same details (names, counts, points) as the English one,
// in a push that would otherwise show a raw %1$s or crash on a missing argument
class PushStringsTest {

    // one string or one plural's items, by name. a plural maps its quantity ("one", "other") to the text
    private class Strings(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private val english = load("values")
    private val afrikaans = load("values-af")
    private val zulu = load("values-zu")

    // the resources live in the app module, which is the folder the unit tests run from
    private fun load(folder: String): Strings {
        val file = listOf("src/main/res/$folder/strings.xml", "app/src/main/res/$folder/strings.xml").map(::File).first { it.isFile }
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            when (node.tagName) {
                "string" -> strings[node.getAttribute("name")] = node.textContent
                "plurals" -> {
                    val items = node.getElementsByTagName("item")
                    plurals[node.getAttribute("name")] = (0 until items.length)
                        .map { items.item(it) as Element }
                        .associate { it.getAttribute("quantity") to it.textContent }
                }
            }
        }
        return Strings(strings, plurals)
    }

    // what a push or a channel is called, and what it says
    private fun isPushText(name: String) = name.startsWith("push_") || name.startsWith("channel_")

    private val placeholder = Regex("%(\\d+)\\$[sd]")
    private fun placeholdersOf(text: String) = placeholder.findAll(text).map { it.groupValues[1] }.toSet()

    @Test
    fun `every push and channel string in English exists in Afrikaans and isiZulu`() {
        val wanted = english.strings.keys.filter(::isPushText).toSet()
        assertTrue("the push strings should not be empty", wanted.size >= 15)

        assertEquals("missing in Afrikaans", emptySet<String>(), wanted - afrikaans.strings.keys)
        assertEquals("missing in isiZulu", emptySet<String>(), wanted - zulu.strings.keys)
    }

    @Test
    fun `every push plural in English exists in Afrikaans and isiZulu with one and other`() {
        val wanted = english.plurals.keys.filter(::isPushText).toSet()
        assertEquals(setOf("push_achievement_many", "push_achievement_summary"), wanted)

        for ((language, strings) in mapOf("Afrikaans" to afrikaans, "isiZulu" to zulu)) {
            assertEquals("plurals missing in $language", emptySet<String>(), wanted - strings.plurals.keys)
            wanted.forEach { name ->
                assertEquals("$language $name", setOf("one", "other"), strings.plurals.getValue(name).keys)
            }
        }
    }

    @Test
    fun `each translated push string fills in the same details as the English one`() {
        english.strings.filterKeys(::isPushText).forEach { (name, text) ->
            val expected = placeholdersOf(text)
            assertEquals("Afrikaans $name", expected, placeholdersOf(afrikaans.strings.getValue(name)))
            assertEquals("isiZulu $name", expected, placeholdersOf(zulu.strings.getValue(name)))
        }
    }

    @Test
    fun `each translated push plural fills in the same details in its many form`() {
        english.plurals.filterKeys(::isPushText).forEach { (name, items) ->
            val expected = placeholdersOf(items.getValue("other"))
            assertEquals("Afrikaans $name", expected, placeholdersOf(afrikaans.plurals.getValue(name).getValue("other")))
            assertEquals("isiZulu $name", expected, placeholdersOf(zulu.plurals.getValue(name).getValue("other")))
        }
    }

    @Test
    fun `the singular form of a push plural never asks for a detail the call does not give`() {
        // the app calls these with every detail, but the one form of English says "a new achievement"
        // and may leave the count out; a form may use fewer details, never ones the many form lacks
        for ((language, strings) in mapOf("English" to english, "Afrikaans" to afrikaans, "isiZulu" to zulu)) {
            strings.plurals.filterKeys(::isPushText).forEach { (name, items) ->
                val many = placeholdersOf(items.getValue("other"))
                assertTrue("$language $name one-form uses a detail the other form lacks", many.containsAll(placeholdersOf(items.getValue("one"))))
            }
        }
    }

    @Test
    fun `no push string is blank or left as the English text in another language`() {
        for ((language, strings) in mapOf("Afrikaans" to afrikaans, "isiZulu" to zulu)) {
            english.strings.filterKeys(::isPushText).forEach { (name, text) ->
                val translated = strings.strings.getValue(name)
                assertTrue("$language $name is blank", translated.isNotBlank())
                // a name with nothing to translate, e.g. only a placeholder, may stay the same
                if (text.replace(placeholder, "").trim().length > 3) {
                    assertTrue("$language $name is still the English text", translated != text)
                }
            }
        }
    }
}
//------------------------------EOF------------------------------\\
