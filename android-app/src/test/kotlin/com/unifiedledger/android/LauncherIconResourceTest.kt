package com.unifiedledger.android

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * D-369 D launcher-icon evidence (spec section 7.3; official adaptive-icon design guidance
 * https://developer.android.com/develop/ui/views/launch/icon_design_adaptive).
 *
 * The launcher icon is a project-owned adaptive icon: a P8-blue background layer, a white
 * ledger/unified-flow foreground drawn as one stroked vector path, and a monochrome layer that
 * reuses the SAME geometry for the system's themed-icon tint. No raster image, no external icon
 * library and no new dependency is introduced, so the whole face is four XML files plus one color.
 *
 * These assertions are resource-shape assertions on the source tree (manifest attribute values,
 * adaptive-icon layer wiring, vector geometry and XML well-formedness), which is what a JVM test can
 * prove without an Android device or `aapt`. The AGP resource link step in the `Android compile`
 * check still has the final say on compilability; OEM launcher masks are NOT claimed as visually
 * tested here — the visible-safe-zone guarantee is proven as geometry, not as a screenshot.
 */
class LauncherIconResourceTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Test
    fun theManifestPointsIconAndRoundIconAtProjectOwnedAdaptiveIcons() {
        val application = applicationElement()
        assertEquals(
            "@mipmap/ic_launcher",
            application.getAttributeNS(androidNs, "icon"),
            "the application must declare the project-owned adaptive icon",
        )
        assertEquals(
            "@mipmap/ic_launcher_round",
            application.getAttributeNS(androidNs, "roundIcon"),
            "the round launcher entry must point at the project-owned round adaptive icon",
        )
        // Every manifest icon reference resolves to an existing project file (no framework or
        // generated reference, no missing resource).
        for (attribute in listOf("icon", "roundIcon")) {
            val reference = application.getAttributeNS(androidNs, attribute)
            assertTrue(
                Files.isRegularFile(mipmapFile(reference)),
                "the manifest $attribute reference $reference must resolve to a project-owned mipmap XML",
            )
        }
    }

    @Test
    fun theManifestKeepsTheExistingLabelLauncherActivityAndWidgetEntry() {
        // D-369 D adds an icon; it must not disturb the existing launcher label, the MAIN/LAUNCHER
        // activity entry, the widget receiver or its provider metadata.
        val manifest = document("android-app/src/main/AndroidManifest.xml")
        val application = applicationElement()
        assertEquals("UnifiedLedger", application.getAttributeNS(androidNs, "label"))
        assertEquals(1, manifest.getElementsByTagName("activity").length, "exactly the existing single Activity must remain declared")
        val text = repositoryFile("android-app/src/main/AndroidManifest.xml").toFile().readText()
        assertTrue(text.contains("android:name=\".MainActivity\""), "the launcher activity must stay declared")
        assertTrue(text.contains("android.intent.action.MAIN"), "the launcher MAIN action must stay declared")
        assertTrue(text.contains("android.intent.category.LAUNCHER"), "the launcher category must stay declared")
        assertTrue(text.contains("android:name=\".LedgerWidgetProvider\""), "the widget receiver must stay declared")
        assertTrue(text.contains("android:resource=\"@xml/ledger_widget_info\""), "the widget provider metadata must stay declared")
    }

    @Test
    fun everyAdaptiveIconLayerIsDeclaredAndResolves() {
        assertEquals("#FF2B5FCB", backgroundColour(), "the background must be the P8 light accent blue (#2B5FCB)")
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val doc = document("android-app/src/main/res/mipmap-anydpi-v26/$name.xml")
            assertEquals("adaptive-icon", doc.documentElement.tagName, "$name must be an adaptive-icon resource")
            assertEquals(
                "@color/ic_launcher_background",
                layerDrawableReference(doc, "background"),
                "$name must take the P8-blue background layer",
            )
            assertEquals(
                "@drawable/ic_launcher_foreground",
                layerDrawableReference(doc, "foreground"),
                "$name must take the project-owned ledger foreground",
            )
            assertEquals(
                "@drawable/ic_launcher_monochrome",
                layerDrawableReference(doc, "monochrome"),
                "$name must declare the themed-icon monochrome layer",
            )
            // The three layer references resolve to real project resources.
            assertTrue(
                Files.isRegularFile(repositoryFile("android-app/src/main/res/drawable/ic_launcher_foreground.xml")),
                "the foreground layer must exist as a project vector",
            )
            assertTrue(
                Files.isRegularFile(repositoryFile("android-app/src/main/res/drawable/ic_launcher_monochrome.xml")),
                "the monochrome layer must exist as a project vector",
            )
        }
    }

    @Test
    fun theForegroundAndMonochromeLayersAreTheSameWithinTheAdaptiveIconSafeZone() {
        val foreground = pathDataOf("android-app/src/main/res/drawable/ic_launcher_foreground.xml")
        val monochrome = pathDataOf("android-app/src/main/res/drawable/ic_launcher_monochrome.xml")
        assertEquals(
            foreground,
            monochrome,
            "the monochrome layer must reuse the visible glyph geometry, otherwise the themed icon differs",
        )
        assertEveryEndpointInsideTheUnmaskedZone(foreground, "foreground")
        assertEveryEndpointInsideTheUnmaskedZone(monochrome, "monochrome")
    }

    @Test
    fun theLauncherAssetsAreProjectOwnedXmlVectorsWithoutRasterOrDependencyImports() {
        // The launcher face is exactly one color plus four XML files. A PNG/WebP would mean copied
        // or generated raster art, and an external icon library would arrive as a dependency line.
        val launcherFiles =
            Files.walk(repositoryFile("android-app/src/main/res")).use { stream ->
                stream
                    .filter { path -> path.fileName.toString().startsWith("ic_launcher") }
                    .toList()
            }
        assertTrue(launcherFiles.isNotEmpty(), "the project-owned launcher resources must exist")
        for (file in launcherFiles) {
            assertTrue(
                file.toString().endsWith(".xml"),
                "the launcher face must stay XML-backed (no raster asset): $file",
            )
        }
        assertEquals(
            5,
            launcherFiles.size,
            "the launcher face must be the background colour, two vectors and the two adaptive-icon XMLs",
        )
        val build = repositoryFile("android-app/build.gradle.kts").toFile().readText()
        for (forbidden in listOf("material-icons", "androidx.compose.material:material-icons", "iconics", "com.mikepenz")) {
            assertTrue(!build.contains(forbidden), "no external icon library may be added: $forbidden")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun applicationElement(): Element {
        val doc = document("android-app/src/main/AndroidManifest.xml")
        return doc.getElementsByTagName("application").item(0) as Element
    }

    private fun mipmapFile(reference: String): Path {
        val name = reference.removePrefix("@mipmap/")
        return repositoryFile("android-app/src/main/res/mipmap-anydpi-v26/$name.xml")
    }

    private fun layerDrawableReference(
        doc: Document,
        layer: String,
    ): String {
        val nodes = doc.getElementsByTagName(layer)
        assertEquals(1, nodes.length, "the adaptive icon must declare exactly one <$layer> layer")
        return (nodes.item(0) as Element).getAttributeNS(androidNs, "drawable")
    }

    private fun backgroundColour(): String {
        val doc = document("android-app/src/main/res/values/ic_launcher_background.xml")
        val colours = doc.getElementsByTagName("color")
        assertEquals(1, colours.length, "the background colour file must declare exactly one colour")
        val colour = colours.item(0) as Element
        assertEquals("ic_launcher_background", colour.getAttribute("name"))
        return colour.textContent.trim()
    }

    /** Asserts the vector resource shape and returns its single path's `pathData`. */
    private fun pathDataOf(relative: String): String {
        val doc = document(relative)
        val root = doc.documentElement
        assertEquals("vector", root.tagName, "$relative must be a vector drawable")
        assertEquals("108dp", root.getAttributeNS(androidNs, "width"), "$relative must use the 108dp adaptive canvas")
        assertEquals("108dp", root.getAttributeNS(androidNs, "height"), "$relative must use the 108dp adaptive canvas")
        assertEquals("108", root.getAttributeNS(androidNs, "viewportWidth"))
        assertEquals("108", root.getAttributeNS(androidNs, "viewportHeight"))
        val paths = root.getElementsByTagName("path")
        assertEquals(1, paths.length, "$relative must keep one stroked path so the layers stay identical")
        val path = paths.item(0) as Element
        assertEquals("#FFFFFFFF", path.getAttributeNS(androidNs, "strokeColor"), "$relative must be a white mark")
        assertEquals("6", path.getAttributeNS(androidNs, "strokeWidth"), "$relative must use the 6dp stroke")
        // The 3dp stroke radius used by the safe-zone bound is only true for a ROUND line cap: a
        // butt/square cap would paint past the endpoint by up to half the stroke width on the axis.
        assertEquals(
            "round",
            path.getAttributeNS(androidNs, "strokeLineCap"),
            "$relative must round the 6dp stroke so the 3dp safe-zone radius holds",
        )
        return path.getAttributeNS(androidNs, "pathData")
    }

    private fun assertEveryEndpointInsideTheUnmaskedZone(
        pathData: String,
        layer: String,
    ) {
        val endpoints = pathEndpoints(pathData)
        assertTrue(endpoints.size >= 5, "the ledger mark must draw a spine and three rows, got: $pathData")
        for ((x, y) in endpoints) {
            val distance = hypot(x - 54.0, y - 54.0)
            // The adaptive icon keeps only the central 66dp-diameter circle across launcher masks;
            // the round 6dp stroke extends 3dp past every endpoint, so endpoints must stay inside
            // 30dp of the centre for the painted mark to stay inside the 33dp unmasked radius.
            assertTrue(
                distance <= 30.0,
                "the $layer endpoint ($x, $y) is ${distance}dp from the 108dp canvas centre; " +
                    "with a 3dp stroke radius it must stay within the 33dp unmasked radius",
            )
        }
    }

    /** Parses the absolute `M`/`L` endpoint pairs of the authored mark, rejecting any other command. */
    private fun pathEndpoints(pathData: String): List<Pair<Double, Double>> {
        val match = Regex("[ML]\\s*(-?\\d+(?:\\.\\d+)?)\\s*[,\\s]\\s*(-?\\d+(?:\\.\\d+)?)")
        val pairs =
            match
                .findAll(pathData)
                .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
                .toList()
        val residue = match.replace(pathData, " ").filterNot { it.isWhitespace() || it == ',' }
        assertTrue(residue.isEmpty(), "the launcher mark must use only absolute M/L commands, found: $residue")
        return pairs
    }

    private fun document(relative: String): Document = document(repositoryFile(relative))

    private fun document(path: Path): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(path.toFile())
    }

    /** Resolves a repository-relative path by walking up to the settings file (the precedent helper). */
    private fun repositoryFile(relative: String): Path {
        var candidate =
            Path
                .of(System.getProperty("user.dir"))
        repeat(8) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                return candidate.resolve(relative)
            }
            candidate = candidate.parent ?: error("repository root not found")
        }
        error("repository root not found")
    }
}
