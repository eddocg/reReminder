package com.olaf.rereminder.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.olaf.rereminder.ui.settings.SettingsRoute
import com.olaf.rereminder.ui.theme.Motion
import com.olaf.rereminder.ui.timer.BreakTimerRoute

object Routes {
    const val TIMER = "timer"
    const val LIST = "list"
    const val SETTINGS = "settings"
    const val EDITOR = "editor/{id}"
    const val ARG_ID = "id"

    fun editor(id: Int) = "editor/$id"
}

/**
 * The whole app in one navigation graph: the break timer, and its settings.
 *
 * reReminder's reminder list and editor ([Routes.LIST], [Routes.EDITOR]) are deliberately not in
 * the graph. Break Alarm runs a single reminder, and leaving their code untouched keeps changes
 * from upstream easy to merge.
 */
@Composable
fun ReReminderApp() {
    val navController = rememberNavController()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.TIMER,
        ) {
            composable(
                route = Routes.TIMER,
                enterTransition = { recedeIn() },
                exitTransition = { recedeOut() },
                popEnterTransition = { recedeIn() },
                popExitTransition = { recedeOut() },
            ) {
                BreakTimerRoute(
                    onSettings = { navController.navigateFrom(Routes.TIMER, Routes.SETTINGS) },
                )
            }

            composable(
                route = Routes.SETTINGS,
                enterTransition = { slideIn() },
                popExitTransition = { slideOut() },
            ) {
                SettingsRoute(onBack = { navController.popFrom(Routes.SETTINGS) })
            }
        }
    }
}

/**
 * Navigation guarded against double taps.
 *
 * A screen keeps receiving input while it animates out, so a second tap on the back arrow (or
 * back, then Save) used to pop again — taking the list with it and leaving an empty window — and
 * two quick taps on a card stacked two editors. Each action now only acts if the screen it came
 * from is still the one on top.
 */
private fun NavController.popFrom(route: String) {
    if (currentDestination?.route == route) popBackStack()
}

private fun NavController.navigateFrom(route: String, destination: String) {
    if (currentDestination?.route == route) navigate(destination)
}

// --- Transitions ----------------------------------------------------------
//
// All of these run on Motion.screenEnter/screenExit, never on springs, so predictive back can
// scrub them and every part of a transition ends on the same frame (see Motion).

/**
 * The timer stepping back behind whatever opened on top of it.
 *
 * It only dims rather than fading out completely: it sits underneath the screen that is fading
 * in, and two half-transparent screens let the bare window background flash through.
 */
private fun recedeOut(): ExitTransition =
    fadeOut(tween(Motion.SCREEN_EXIT_MILLIS, easing = LinearEasing), targetAlpha = 0.4f) +
        scaleOut(Motion.screenExit(), targetScale = 0.96f)

private fun recedeIn(): EnterTransition =
    fadeIn(tween(Motion.SCREEN_ENTER_MILLIS, easing = LinearEasing), initialAlpha = 0.4f) +
        scaleIn(Motion.screenEnter(), initialScale = 0.96f)

private fun AnimatedContentTransitionScope<*>.slideIn(): EnterTransition =
    slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = Motion.screenEnter(),
    ) + fadeIn(tween(Motion.SCREEN_ENTER_MILLIS, easing = LinearEasing))

private fun AnimatedContentTransitionScope<*>.slideOut(): ExitTransition =
    slideOutOfContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.End,
        animationSpec = Motion.screenExit(),
    ) + fadeOut(tween(Motion.SCREEN_EXIT_MILLIS, easing = LinearEasing))
