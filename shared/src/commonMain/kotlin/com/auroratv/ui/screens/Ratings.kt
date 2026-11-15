package com.auroratv.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auroratv.ratings.ExternalRatings
import com.auroratv.ratings.Review
import com.auroratv.ui.components.FocusSurface
import com.auroratv.ui.components.ScreenPadding
import com.auroratv.ui.components.ScreenStart
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraType
import kotlinx.coroutines.launch

// Original, generic artwork (a tomato, a popcorn bucket) — not any rating site's logo.
private val TomatoRed = Color(0xFFE5391C)
private val LeafGreen = Color(0xFF3FA34D)
private val SplatGreen = Color(0xFF7DBE3B)
private val BucketRed = Color(0xFFD7263D)
private val Kernel = Color(0xFFFFF4D6)
private val KernelShade = Color(0xFFF2C14E)

/** Ratings row for the detail page: critics + audience meters, IMDb, Metacritic, Letterboxd. */
@Composable
fun RatingsStrip(ratings: ExternalRatings?, modifier: Modifier = Modifier) {
    AnimatedVisibility(ratings != null && !ratings.isEmpty, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val r = ratings ?: return@AnimatedVisibility
        val colors = Aurora.colors
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            r.tomatometer?.let { score ->
                Meter("Critics $score%", { TomatoIcon(fresh = score >= 60) }, "$score%", "Critics")
            }
            r.popcornmeter?.let { score ->
                Meter("Audience $score%", { PopcornIcon(full = score >= 60) }, "$score%", "Audience")
            }
            r.imdb?.let { v ->
                Meter("IMDb ${fmt1(v)}", { TextBadge("IMDb") }, fmt1(v), r.imdbVotes?.let { compactCount(it) + " votes" } ?: "IMDb")
            }
            r.metacritic?.let { m ->
                val tint = when { m >= 61 -> Color(0xFF3BB273); m >= 40 -> Color(0xFFF2B33D); else -> Color(0xFFE5484D) }
                Meter("Metascore $m", { ScoreSquare(m.toString(), tint) }, null, "Metascore")
            }
            r.letterboxd?.let { v ->
                Meter("Letterboxd ${fmt1(v)} out of 5", { TextBadge("LB") }, "${fmt1(v)}/5", "Letterboxd")
            }
            if (r.source == "MDBList") Text("via MDBList", style = AuroraType.caption, color = colors.onSurfaceFaint)
        }
    }
}

@Composable
private fun Meter(description: String, icon: @Composable () -> Unit, value: String?, caption: String) {
    val colors = Aurora.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        icon()
        Spacer(Modifier.width(8.dp))
        Column {
            value?.let { Text(it, style = AuroraType.label, color = colors.onBackground) }
            Text(caption, style = AuroraType.caption, color = colors.onSurfaceFaint)
        }
    }
}

@Composable
private fun TextBadge(text: String) {
    val colors = Aurora.colors
    Box(
        Modifier.height(26.dp).clip(RoundedCornerShape(6.dp)).border(1.5.dp, colors.onSurfaceMuted, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = AuroraType.caption, color = colors.onBackground) }
}

@Composable
private fun ScoreSquare(text: String, tint: Color) {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(tint), contentAlignment = Alignment.Center) {
        Text(text, style = AuroraType.label, color = Color.White)
    }
}

/** A ripe tomato with a leaf (60%+), or a green splat below that. */
@Composable
fun TomatoIcon(fresh: Boolean, modifier: Modifier = Modifier.size(28.dp)) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        if (fresh) {
            drawOval(TomatoRed, topLeft = Offset(w * 0.06f, h * 0.2f), size = Size(w * 0.88f, h * 0.76f))
            drawOval(Color.White.copy(alpha = 0.28f), topLeft = Offset(w * 0.2f, h * 0.32f), size = Size(w * 0.22f, h * 0.16f))
            leaf(w, h)
        } else {
            val c = Offset(w * 0.5f, h * 0.56f)
            drawCircle(SplatGreen, radius = w * 0.3f, center = c)
            for (i in 0 until 7) {
                val a = i * (2 * kotlin.math.PI / 7) + 0.3
                val d = w * (if (i % 2 == 0) 0.34f else 0.27f)
                drawCircle(SplatGreen, radius = w * (if (i % 2 == 0) 0.1f else 0.07f), center = Offset(c.x + d * kotlin.math.cos(a).toFloat(), c.y + d * kotlin.math.sin(a).toFloat()))
            }
        }
    }
}

private fun DrawScope.leaf(w: Float, h: Float) {
    val cx = w * 0.5f; val cy = h * 0.22f
    for (dx in listOf(-0.16f, 0f, 0.16f)) {
        drawOval(LeafGreen, topLeft = Offset(cx + dx * w - w * 0.1f, cy - h * 0.08f), size = Size(w * 0.2f, h * 0.13f))
    }
    drawRect(LeafGreen, topLeft = Offset(cx - w * 0.03f, cy - h * 0.2f), size = Size(w * 0.06f, h * 0.14f))
}

/** A striped popcorn bucket, overflowing at 60%+, nearly empty below that. */
@Composable
fun PopcornIcon(full: Boolean, modifier: Modifier = Modifier.size(28.dp)) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val top = h * 0.38f
        val bucket = Path().apply {
            moveTo(w * 0.14f, top); lineTo(w * 0.86f, top); lineTo(w * 0.74f, h * 0.98f); lineTo(w * 0.26f, h * 0.98f); close()
        }
        val kernels = if (full) listOf(0.24f to 0.32f, 0.42f to 0.22f, 0.6f to 0.24f, 0.76f to 0.33f, 0.34f to 0.14f, 0.54f to 0.1f, 0.68f to 0.16f)
        else listOf(0.4f to 0.36f, 0.6f to 0.36f)
        kernels.forEach { (x, y) ->
            drawCircle(KernelShade, radius = w * 0.12f, center = Offset(w * x + w * 0.02f, h * y + h * 0.02f))
            drawCircle(Kernel, radius = w * 0.11f, center = Offset(w * x, h * y))
        }
        clipPath(bucket) {
            drawRect(Color.White, topLeft = Offset(0f, top), size = Size(w, h - top))
            for (i in 0 until 4) drawRect(if (full) BucketRed else Color(0xFF8A8F98), topLeft = Offset(w * (0.14f + i * 0.2f), top), size = Size(w * 0.1f, h - top))
        }
        drawRoundRect(if (full) BucketRed else Color(0xFF8A8F98), topLeft = Offset(w * 0.1f, top - h * 0.04f), size = Size(w * 0.8f, h * 0.08f), cornerRadius = CornerRadius(w * 0.03f))
    }
}

/** Horizontal row of spoiler-free review cards. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ReviewsRow(reviews: List<Review>, rowFocus: FocusRequester, onOpen: (Review) -> Unit) {
    val colors = Aurora.colors
    Column {
        Row(Modifier.padding(start = ScreenStart, end = ScreenPadding, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
            Text("Reviews", style = AuroraType.section, color = colors.onBackground)
            Spacer(Modifier.width(12.dp))
            Text("Spoiler-free · from Trakt", style = AuroraType.caption, color = colors.onSurfaceFaint)
        }
        LazyRow(
            contentPadding = PaddingValues(start = ScreenStart, end = ScreenPadding, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.focusRequester(rowFocus).focusRestorer(),
        ) {
            items(reviews, key = { it.id }) { review -> ReviewCard(review) { onOpen(review) } }
        }
    }
}

@Composable
private fun ReviewCard(review: Review, onClick: () -> Unit) {
    val colors = Aurora.colors
    FocusSurface(onClick = onClick, shape = RoundedCornerShape(16.dp), focusKey = "review/${review.id}", sheen = false, modifier = Modifier.width(440.dp).height(212.dp)) { focused ->
        Column(
            Modifier.fillMaxSize().background(if (focused) colors.surfaceRaised else colors.surface.copy(alpha = 0.72f)).padding(20.dp),
        ) {
            ReviewHeader(review)
            Spacer(Modifier.height(10.dp))
            Text(review.text, style = AuroraType.body, color = colors.onSurfaceMuted, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.height(8.dp))
            Text(
                listOfNotNull(if (review.isLongReview) "Full review" else "Comment", review.likes.takeIf { it > 0 }?.let { "$it likes" }, review.date).joinToString("  ·  "),
                style = AuroraType.caption, color = colors.onSurfaceFaint,
            )
        }
    }
}

@Composable
private fun ReviewHeader(review: Review) {
    val colors = Aurora.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(review.author, style = AuroraType.label, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(12.dp))
        RatingPill(review.rating)
    }
}

/** The reviewer's own score, 1–10, as five stars plus the number. */
@Composable
private fun RatingPill(rating: Int?) {
    val colors = Aurora.colors
    if (rating == null) {
        Text("No score", style = AuroraType.caption, color = colors.onSurfaceFaint)
        return
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(colors.onBackground.copy(alpha = 0.1f)).padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Rated $rating out of 10" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Stars(rating / 2f)
        Spacer(Modifier.width(6.dp))
        Text("$rating/10", style = AuroraType.caption, color = colors.onBackground)
    }
}

@Composable
private fun Stars(value: Float) {
    val gold = Color(0xFFF5C518)
    val empty = Aurora.colors.onSurfaceFaint.copy(alpha = 0.5f)
    Canvas(Modifier.width(70.dp).height(13.dp)) {
        val s = size.height
        for (i in 0 until 5) {
            val x = i * (s + (size.width - 5 * s) / 4)
            val star = starPath(Offset(x + s / 2, s / 2), s / 2)
            drawPath(star, empty)
            val fill = (value - i).coerceIn(0f, 1f)
            if (fill > 0f) clipPath(Path().apply { addRect(androidx.compose.ui.geometry.Rect(x, 0f, x + s * fill, s)) }) { drawPath(star, gold) }
        }
    }
}

private fun starPath(c: Offset, r: Float) = Path().apply {
    for (i in 0 until 10) {
        val radius = if (i % 2 == 0) r else r * 0.45f
        val a = -kotlin.math.PI / 2 + i * kotlin.math.PI / 5
        val p = Offset(c.x + radius * kotlin.math.cos(a).toFloat(), c.y + radius * kotlin.math.sin(a).toFloat())
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

/** Full review over a dimmed page; Up/Down scroll, Back/OK close. */
@Composable
fun ReviewOverlay(review: Review, onClose: () -> Unit) {
    val colors = Aurora.colors
    val focus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(review.id) { focus.requestFocus() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f))
            .focusRequester(focus)
            .onKeyEvent { ev ->
                when (ev.key) {
                    Key.Back, Key.Escape, Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> { if (ev.type == KeyEventType.KeyUp) onClose(); true }
                    Key.DirectionDown -> { if (ev.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(220f) }; true }
                    Key.DirectionUp -> { if (ev.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(-220f) }; true }
                    Key.DirectionLeft, Key.DirectionRight -> true
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 920.dp).fillMaxWidth(0.8f).heightIn(max = 640.dp)
                .clip(RoundedCornerShape(24.dp)).background(colors.surfaceRaised).padding(36.dp),
        ) {
            ReviewHeader(review)
            review.date?.let { Text(it, style = AuroraType.caption, color = colors.onSurfaceFaint) }
            Spacer(Modifier.height(18.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll)) {
                Text(review.text, style = AuroraType.bodyStrong, color = colors.onBackground)
            }
            Spacer(Modifier.height(18.dp))
            Text("From Trakt · ↑↓ scroll · OK or Back to close", style = AuroraType.caption, color = colors.onSurfaceFaint)
        }
    }
}

private fun fmt1(v: Double): String = (kotlin.math.round(v * 10) / 10.0).let { if (it == it.toLong().toDouble()) "${it.toLong()}.0" else it.toString() }

private fun compactCount(n: Int): String = when {
    n >= 1_000_000 -> "${n / 100_000 / 10.0}M".replace(".0M", "M")
    n >= 1_000 -> "${n / 1_000}K"
    else -> n.toString()
}

/** Shown where reviews would be when no Trakt key is set: says how to turn them on. */
@Composable
fun ReviewsHint(hasMdbList: Boolean, onOpenSettings: () -> Unit) {
    val colors = Aurora.colors
    Column(Modifier.padding(start = ScreenStart, end = ScreenPadding)) {
        Text("Reviews", style = AuroraType.section, color = colors.onBackground, modifier = Modifier.padding(bottom = 14.dp))
        FocusSurface(onClick = onOpenSettings, shape = RoundedCornerShape(16.dp), focusKey = "reviews/setup", sheen = false, modifier = Modifier.widthIn(max = 760.dp)) { focused ->
            Row(
                Modifier.background(if (focused) colors.surfaceRaised else colors.surface.copy(alpha = 0.72f)).padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PopcornIcon(full = true, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(18.dp))
                Column {
                    Text("Turn on spoiler-free reviews", style = AuroraType.label, color = colors.onBackground)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Add a free Trakt Client ID" + (if (hasMdbList) "" else " (and an MDBList key for audience, IMDb and Metacritic scores)") +
                            " in Settings → Ratings. Type it once on any device running ${com.auroratv.AppInfo.NAME}; your others pick it up.",
                        style = AuroraType.caption, color = colors.onSurfaceMuted,
                    )
                }
            }
        }
    }
}
