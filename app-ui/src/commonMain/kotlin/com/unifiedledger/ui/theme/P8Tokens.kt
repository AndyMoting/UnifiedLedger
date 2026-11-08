package com.unifiedledger.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * P8-00 shell token palette (D-301; spec `docs/specs/2026-11-08-p8-00-visual-nav-baseline-design.md`
 * section 3). The thirteen frozen values land here verbatim for the dark instance; the light
 * instance applies the measured light-token mapping plus the two follow-up corrections from the
 * local design adjudication material (section 7: the light faint value no longer exists on its
 * own — every placeholder text consumes the variant value at 5.2:1; section 8: the light page
 * darkens one step to #ECECF1 so the white cards separate, the dock/card separation rides
 * shadows, and the small-control stroke is #E8E8EE).
 *
 * Contrast evidence: the local design adjudication material (dark: post-fix 0 FAIL; light:
 * post-fix 0 FAIL below AA-large). The category glyph color #1A1A1C is shared by both
 * themes (the report's fix list item 1) — the colored circle tints stay identical across themes
 * (light-token-map identity rows).
 *
 * Material3 fallback (D-134 structure, not regressed): [P503Theme]-style hosts map a subset of
 * this palette onto the M3 color scheme (primary/背景/onSurfaceVariant/outline family) while the
 * `error` family keeps its untouched M3 semantic — the expense/income amounts are presentation
 * tokens consumed directly, never the failure color.
 */
data class P8Palette(
    /** 页面底色. */
    val page: Color,
    /** 分组卡底色与圆角（画布 18–22 区间，取中值 20）. */
    val card: Color,
    val cardRadius: Dp,
    /** 底栏胶囊与独立＋的底色与圆角（画布 29–32 区间，取中值 30）. */
    val dock: Color,
    val dockRadius: Dp,
    /** dock 系描边；浅色下同时是小控件描边（#E8E8EE，报告 section 8）. */
    val stroke: Color,
    /** 组内分隔线. */
    val divider: Color,
    /** 主文本. */
    val onSurface: Color,
    /** 次级文本（≥12sp；浅色下同时承担占位文字，报告 section 7）. */
    val onSurfaceVariant: Color,
    /** 仅占位文字（暗色 #6B6B70；浅色不再有独立值，同 variant）. */
    val disabledFaint: Color,
    /** 强调色：Tab 选中文字、进度条. */
    val accent: Color,
    /** Tab 选中底. */
    val accentSelectedBackground: Color,
    /** 负值/支出. */
    val expense: Color,
    /** 收入/正向. */
    val income: Color,
    /** 分类圆图标底色（38dp 圆；两主题同值）. */
    val categoryTints: List<Color>,
    /** 分类圆图标上的字形深字（两主题通用，报告修复清单 1）. */
    val categoryGlyph: Color,
)

/** The dark instance: the spec section 3 values verbatim. */
internal fun p8DarkPalette(): P8Palette =
    P8Palette(
        page = Color(0xFF0A0A0B),
        card = Color(0xFF17171A),
        cardRadius = 20.dp,
        dock = Color(0xFF1C1C1F),
        dockRadius = 30.dp,
        stroke = Color(0xFF34343B),
        divider = Color(0xFF26262B),
        onSurface = Color(0xFFFFFFFF),
        onSurfaceVariant = Color(0xFF8B8B92),
        disabledFaint = Color(0xFF6B6B70),
        accent = Color(0xFF5B93FF),
        accentSelectedBackground = Color(0xFF2C2C33),
        expense = Color(0xFFFF6B5B),
        income = Color(0xFF3DDC97),
        categoryTints =
            listOf(
                Color(0xFFFF7A5C),
                Color(0xFF4E8DFF),
                Color(0xFF7C6BFF),
                Color(0xFFFFC94D),
            ),
        categoryGlyph = Color(0xFF1A1A1C),
    )

/**
 * The light instance: the light-token mapping plus the report sections 7/8 corrections (page
 * #ECECF1, faint folded into variant #6C6C74, small-control stroke #E8E8EE).
 */
internal fun p8LightPalette(): P8Palette =
    P8Palette(
        page = Color(0xFFECECF1),
        card = Color(0xFFFFFFFF),
        cardRadius = 20.dp,
        dock = Color(0xFFFFFFFF),
        dockRadius = 30.dp,
        stroke = Color(0xFFE8E8EE),
        divider = Color(0xFFEFEFF3),
        onSurface = Color(0xFF101014),
        onSurfaceVariant = Color(0xFF6C6C74),
        disabledFaint = Color(0xFF6C6C74),
        accent = Color(0xFF2B5FCB),
        accentSelectedBackground = Color(0xFFE3EDFF),
        expense = Color(0xFFD93025),
        income = Color(0xFF188038),
        categoryTints =
            listOf(
                Color(0xFFFF7A5C),
                Color(0xFF4E8DFF),
                Color(0xFF7C6BFF),
                Color(0xFFFFC94D),
            ),
        categoryGlyph = Color(0xFF1A1A1C),
    )

/** Composition-local access to the active P8 palette; provided by the shared theme wrapper. */
val LocalP8Palette = staticCompositionLocalOf { p8DarkPalette() }

/**
 * The M3 color-scheme remap the theme wrapper applies so every existing M3 component (buttons,
 * text buttons, radio buttons, progress indicators, dividers) consumes the P8 tokens without a
 * per-screen rewrite. `error` keeps the untouched M3 semantic (the expense red is a presentation
 * token, never the failure color); the derived containers stay on their M3 defaults except the
 * ones the shell consumes (secondaryContainer hosts the Busy strip on the selected-background
 * tone). Pure.
 */
internal fun remapMaterialScheme(
    dark: Boolean,
    palette: P8Palette,
): ColorScheme =
    if (dark) {
        darkColorScheme(
            primary = palette.accent,
            // page-on-accent contrast 6.66:1 (computed; the report's contrast bar); the light
            // instance uses white.
            onPrimary = palette.page,
            background = palette.page,
            onBackground = palette.onSurface,
            surface = palette.card,
            onSurface = palette.onSurface,
            onSurfaceVariant = palette.onSurfaceVariant,
            outline = palette.stroke,
            outlineVariant = palette.divider,
            secondaryContainer = palette.accentSelectedBackground,
            onSecondaryContainer = palette.onSurface,
        )
    } else {
        lightColorScheme(
            // The report's fix list item 2: the accent button base is the deepened #2B5FCB with
            // white content (5.83:1) in BOTH themes; the light accent itself is the same value.
            primary = palette.accent,
            onPrimary = Color(0xFFFFFFFF),
            background = palette.page,
            onBackground = palette.onSurface,
            surface = palette.card,
            onSurface = palette.onSurface,
            onSurfaceVariant = palette.onSurfaceVariant,
            outline = palette.stroke,
            outlineVariant = palette.divider,
            secondaryContainer = palette.accentSelectedBackground,
            onSecondaryContainer = palette.onSurface,
        )
    }
