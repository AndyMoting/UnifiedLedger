package com.unifiedledger.ui.theme.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * D-134 D2-D3 compile-time glass flag (spec section 4/D2-D3): frozen off in this batch with
 * zero call sites, so the glass path is never entered and the runtime behavior of the app is
 * unchanged. The enablement batch owns flipping this flag, the wiring and the P6-D5 numeric
 * freeze (authority: D3 platform regression batch).
 */
internal const val GLASS_ENABLED = false

/**
 * D-134 D2-D3: the single glass entry of the theme/component layer. Glass path: [backdrop] is
 * recorded through the backdrop library and [content] is elevated as a liquid-glass element of
 * [shape] sampling that layer. Material3 fallback: the same stacking renders [content] on a
 * plain tonal Surface and the effect layer is never entered (P6-D3: Material3 is the stable
 * baseline and default fallback). Accounting state, navigation, submission and failure states
 * must never depend on this composable; zero call sites exist in this batch.
 */
@Composable
internal fun GlassLayer(
    modifier: Modifier = Modifier,
    shape: Shape,
    backdrop: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    if (GLASS_ENABLED) {
        val layerBackdrop = rememberLayerBackdrop()
        Box(modifier = modifier) {
            Box(modifier = Modifier.fillMaxSize().layerBackdrop(layerBackdrop)) {
                backdrop()
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .drawBackdrop(
                            backdrop = layerBackdrop,
                            shape = { shape },
                            effects = {
                                vibrancy()
                                blur(2.dp.toPx())
                                lens(12.dp.toPx(), 24.dp.toPx())
                            },
                        ),
            ) {
                content()
            }
        }
    } else {
        Box(modifier = modifier) {
            backdrop()
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = shape,
                tonalElevation = 3.dp,
            ) {
                content()
            }
        }
    }
}
