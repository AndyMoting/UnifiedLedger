package com.unifiedledger.ui.theme.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * D-136 glass enablement (spec 1/1): the D-134 D2-D3 compile-time glass flag is flipped to
 * `true`, so the glass path of the two-piece encapsulation below is the active render path
 * with a single wiring point (the P503TabShell bottom capsule). The fallback paths stay
 * value-identical to the plain Material3 surface (P6-D3: Material3 is the stable baseline and
 * default fallback) and a FAIL on any acceptance gate reverts this single constant (spec 4.1).
 * Accounting state, navigation, submission and failure states must never depend on glass.
 */
internal const val GLASS_ENABLED = true

/**
 * D-136 two-piece encapsulation, handle side (spec 2.2): an encapsulated handle over the
 * backdrop library layer. The library type stays inside this package — the layer is private
 * and only reachable through [requireLayer] from the glass composables — so no library symbol
 * leaks to the wiring site; the inert handle used by the fallback path carries no layer.
 */
internal class GlassBackdrop private constructor(
    private val layer: LayerBackdrop?,
) {
    internal fun requireLayer(): LayerBackdrop = requireNotNull(layer) { "the glass layer is only available while GLASS_ENABLED is true" }

    internal companion object {
        internal fun ofLayer(layer: LayerBackdrop): GlassBackdrop = GlassBackdrop(layer)

        internal val INERT: GlassBackdrop = GlassBackdrop(layer = null)
    }
}

/**
 * D-136 two-piece encapsulation, handle factory (spec 2.2): on the glass path the returned
 * handle wraps a fresh library layer backdrop; the fallback path returns an inert handle.
 */
@Composable
internal fun rememberGlassBackdrop(): GlassBackdrop =
    if (GLASS_ENABLED) {
        GlassBackdrop.ofLayer(rememberLayerBackdrop())
    } else {
        GlassBackdrop.INERT
    }

/**
 * D-136 two-piece encapsulation, source side (spec 2.2): on the glass path the content drawn
 * inside [content] is registered as the layer backdrop sampled by [GlassSurface] (modifier
 * semantics such as fill and padding flow through the [modifier] argument); the fallback path
 * is a plain transparent Box with zero behavior change.
 */
@Composable
internal fun GlassBackdropSource(
    backdrop: GlassBackdrop,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (GLASS_ENABLED) {
        Box(modifier = modifier.layerBackdrop(backdrop.requireLayer())) {
            content()
        }
    } else {
        Box(modifier = modifier) {
            content()
        }
    }
}

/**
 * D-136 two-piece encapsulation, surface side (spec 2.2): on the glass path the content is
 * drawn as a liquid-glass element of [shape] sampling the layer registered by
 * [GlassBackdropSource], with the effect values frozen by D-134 (vibrancy + blur 2.dp +
 * lens 12.dp/24.dp), a shadow aligned with the fallback shadowElevation 6.dp, and the content
 * Box clipped to [shape] (D2IMPL-Q-001 fix, fallback/glass content-clipping parity). The
 * fallback path is value-identical to the plain Material3 surface this replaces
 * (tonalElevation 3.dp, shadowElevation 6.dp).
 */
@Composable
internal fun GlassSurface(
    backdrop: GlassBackdrop,
    modifier: Modifier = Modifier,
    shape: Shape,
    content: @Composable () -> Unit,
) {
    if (GLASS_ENABLED) {
        Box(
            modifier =
                modifier
                    .shadow(elevation = 6.dp, shape = shape)
                    .drawBackdrop(
                        backdrop = backdrop.requireLayer(),
                        shape = { shape },
                        effects = {
                            vibrancy()
                            blur(2.dp.toPx())
                            lens(12.dp.toPx(), 24.dp.toPx())
                        },
                    ),
        ) {
            Box(modifier = Modifier.clip(shape)) {
                content()
            }
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            content()
        }
    }
}
