package realm.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldTest {
    private fun heightAt(fontScale: Float, text: String): Int {
        var height = 0
        val scene = ImageComposeScene(400, 400, Density(1f, fontScale)) {
            RealmTheme { Field(TextFieldValue(text), {}, "Dump something…", Modifier.onSizeChanged { height = it.height }) }
        }
        scene.render()
        scene.close()
        return height
    }

    @Test
    fun oneLineFitsTheControlHeightAtLargerFontSizesToo() {
        assertEquals(48, heightAt(1f, "hi"))
        assertEquals(48, heightAt(1.3f, "hi"))
    }

    @Test
    fun moreLinesGrowByALineEach() {
        assertEquals(48 + 22, heightAt(1f, "one\ntwo"))
    }
}
