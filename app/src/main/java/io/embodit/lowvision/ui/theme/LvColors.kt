package io.embodit.lowvision.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's exact colours (design-package `palette`). The only file with colour literals. Disease
 * colours are for icons and the field diagram outline only, never large fills.
 */
object LvColors {
    val PlateInk = Color(0xFF15181C) // design-style: fixed-figma-color plateInk: opaque card base in the Stage (glass turns black there)
    val Glaucoma = Color(0xFF6FA8DC) // design-style: fixed-figma-color disease.glaucoma fog blue
    val Amd = Color(0xFFD9A35F) // design-style: fixed-figma-color disease.amd amber
    val Hemianopia = Color(0xFF9C8BD9) // design-style: fixed-figma-color disease.hemianopia lilac
    val Door = Color(0xFF3DDC84) // design-style: fixed-figma-color door green dashes
    val Danger = Color(0xFFFF5F52) // design-style: fixed-figma-color danger: stepped on the shoe / wrong pick
    val OnDanger = Color(0xFF2A0A07) // design-style: fixed-figma-color text on the danger red (Quit confirm, v0.1.2)
    val OnPlate = Color(0xFFF2F4F7) // design-style: fixed-figma-color text on plateInk (15.9 : 1)
    val OnPlateDim = Color(0xFFB8C0CA) // design-style: fixed-figma-color secondary text on plateInk (9.7 : 1)
    val PlateRaised = Color(0xFF242A31) // design-style: fixed-figma-color raised row on plateInk
    val FieldSeen = Color(0xFFDDE6EE) // design-style: fixed-figma-color the seen part of the field diagram
    val Accent = Color(0xFF4FC3E8) // design-style: fixed-figma-color interaction: eye-fundus teal, selected card ring and primary button
    val OnAccent = Color(0xFF06222C) // design-style: fixed-figma-color text on the accent (9.2 : 1)

    // ---- 3D scene (unlit, tone mapping off)
    val Mug = Color(0xFFF4F4F2) // design-style: fixed-figma-color mug white
    val Salt = Color(0xFF8E9399) // design-style: fixed-figma-color salt shaker grey
    val GlassesCase = Color(0xFF1F3A68) // design-style: fixed-figma-color glasses case deep blue
    val TableWood = Color(0xFFC9A57A) // design-style: fixed-figma-color virtual table light wood
    val Stand = Color(0xFFE9ECEF) // design-style: fixed-figma-color virtual sign stand white
    val Shoe = Color(0xFF3A3D42) // design-style: fixed-figma-color shoe dark grey
}
