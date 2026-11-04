package io.embodit.lowvision.domain

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.acos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.5 (QA #3 q3-2, profile D25 / D24 / B12): the Tripo props are calibrated (origin, face, size) and opaque.
 * Gate: centre within 1 cm of where the scene expects the origin, face direction within 2°, size within 10 %.
 */
class PropCalibrationTest {
    /** Design sizes (x, y, z in metres; null = not given) and where the origin sits on the box. */
    private val design =
        mapOf(
            PropModel.TABLE to Triple(Vec3(WalkRules.TABLE_W, WalkRules.TABLE_H, WalkRules.TABLE_D), "top", null),
            PropModel.MUG to Triple(Vec3(Float.NaN, 0.095f, 0.08f), "bottom", "body"),
            PropModel.SALT to Triple(Vec3(0.04f, 0.09f, 0.04f), "bottom", null),
            PropModel.GLASSES_CASE to Triple(Vec3(0.16f, Float.NaN, Float.NaN), "bottom", null),
            PropModel.SIGN_PLAQUE to Triple(Vec3(WalkRules.SIGN_W + 0.02f, WalkRules.SIGN_H + 0.02f, Float.NaN), "back", null),
            PropModel.SIGN_STAND to Triple(Vec3(Float.NaN, 1f, Float.NaN), "bottom", null),
            PropModel.SHOE to Triple(Vec3(Float.NaN, Float.NaN, 0.28f), "bottom", null),
        )

    private fun deg(a: Vec3, b: Vec3) = Math.toDegrees(acos((a.normalized().dot(b.normalized())).coerceIn(-1f, 1f).toDouble())).toFloat()

    private fun checkCalibrated(prop: PropModel, box: Box3) {
        val (size, origin, special) = design.getValue(prop)
        // the point that must sit on the origin
        val anchor =
            when (origin) {
                "top" -> Vec3(box.center.x, box.max.y, box.center.z)
                "back" -> Vec3(box.center.x, box.center.y, box.max.z + 0.001f)
                // the mug: the round body (as wide as it is deep) is centred, the handle sticks out along +X
                else -> if (special == "body") Vec3(box.min.x + box.size.z / 2f, box.min.y, box.center.z) else Vec3(box.center.x, box.min.y, box.center.z)
            }
        assertTrue("$prop origin off by ${anchor.length} m", anchor.length < 0.01f)
        for ((i, want) in listOf(size.x, size.y, size.z).withIndex()) {
            if (want.isNaN()) continue
            val have = listOf(box.size.x, box.size.y, box.size.z)[i]
            assertTrue("$prop axis $i: $have vs $want", abs(have - want) / want < 0.10f)
        }
    }

    @Test
    fun bakedFiles_areCalibrated() {
        for (prop in PropModel.entries) {
            val c = PropCalibration.calibrate(prop.box, prop)
            assertEquals("file", c.why)
            checkCalibrated(prop, c.apply(prop.box))
        }
    }

    @Test
    fun rootNodeDropped_rawTripoAxesAreCalibratedToo() {
        for (prop in PropModel.entries) {
            val c = PropCalibration.calibrate(prop.rawBox, prop)
            assertEquals("$prop", "raw", c.why)
            val out = c.apply(prop.rawBox)
            assertTrue("$prop raw -> $out vs ${prop.box}", out.near(prop.box, 0.02f))
            checkCalibrated(prop, out)
            // the face: in the raw file it points along the baked yaw's inverse; calibrated it must point along +Z
            val rawFace = PropCalibration.turn(Vec3(0f, 0f, 1f), -prop.yawDeg)
            val face = PropCalibration.turn(rawFace, c.yawDeg)
            assertTrue("$prop face off by ${deg(face, Vec3(0f, 0f, 1f))}°", deg(face, Vec3(0f, 0f, 1f)) < 2f)
        }
    }

    @Test
    fun theMug_standsOutFromTheDistractorsByShape() {
        // design E2: the mug is the only item with a handle (its box is wider than deep) and is the widest round item
        val mug = PropModel.MUG.box.size
        val salt = PropModel.SALT.box.size
        val case = PropModel.GLASSES_CASE.box.size
        assertTrue(mug.x > mug.z * 1.2f)
        assertTrue(abs(salt.x - salt.z) < 0.005f)
        assertTrue(mug.y > case.y * 1.8f)
        assertTrue(mug.z > salt.z * 1.8f)
    }

    /** The GLBs in the APK: one calibration root node, every material OPAQUE (profile D24 / 2026-10-29). */
    @Test
    fun assets_areOpaque_withOneCalibrationRoot() {
        val dir = listOf(File("src/main/assets/props"), File("app/src/main/assets/props")).first { it.isDirectory }
        for (prop in PropModel.entries) {
            val f = File(dir.parentFile, prop.asset)
            assertTrue("missing ${f.path}", f.isFile)
            val json = glbJson(f.readBytes())
            val materials = Regex("\"alphaMode\":\"(\\w+)\"").findAll(json).map { it.groupValues[1] }.toList()
            assertTrue("${prop.asset} has materials", materials.isNotEmpty())
            assertTrue("${prop.asset} alphaMode $materials", materials.all { it == "OPAQUE" })
            val name = prop.asset.substringAfter('/').substringBefore('.')
            assertTrue("${prop.asset} calibration root", json.contains("\"name\":\"calib:$name\""))
            assertTrue("${prop.asset} no transmission / blend extensions", !json.contains("KHR_materials_transmission") && !json.contains("\"BLEND\""))
        }
    }

    private fun glbJson(bytes: ByteArray): String {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, b.getInt(0))
        val len = b.getInt(12)
        assertEquals(0x4E4F534A, b.getInt(16))
        return String(bytes, 20, len, Charsets.UTF_8)
    }
}
