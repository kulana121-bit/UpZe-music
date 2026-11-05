package com.ravi.musicspot.ui

import com.ravi.musicspot.ui.motion.focusBlur
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.ui.account.GoogleLoginScreen
import com.ravi.musicspot.ui.account.SpotifyLoginScreen
import com.ravi.musicspot.ui.account.WelcomeFlow
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf // v2.0.14b
import androidx.compose.ui.layout.layout // v2.0.14b
import androidx.compose.ui.unit.Constraints // v2.0.14b
import androidx.compose.ui.graphics.RectangleShape // v2.0.14b
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.lerp
import com.ravi.musicspot.ui.components.Artwork
import kotlin.math.roundToInt
import com.ravi.musicspot.ui.chrome.DockChrome
import com.ravi.musicspot.ui.chrome.CHROME_SIDE
import com.ravi.musicspot.ui.chrome.CHROME_BOTTOM
import com.ravi.musicspot.ui.motion.MotionSpec
import com.ravi.musicspot.ui.chrome.DOCK_HEIGHT
import com.ravi.musicspot.ui.chrome.MINI_HEIGHT
import com.ravi.musicspot.ui.chrome.MiniPlayer
import com.ravi.musicspot.ui.chrome.SideRail
import com.ravi.musicspot.ui.chrome.chromeHeight
import com.ravi.musicspot.ui.glass.GlassLayer
import com.ravi.musicspot.ui.motion.PageMotion
import com.ravi.musicspot.ui.motion.reducedMotion
import com.ravi.musicspot.ui.player.CreditsSheet
import com.ravi.musicspot.ui.player.PlayerScreen
import com.ravi.musicspot.ui.screens.ArtistScreen
import com.ravi.musicspot.ui.screens.DeviceMusicScreen
import com.ravi.musicspot.ui.screens.DownloadsScreen
import com.ravi.musicspot.ui.screens.ExploreScreen
import com.ravi.musicspot.ui.screens.HistoryScreen
import com.ravi.musicspot.ui.screens.HomeScreen
import com.ravi.musicspot.ui.screens.LibraryScreen
import com.ravi.musicspot.ui.settings.SettingsHome
import com.ravi.musicspot.ui.settings.SettingsPageScreen
import com.ravi.musicspot.ui.screens.LikedScreen
import com.ravi.musicspot.ui.screens.LocalBottomInset
import com.ravi.musicspot.ui.screens.LocalNav
import com.ravi.musicspot.ui.screens.LocalPlaylistScreen
import com.ravi.musicspot.ui.screens.MoodScreen
import com.ravi.musicspot.ui.screens.RemoteCollectionScreen
import com.ravi.musicspot.ui.screens.SavedScreen
import com.ravi.musicspot.ui.screens.SearchScreen
import com.ravi.musicspot.ui.theme.MusicSpotTheme
import com.ravi.musicspot.ui.theme.Ms
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.ravi.musicspot.ui.chrome.NavHideState

/** Holds whether the player is open; [progress] 0 = mini player, 1 = full Now Playing. */
class PlayerSheetState {
    val progress = Animatable(0f)
}

/**
 * The app shell: theme, the glass layer the floating chrome samples, per-tab navigation with page motion,
 * the mini player that expands into Now Playing (swipe/drag up to open, swipe down or back to collapse),
 * back handling and edge-to-edge insets. Google/Spotify login pages are placeholders until step 3.
 */
@Composable
fun AppRoot(openPlayer: Boolean, onOpenPlayerConsumed: () -> Unit) {
    val settings by Graph.settings.state.collectAsState()
    val st by Graph.player.state.collectAsState()
    val ready by Graph.ready.collectAsState()
    MusicSpotTheme(artUrl = st.current?.artUrl, dynamic = settings.dynamicColor, mode = settings.themeMode) {
        if (!ready) { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)); return@MusicSpotTheme }
        if (!settings.onboarded) { WelcomeFlow(); return@MusicSpotTheme }
        val nav = remember { Navigator() }
        val sheet = remember { PlayerSheetState() }
        val scope = rememberCoroutineScope()
        val still = reducedMotion()
        val hasMini = st.current != null

        fun expand() = scope.launch { if (still) sheet.progress.snapTo(1f) else sheet.progress.animateTo(1f, PageMotion.playerSpring) }
        fun collapse() = scope.launch { if (still) sheet.progress.snapTo(0f) else sheet.progress.animateTo(0f, PageMotion.playerSpring) }

        LaunchedEffect(Unit) { Graph.player.connect() }
        val updCtx = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(Unit) { com.ravi.musicspot.update.Updater.checkOnStart(updCtx) } // 1.0.0: ≤ once per 12 h, IO
        // v2.0.14: latest callback without restarting the effect.
        val openConsumed by androidx.compose.runtime.rememberUpdatedState(onOpenPlayerConsumed)
        LaunchedEffect(openPlayer, hasMini) { if (openPlayer && hasMini) { expand(); openConsumed() } }
        LaunchedEffect(hasMini) { if (!hasMini) sheet.progress.snapTo(0f) }

        // Pages first; the player's own BackHandler (composed later) wins while it is open.
        // Predictive back (Android 14+ shows the gesture progress; older versions just get the back event): the page
        // shrinks and slides with the finger, then the pop transition runs.
        var backProgress by remember { mutableStateOf(0f) }
        androidx.activity.compose.PredictiveBackHandler(enabled = nav.canGoBack) { events ->
            try {
                events.collect { e -> if (!still) backProgress = e.progress }
                backProgress = 0f
                nav.back()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) { backProgress = 0f; throw e }
        }

        CompositionLocalProvider(LocalNav provides nav) {
            BoxWithConstraints(Modifier.fillMaxSize().focusBlur()) {
                val wide = maxWidth >= 600.dp
                val heightPx = with(LocalDensity.current) { maxHeight.toPx() }.coerceAtLeast(1f)
                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val railWidth = 104.dp

                // v2.0.3 hide-on-scroll, v2.0.6 Mini / Hide / Always visible (phones only; the tablet side rail never
                // changes). Expanded again on tab/route change, when the player starts to expand or finishes collapsing,
                // and at the top of a list. One critically damped spring drives one progress value per mode; the dock
                // and the mini player both read it (in layout, never in composition), so they move as one.
                val navDensity = LocalDensity.current
                val navHide = remember(navDensity) { with(navDensity) { NavHideState(hideAfterPx = 24.dp.toPx(), showAfterPx = 8.dp.toPx()) } }
                val navMode = settings.navMode
                LaunchedEffect(navHide, navMode, wide) { navHide.enabled = navMode != com.ravi.musicspot.data.NavBarMode.ALWAYS && !wide }
                LaunchedEffect(navHide, nav.tab, nav.current) { navHide.show() }
                LaunchedEffect(navHide, hasMini) { snapshotFlow { sheet.progress.value > 0f }.distinctUntilChanged().collect { navHide.show() } }
                val dockSpec = if (still) snap<Float>() else MotionSpec.dockSpring()
                val morphF = animateFloatAsState(if (navHide.hidden && navMode == com.ravi.musicspot.data.NavBarMode.MINI) 1f else 0f, dockSpec, label = "dockMorph")
                val hideF = animateFloatAsState(if (navHide.hidden && navMode == com.ravi.musicspot.data.NavBarMode.HIDE) 1f else 0f, dockSpec, label = "navHide")

                // Constant: the expanded chrome's height, so lists never jump while the dock changes (v2.0.6).
                val bottomInset = navBottom + if (wide) (if (hasMini) MINI_HEIGHT + 24.dp else 16.dp) else if (settings.dockStyle == com.ravi.musicspot.data.DockStyle.EDGE) com.ravi.musicspot.ui.chrome.edgeDockHeight(hasMini) else chromeHeight(hasMini) // v2.0.16: Tray pads by its real height

                // Cover morph: the artwork flies between the mini player's slot and Now Playing's slot with the sheet
                // progress. Both slots report their resting bounds; while 0 < p < 1 a single floating copy is drawn.
                var miniCover by remember { mutableStateOf<Rect?>(null) }
                var fullCover by remember { mutableStateOf<Rect?>(null) }
                // v2.0.14b: progress is only read through derivedStateOf here, so AppRoot recomposes when these booleans
                // flip, not every frame of a drag/spring. Raw progress is read in graphicsLayer/offset/layout lambdas.
                val sheetMidway by remember(sheet) { derivedStateOf { sheet.progress.value in 0.001f..0.999f } }
                val sheetOpen by remember(sheet) { derivedStateOf { sheet.progress.value > 0f } }
                val morphing = !still && hasMini && sheetMidway && miniCover != null && fullCover != null

                fun drag(dy: Float) { scope.launch { sheet.progress.snapTo((sheet.progress.value - dy / heightPx).coerceIn(0f, 1f)) } }
                val tabs = listOf(Tab.HOME, Tab.EXPLORE, Tab.LIBRARY, Tab.SETTINGS, Tab.SEARCH)
                // 1.0.1: every dock / rail selects through here, so a new tab lifts out of the bar (TabLift) on all of them.
                val selectTab: (Tab) -> Unit = { t -> if (t != nav.tab) com.ravi.musicspot.ui.chrome.TabLift.lift(t, scope, still); nav.select(t) }

                GlassLayer(
                    Modifier.fillMaxSize(),
                    content = {
                        CompositionLocalProvider(LocalBottomInset provides bottomInset) {
                            Box(Modifier.fillMaxSize().padding(start = if (wide) railWidth else 0.dp).nestedScroll(navHide.connection).graphicsLayer {
                                val bp = backProgress
                                if (bp > 0f) {
                                    scaleX = 1f - 0.08f * bp; scaleY = 1f - 0.08f * bp
                                    translationX = 24.dp.toPx() * bp
                                    shape = RoundedCornerShape((28f * bp).dp); clip = true
                                }
                            }) { Pages(nav, still) }
                        }
                    },
                    overlay = {
                        if (wide) SideRail(tabs, nav.tab, selectTab, Modifier.align(Alignment.CenterStart))
                        val mini: @Composable () -> Unit = {
                            MiniPlayer(
                                onOpen = { expand() },
                                onDrag = ::drag,
                                onDragEnd = { total -> if (total < -80f || sheet.progress.value > 0.25f) expand() else collapse() },
                                coverVisible = !morphing,
                                onCoverBounds = { miniCover = it },
                            )
                        }
                        val fadeWithPlayer = Modifier.graphicsLayer { alpha = 1f - (sheet.progress.value * 2f).coerceIn(0f, 1f) }
                        if (wide) {
                            if (hasMini) Box(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    .padding(start = railWidth + CHROME_SIDE, end = CHROME_SIDE, bottom = navBottom + CHROME_BOTTOM).then(fadeWithPlayer),
                            ) { mini() }
                        } else if (settings.dockStyle == com.ravi.musicspot.data.DockStyle.CAPSULE) com.ravi.musicspot.ui.chrome.CapsuleDock(
                            tabs = tabs, selected = nav.tab, onSelect = selectTab, hasMini = hasMini,
                            morph = { morphF.value }, hide = { hideF.value },
                            miniTarget = navHide.hidden && navMode == com.ravi.musicspot.data.NavBarMode.MINI,
                            onExpand = { navHide.show() },
                            hideDrop = navBottom + CHROME_BOTTOM + DOCK_HEIGHT + MINI_HEIGHT + 24.dp,
                            onOpen = { expand() },
                            onDrag = ::drag,
                            onDragEnd = { total -> if (total < -80f || sheet.progress.value > 0.25f) expand() else collapse() },
                            coverVisible = !morphing,
                            onCoverBounds = { miniCover = it },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = navBottom + CHROME_BOTTOM).then(fadeWithPlayer),
                        ) else if (settings.dockStyle == com.ravi.musicspot.data.DockStyle.EDGE) com.ravi.musicspot.ui.chrome.EdgeDock(
                            tabs = tabs, selected = nav.tab, onSelect = selectTab, hasMini = hasMini,
                            morph = { morphF.value }, hide = { hideF.value },
                            miniTarget = navHide.hidden && navMode == com.ravi.musicspot.data.NavBarMode.MINI,
                            onExpand = { navHide.show() },
                            hideDrop = navBottom + CHROME_BOTTOM + DOCK_HEIGHT + MINI_HEIGHT + 24.dp,
                            onOpen = { expand() },
                            onDrag = ::drag,
                            onDragEnd = { total -> if (total < -80f || sheet.progress.value > 0.25f) expand() else collapse() },
                            coverVisible = !morphing,
                            onCoverBounds = { miniCover = it },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = navBottom + CHROME_BOTTOM).then(fadeWithPlayer),
                        ) else if (settings.dockStyle == com.ravi.musicspot.data.DockStyle.ISLAND) com.ravi.musicspot.ui.chrome.IslandDock(
                            tabs = tabs, selected = nav.tab, onSelect = selectTab, hasMini = hasMini,
                            morph = { morphF.value }, hide = { hideF.value },
                            miniTarget = navHide.hidden && navMode == com.ravi.musicspot.data.NavBarMode.MINI,
                            onExpand = { navHide.show() },
                            hideDrop = navBottom + CHROME_BOTTOM + DOCK_HEIGHT + MINI_HEIGHT + 24.dp,
                            onOpen = { expand() },
                            onDrag = ::drag,
                            onDragEnd = { total -> if (total < -80f || sheet.progress.value > 0.25f) expand() else collapse() },
                            coverVisible = !morphing,
                            onCoverBounds = { miniCover = it },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = navBottom + CHROME_BOTTOM).then(fadeWithPlayer),
                        ) else DockChrome(
                            tabs = tabs, selected = nav.tab, onSelect = selectTab, hasMini = hasMini,
                            morph = { morphF.value }, hide = { hideF.value },
                            miniTarget = navHide.hidden && navMode == com.ravi.musicspot.data.NavBarMode.MINI,
                            onExpand = { navHide.show() },
                            hideDrop = navBottom + CHROME_BOTTOM + DOCK_HEIGHT + 24.dp,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = navBottom + CHROME_BOTTOM).then(fadeWithPlayer),
                            miniPlayer = mini,
                        )
                        // 1.0.1: the lifted tab bubble, above the dock, inside the glass layer (real blur on Android 12+).
                        com.ravi.musicspot.ui.chrome.TabLiftOverlay(MaterialTheme.colorScheme.primary)
                    },
                )

                // Now Playing slides up over everything; nothing in it scrolls.
                // v2.0.14b: boolean via derivedStateOf; raw progress read only inside graphicsLayer.
                if (hasMini && sheetOpen) Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer { val p = sheet.progress.value; translationY = (1f - p) * heightPx; alpha = (p * 3f).coerceIn(0f, 1f) }
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    if (settings.playerStyle == com.ravi.musicspot.data.PlayerStyle.VINYL) com.ravi.musicspot.ui.player.VinylPlayerScreen(
                        progress = { sheet.progress.value },
                        onCollapse = { collapse() },
                        onDrag = ::drag,
                        onDragEnd = { total -> if (total > 120f || sheet.progress.value < 0.75f) collapse() else expand() },
                        coverVisible = !morphing,
                        onCoverBounds = { r -> fullCover = r.translate(0f, -(1f - sheet.progress.value) * heightPx) },
                        onOpenEq = { collapse(); nav.open(Tab.SETTINGS, Route.SettingsPage("sound")) },
                        onNavigate = { nav.push(it) },
                    ) else if (settings.playerStyle == com.ravi.musicspot.data.PlayerStyle.MINIMAL) com.ravi.musicspot.ui.player.MinimalPlayerScreen(
                        progress = { sheet.progress.value },
                        onCollapse = { collapse() },
                        onDrag = ::drag,
                        onDragEnd = { total -> if (total > 120f || sheet.progress.value < 0.75f) collapse() else expand() },
                        coverVisible = !morphing,
                        onCoverBounds = { r -> fullCover = r.translate(0f, -(1f - sheet.progress.value) * heightPx) },
                        onOpenEq = { collapse(); nav.open(Tab.SETTINGS, Route.SettingsPage("sound")) },
                        onNavigate = { nav.push(it) },
                    ) else
                    if (settings.playerStyle == com.ravi.musicspot.data.PlayerStyle.AURA) com.ravi.musicspot.ui.player.AuraPlayerScreen(
                        progress = { sheet.progress.value },
                        onCollapse = { collapse() },
                        onDrag = ::drag,
                        onDragEnd = { total -> if (total > 120f || sheet.progress.value < 0.75f) collapse() else expand() },
                        coverVisible = !morphing,
                        onCoverBounds = { r -> fullCover = r.translate(0f, -(1f - sheet.progress.value) * heightPx) },
                        onOpenEq = { collapse(); nav.open(Tab.SETTINGS, Route.SettingsPage("sound")) },
                        onNavigate = { nav.push(it) },
                    ) else PlayerScreen(
                        progress = { sheet.progress.value },
                        onCollapse = { collapse() },
                        onDrag = ::drag,
                        onDragEnd = { total -> if (total > 120f || sheet.progress.value < 0.75f) collapse() else expand() },
                        coverVisible = !morphing,
                        // Bounds include the sheet's translation; store the resting (fully open) position.
                        onCoverBounds = { r -> fullCover = r.translate(0f, -(1f - sheet.progress.value) * heightPx) },
                        onOpenEq = { collapse(); nav.open(Tab.SETTINGS, Route.SettingsPage("sound")) },
                        onNavigate = { nav.push(it) },
                    )
                }

                // v2.0.14: null-safe locals instead of !!.
                val mc = miniCover; val fc = fullCover
                if (morphing && mc != null && fc != null) {
                    val a = mc; val b = fc
                    // v2.0.14b: the morph reads progress only in the offset/layout/graphicsLayer lambdas (no per-frame
                    // recomposition). The outer graphicsLayer does the rounded clip, so Artwork gets a plain rect shape.
                    fun morphT() = FastOutSlowInEasing.transform(sheet.progress.value)
                    Artwork(
                        st.current?.artUrl,
                        Modifier.offset { val t = morphT(); IntOffset(lerp(a.left, b.left, t).roundToInt(), lerp(a.top, b.top, t).roundToInt()) }
                            .layout { measurable, _ ->
                                val t = morphT()
                                val w = lerp(a.width, b.width, t).roundToInt().coerceAtLeast(0)
                                val h = lerp(a.height, b.height, t).roundToInt().coerceAtLeast(0)
                                val placeable = measurable.measure(Constraints.fixed(w, h))
                                layout(w, h) { placeable.place(0, 0) }
                            }
                            .graphicsLayer { val t = morphT(); shadowElevation = 24.dp.toPx() * t; shape = RoundedCornerShape((12f + 8f * t).dp); clip = true },
                        RectangleShape,
                    )
                }

                // Sign-in pages sit above the glass layer and chrome: a WebView must not be drawn into the backdrop.
                when (nav.current) {
                    Route.GoogleLogin -> GoogleLoginScreen { nav.back() }
                    Route.SpotifyLogin -> SpotifyLoginScreen { nav.back() }
                    else -> {}
                }
                // 1.0.1: Customize and Your artists (from Settings) cover the dock and mini player, like the sign-in pages,
                // so their own bottom buttons are reachable; they slide in/out on the page motion and return with nav.back().
                val fullRoute = nav.current.takeIf { it == Route.Customize || it == Route.Artists }
                androidx.compose.animation.AnimatedContent(
                    targetState = fullRoute,
                    transitionSpec = {
                        if (initialState == null) { com.ravi.musicspot.ui.motion.TapOrigin.windowOrigin().also { o -> com.ravi.musicspot.ui.motion.TapOrigin.save("settingsFull", o) }.let { PageMotion.pushFrom(still, it) } } else if (targetState == null) PageMotion.popTo(still, com.ravi.musicspot.ui.motion.TapOrigin.take("settingsFull")) else MotionSpec.fadeThrough(still)
                    },
                    label = "settingsFull",
                ) { r ->
                    when (r) {
                        Route.Customize -> com.ravi.musicspot.ui.setup.SetupFlow(fromSettings = true, onExit = { nav.back() })
                        Route.Artists -> com.ravi.musicspot.ui.setup.ArtistsScreen(onBack = { nav.back() })
                        else -> Box(Modifier.fillMaxSize())
                    }
                }

                TrackMenuSheet(nav) { collapse() }
                AddToPlaylistDialog()
                CreditsSheet()
                PillHost()
                com.ravi.musicspot.update.UpdateSheet() // 1.0.0 in-app updater
            }
        }
    }
}

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun Pages(nav: Navigator, still: Boolean) {
    val key = nav.tab to nav.current
    // v2.0.5: one saved-state slot per back-stack entry (see Navigator.pageKey): every rememberSaveable inside a page,
    // including the default LazyListState/LazyGridState of its lists and carousels, survives Back and tab switches.
    val pageKey = nav.pageKey()
    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    LaunchedEffect(pageKey) { nav.dropped().forEach { if (it != pageKey) holder.removeState(it) } }
    androidx.compose.animation.SharedTransitionLayout(Modifier.fillMaxSize()) {
    CompositionLocalProvider(com.ravi.musicspot.ui.motion.LocalSharedScope provides this) {
    AnimatedContent(
        targetState = Triple(key.first, key.second, pageKey),
        transitionSpec = {
            // 1.0.1: pushes grow out of the tapped row/card/button (TapOrigin) and Back shrinks the page into it.
            when (nav.lastDirection) {
                1 -> { val o = com.ravi.musicspot.ui.motion.TapOrigin.windowOrigin(); com.ravi.musicspot.ui.motion.TapOrigin.save(targetState.third, o); PageMotion.pushFrom(still, o) }
                -1 -> PageMotion.popTo(still, com.ravi.musicspot.ui.motion.TapOrigin.take(initialState.third))
                else -> PageMotion.tab(still)
            }
        },
        label = "pages",
        modifier = Modifier.fillMaxSize(),
    ) { (_, route, pk) -> holder.SaveableStateProvider(pk) { CompositionLocalProvider(com.ravi.musicspot.ui.motion.LocalPageScope provides this, com.ravi.musicspot.ui.motion.LocalStaggerGroup provides remember { com.ravi.musicspot.ui.motion.StaggerGroup() }) { RouteContent(route) } } }
    }
    }
}

@Composable
private fun RouteContent(r: Route) {
    when (r) {
        is Route.Root -> when (r.tab) {
            Tab.HOME -> HomeScreen()
            Tab.EXPLORE -> ExploreScreen()
            Tab.SEARCH -> SearchScreen()
            Tab.LIBRARY -> LibraryScreen()
            Tab.SETTINGS -> SettingsHome()
        }
        is Route.Remote -> RemoteCollectionScreen(r.collection)
        is Route.Artist -> ArtistScreen(r)
        is Route.Mood -> MoodScreen(r)
        is Route.LocalPlaylist -> LocalPlaylistScreen(r.id)
        Route.Liked -> LikedScreen()
        Route.History -> HistoryScreen()
        Route.Downloads -> DownloadsScreen()
        Route.DeviceMusic -> DeviceMusicScreen()
        Route.Saved -> SavedScreen()
        is Route.SettingsPage -> SettingsPageScreen(r.page)
        is Route.ShelfAll -> com.ravi.musicspot.ui.screens.ShelfAllScreen(r.shelf)
        Route.GoogleLogin, Route.SpotifyLogin, Route.Customize, Route.Artists -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) // drawn by AppRoot
    }
}

@Suppress("unused") private val dockHeightRef = DOCK_HEIGHT
