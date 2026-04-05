package one.wabbit.typeclass.idea

import kotlin.test.Test
import kotlin.test.assertTrue

class TypeclassPluginXmlTest {
    @Test
    fun declaresK2CompatibilityForKotlinPlugin() {
        val pluginXml =
            requireNotNull(javaClass.classLoader.getResource("META-INF/plugin.xml")) {
                "Missing META-INF/plugin.xml on the test classpath"
            }.readText()

        assertTrue(pluginXml.contains("""<depends>org.jetbrains.kotlin</depends>"""))
        assertTrue(pluginXml.contains("""<extensions defaultExtensionNs="org.jetbrains.kotlin">"""))
        assertTrue(pluginXml.contains("""<supportsKotlinPluginMode supportsK2="true" />"""))
    }
}
