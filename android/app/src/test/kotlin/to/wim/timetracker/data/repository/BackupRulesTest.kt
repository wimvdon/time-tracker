package to.wim.timetracker.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Guards what Android's backup and phone-to-phone transfer may copy (res/xml). */
class BackupRulesTest {
    // Unit tests run in the app module's folder.
    private val xmlDir = File("src/main/res/xml")

    private val onlySettings = listOf("include file ${DataFiles.dataStorePath(DataFiles.SETTINGS)}")

    private fun rules(
        file: String,
        section: String? = null,
    ): List<String> {
        val root =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(xmlDir, file)).documentElement
        val parent = if (section == null) root else root.getElementsByTagName(section).item(0) as Element
        val children = parent.childNodes
        return (0 until children.length)
            .map { children.item(it) }
            .filterIsInstance<Element>()
            .map { "${it.tagName} ${it.getAttribute("domain")} ${it.getAttribute("path")}" }
    }

    @Test
    fun `Android 12+ backup copies only the settings file`() {
        assertEquals(onlySettings, rules("data_extraction_rules.xml", "cloud-backup"))
    }

    @Test
    fun `phone-to-phone transfer copies only the settings file`() {
        assertEquals(onlySettings, rules("data_extraction_rules.xml", "device-transfer"))
    }

    @Test
    fun `Android 8-11 backup copies only the settings file`() {
        assertEquals(onlySettings, rules("backup_rules.xml"))
    }

    @Test
    fun `the API key is never backed up`() {
        val all = rules("data_extraction_rules.xml", "cloud-backup") + rules("backup_rules.xml")
        assertTrue(all.none { DataFiles.API_KEY in it || DataFiles.API_KEY_FALLBACK in it })
    }
}
