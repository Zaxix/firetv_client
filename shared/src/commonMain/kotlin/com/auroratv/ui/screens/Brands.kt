package com.auroratv.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.auroratv.brands.ResolvedBrand
import com.auroratv.core.toAppError
import com.auroratv.jellyfin.ItemQuery
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.ui.LocalContainer
import com.auroratv.ui.LocalSession
import com.auroratv.jellyfin.Artwork
import com.auroratv.ui.nav.LocalEntry
import androidx.compose.ui.draw.clip
import com.auroratv.ui.components.ArtworkImage
import com.auroratv.ui.components.AuroraButton
import com.auroratv.ui.components.CardStyle
import com.auroratv.ui.components.ContentRail
import com.auroratv.ui.components.EmptyState
import com.auroratv.ui.components.ErrorState
import com.auroratv.ui.components.FocusSurface
import com.auroratv.ui.components.ScreenPadding
import com.auroratv.ui.components.ScreenStart
import com.auroratv.ui.components.SkeletonRail
import com.auroratv.ui.components.artworkPx
import com.auroratv.ui.nav.LocalNavigator
import com.auroratv.ui.nav.Route
import com.auroratv.ui.nav.ScreenFocus
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraType
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import com.auroratv.resources.Res
import com.auroratv.resources.brand_appletv
import com.auroratv.resources.brand_prime_logo
import com.auroratv.resources.brand_netflix
import com.auroratv.resources.brand_disney
import com.auroratv.resources.brand_paramount
import kotlinx.coroutines.coroutineScope

/** Tile size: landscape and well under a poster's height (posters are 150 × 225 dp). */
private val TileWidth = 172.dp
private val TileHeight = 97.dp

private fun ResolvedBrand.gradient(): List<Color> = brand.colors.map { Color(it) }

/** "Brands" row on Home: one tile per studio/network found in the library. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BrandRow(brands: List<ResolvedBrand>, onOpen: (ResolvedBrand) -> Unit) {
    val container = LocalContainer.current
    val api = LocalSession.current.api
    var focusedBrand by remember { mutableStateOf<ResolvedBrand?>(null) }
    // Resting on a tile starts loading its hub, so opening it is usually instant.
    LaunchedEffect(focusedBrand) {
        val b = focusedBrand ?: return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        container.brands.prefetch(api, b)
    }
    LazyRow(
        contentPadding = PaddingValues(start = ScreenStart, end = ScreenPadding, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.focusRestorer(),
    ) {
        items(brands, key = { it.brand.id }) { b -> BrandTile(b, onFocused = { focusedBrand = b }) { onOpen(b) } }
    }
}

@Composable
fun BrandTile(brand: ResolvedBrand, onFocused: () -> Unit = {}, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        onFocused = onFocused,
        shape = RoundedCornerShape(16.dp),
        focusKey = "brand/${brand.brand.id}",
        modifier = Modifier.width(TileWidth).height(TileHeight),
    ) { focused ->
        BrandArt(brand, focused, Modifier.fillMaxSize(), wordmarkSize = 18.sp, imagePadding = 18.dp)
    }
}

/**
 * The brand's face: gradient with a soft light that drifts while focused, and either the studio
 * image from the user's server or the brand name set in Aurora's type.
 */
@Composable
private fun BrandArt(brand: ResolvedBrand, focused: Boolean, modifier: Modifier, wordmarkSize: TextUnit, imagePadding: androidx.compose.ui.unit.Dp) {
    val api = LocalSession.current.api
    val colors = brand.gradient()
    val drift = if (focused && Aurora.motion.enabled) {
        val t = rememberInfiniteTransition(label = "brand")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200), RepeatMode.Reverse), label = "drift").value
    } else 0.35f
    Box(
        modifier
            .background(Brush.linearGradient(colors, start = Offset.Zero, end = Offset.Infinite))
            .drawWithContent {
                drawRect(Brush.radialGradient(
                    listOf(Color.White.copy(alpha = if (focused) 0.22f else 0.1f), Color.Transparent),
                    center = Offset(size.width * (0.15f + 0.7f * drift), size.height * 0.15f),
                    radius = size.width * 0.75f,
                ))
                drawContent()
            },
        contentAlignment = Alignment.Center,
    ) {
        val studio = brand.imageStudio
        val type = brand.imageType
        val bundled = bundledArt(brand.brand.id)
        if (bundled != null) {
            if (bundled.fullBleed) Image(painterResource(bundled.res), brand.brand.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Image(painterResource(bundled.res), brand.brand.name, Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp), contentScale = ContentScale.Fit)
        } else if (studio != null && type != null) {
            val art = Artwork(api.images.url(studio.id, type, studio.imageTags[type], maxWidth = artworkPx(TileWidth * 2, 1f)), null)
            if (type == "Logo") ArtworkImage(art, brand.brand.name, Modifier.fillMaxSize().padding(imagePadding), contentScale = ContentScale.Fit, showPlaceholder = false)
            else ArtworkImage(art, brand.brand.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, showPlaceholder = false)
        } else {
            val b = brand.brand
            Text(
                if (b.uppercase) b.name.uppercase() else b.name,
                style = AuroraType.title.copy(fontSize = wordmarkSize, lineHeight = wordmarkSize * 1.15f, fontWeight = FontWeight.Black, letterSpacing = b.tracking.em),
                color = Color(b.textColor),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
    }
}

/** Tile artwork supplied for the app (see docs/brand); other brands use the server's studio image or their name. */
private class BundledArt(val res: DrawableResource, val fullBleed: Boolean)

private fun bundledArt(id: String): BundledArt? = when (id) {
    "appletv" -> BundledArt(Res.drawable.brand_appletv, fullBleed = true)
    "netflix" -> BundledArt(Res.drawable.brand_netflix, fullBleed = false)
    "prime" -> BundledArt(Res.drawable.brand_prime_logo, fullBleed = false)
    "disney" -> BundledArt(Res.drawable.brand_disney, fullBleed = false)
    "paramount" -> BundledArt(Res.drawable.brand_paramount, fullBleed = false)
    else -> null
}

/** Brand Hub: everything in the library from one studio/network family. */
@Composable
fun BrandHubScreen(route: Route.Brand) {
    val container = LocalContainer.current
    val api = LocalSession.current.api
    val navigator = LocalNavigator.current
    val entry = LocalEntry.current!!
    val model = entry.model("brand") { BrandHubModel() }
    val browseFocus = remember { FocusRequester() }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        if (model.loadedOnce && attempt == 0) return@LaunchedEffect
        model.error = null
        val brand = try {
            container.brands.find(api, route.brandId) ?: container.brands.brands(api).firstOrNull { it.brand.id == route.brandId }
                ?: error("This brand isn't in your library any more.")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            model.error = t.toAppError().userMessage; return@LaunchedEffect
        }
        model.brand = brand
        val hub = container.brands.hub(api, brand)
        // Each row appears as soon as its own answer arrives.
        coroutineScope {
            launch { model.newest = runCatching { hub.newest.await() }.getOrElse { emptyList() } }
            launch { model.movies = runCatching { hub.movies.await() }.getOrElse { emptyList() } }
            launch { model.series = runCatching { hub.series.await() }.getOrElse { emptyList() } }
            launch { model.total = runCatching { hub.total.await() }.getOrNull() }
        }
        model.loadedOnce = true
        if (listOf(hub.newest, hub.movies, hub.series).all { it.isCancelled })
            model.error = "Couldn't load ${brand.brand.name} from the server."
    }
    ScreenFocus(entry, navigator, browseFocus, ready = model.brand != null)

    val brand = model.brand
    if (model.error != null && model.newest.isNullOrEmpty() && model.movies.isNullOrEmpty() && model.series.isNullOrEmpty()) {
        ErrorState(model.error!!, onRetry = { attempt++ })
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(top = com.auroratv.ui.ScreenTop, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "banner") {
            Row(Modifier.padding(start = ScreenStart, end = ScreenPadding), verticalAlignment = Alignment.CenterVertically) {
                if (brand != null) {
                    Box(Modifier.width(360.dp).height(202.dp).clip(RoundedCornerShape(20.dp))) {
                        BrandArt(brand, focused = true, modifier = Modifier.fillMaxSize(), wordmarkSize = 38.sp, imagePadding = 36.dp)
                    }
                }
                Spacer(Modifier.width(36.dp))
                Column {
                    Text(brand?.brand?.name ?: route.name, style = AuroraType.hero, color = Aurora.colors.onBackground)
                    model.total?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(if (it == 1) "1 title in your library" else "$it titles in your library", style = AuroraType.bodyStrong, color = Aurora.colors.onSurfaceMuted)
                    }
                    Spacer(Modifier.height(20.dp))
                    AuroraButton(
                        "Browse all",
                        {
                            val b = model.brand ?: return@AuroraButton
                            navigator.push(Route.Library(null, b.brand.name, listOf("Movie", "Series"), null, b.studioIds))
                        },
                        icon = Icons.Filled.GridView,
                        focusRequester = browseFocus,
                        focusKey = "brand/browse",
                    )
                }
            }
        }
        val rows = listOf(Triple("newest", "New and recent", model.newest), Triple("movies", "Movies", model.movies), Triple("series", "Series", model.series))
        rows.forEach { (key, title, list) ->
            when {
                list == null -> item(key = "$key-loading") { SkeletonRail(CardStyle.Poster) }
                list.isNotEmpty() -> item(key = key) {
                    ContentRail(title, list, api.images, CardStyle.Poster, "brand-$key", onItemClick = { openItem(navigator, it) })
                }
            }
        }
        if (rows.all { it.third?.isEmpty() == true }) {
            item(key = "empty") { EmptyState("Nothing here yet", "No movies or series from ${route.name} in your libraries.") }
        }
    }
}

class BrandHubModel {
    var brand by mutableStateOf<ResolvedBrand?>(null)
    var newest by mutableStateOf<List<BaseItemDto>?>(null)
    var movies by mutableStateOf<List<BaseItemDto>?>(null)
    var series by mutableStateOf<List<BaseItemDto>?>(null)
    var total by mutableStateOf<Int?>(null)
    var error by mutableStateOf<String?>(null)
    var loadedOnce = false
}
