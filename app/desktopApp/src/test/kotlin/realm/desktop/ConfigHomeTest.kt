package realm.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigHomeTest {
    @Test fun theAppKeepsItsSettingsWhereTheCliKeepsItsConfig() {
        val cases = Json.parseToJsonElement(File("../../cli/testdata/config-dirs.json").readText()).jsonArray.map { it.jsonObject }
        for (case in cases) {
            val env = case["env"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            val os = case["os"]!!.jsonPrimitive.content
            val got = configHome(if (os == "darwin") "Mac OS X" else os, env::get, env.getValue("HOME")).path
            val want = case["dir"]!!.jsonPrimitive.content
            assertEquals(want.replace('\\', '/'), got.replace('\\', '/'), "$os $env")
        }
    }
}
