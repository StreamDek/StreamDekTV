package com.streamdek.tv.nativeapp.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage

/**
 * The moment between the page and the trailer.
 *
 * The trailer used to replace the page the instant it was ready, which reads as a jump cut: the
 * artwork, the cast and the buttons are there, and then a film is playing. A second of the title's
 * own backdrop with its logo over it gives the handover somewhere to happen — the page has gone, the
 * trailer has not started, and what is on screen is unmistakably still this title.
 *
 * Everything here is driven from [progress] rather than animating on its own, so the card, the page
 * behind it and the trailer under it are three parts of one movement instead of three animations
 * that happen to overlap. The parts do not arrive together: the backdrop settles out of a slight
 * push-in, and the logo follows it once there is something to sit on. A card whose contents all
 * appeared at once was the flat, abrupt thing this replaced.
 */
@Composable
internal fun TrailerIntroCard(
    backdropUrl: String?,
    titleLogoUrl: String?,
    title: String,
    /** 0 to 1, the card's own arrival. Drives every value below. */
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val eased = progress.coerceIn(0f, 1f)
    // A shallow push-in that ends exactly as the card lands. Wider than the screen at the start, so
    // there is never an edge, and small enough that it reads as settling rather than as zooming.
    val backdropScale = 1.06f - 0.06f * eased
    // The logo trails the backdrop: nothing to read until there is something to read it against.
    val logoReveal = ((eased - 0.35f) / 0.65f).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = eased }
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (!backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = backdropScale
                        scaleY = backdropScale
                    },
            )
        }
        // Dimmed from the edges rather than flatly: a backdrop is composed to look good, not to be
        // a background, and an even scrim over it reads as a screen with the brightness turned
        // down. Darkest where the logo sits, lightest at the corners, so the picture survives.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.62f), Color.Black.copy(alpha = 0.30f)),
                            radius = size.minDimension * 0.95f,
                        ),
                    )
                },
        )
        // Falls back to the title set rather than to nothing: not every title has a logo, and an
        // empty second of dimmed artwork would read as a stall rather than as a card.
        if (!titleLogoUrl.isNullOrBlank()) {
            AsyncImage(
                model = titleLogoUrl,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.46f)
                    .padding(24.dp)
                    .graphicsLayer {
                        alpha = logoReveal
                        // Rises the last of the way rather than appearing at its final size.
                        val logoScale = 0.94f + 0.06f * logoReveal
                        scaleX = logoScale
                        scaleY = logoScale
                    },
            )
        } else {
            Text(
                text = title,
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .padding(24.dp)
                    .graphicsLayer {
                        alpha = logoReveal
                        val logoScale = 0.94f + 0.06f * logoReveal
                        scaleX = logoScale
                        scaleY = logoScale
                    },
            )
        }
    }
}
