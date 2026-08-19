package com.instructor.lessonroutes.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.instructor.lessonroutes.data.AppDatabase
import com.instructor.lessonroutes.data.seedStaticDataIfNeeded
import com.instructor.lessonroutes.ui.generate.GenerateRouteScreen
import com.instructor.lessonroutes.ui.map.LiveMapScreen
import com.instructor.lessonroutes.ui.profiles.StudentProfilesScreen
import com.instructor.lessonroutes.ui.routes.CreateRouteScreen
import com.instructor.lessonroutes.ui.routes.FollowScreen
import com.instructor.lessonroutes.ui.routes.RouteDetailScreen
import com.instructor.lessonroutes.ui.routes.RouteListScreen
import com.instructor.lessonroutes.ui.settings.SettingsScreen

private const val LIVE_MAP = "liveMap"
private const val STUDENT_PROFILES = "studentProfiles"
private const val ROUTE_LIST = "routeList"
private const val ROUTE_DETAIL = "routeDetail/{routeId}"
private const val ROUTE_CREATE = "createRoute"
private const val ROUTE_GENERATE = "generateRoute"
private const val ROUTE_FOLLOW = "follow/{routeId}"
private const val SETTINGS = "settings"

@Composable
fun AppNavHost(database: AppDatabase, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val dao = remember { database.routeDao() }
    val schoolZoneDao = remember { database.schoolZoneDao() }
    val speedCameraDao = remember { database.speedCameraDao() }
    val profileDao = remember { database.studentProfileDao() }

    // Which student profile (if any) the route list is scoped to -- set by the
    // Student Profiles screen, read by the route list. Hoisted here rather than
    // carried as a nav argument so ROUTE_LIST can stay a single plain destination.
    var routeListFilter by remember { mutableStateOf<Long?>(null) }

    // One-time seed of static reference data (school zones, speed cameras) from
    // bundled asset snapshots -- see StaticDataSeeder.kt. Gates rendering the real
    // nav graph until it's done: LiveMapScreen loads from these tables on its own
    // first composition, so if it composed before this finished inserting ~4,000
    // rows, it would load an empty result and never re-fetch. Only matters on a
    // fresh install -- the count() check makes every later launch resolve instantly.
    var isStaticDataReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        seedStaticDataIfNeeded(context, database)
        isStaticDataReady = true
    }

    if (!isStaticDataReady) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    NavHost(navController = navController, startDestination = LIVE_MAP, modifier = modifier) {
        composable(LIVE_MAP) {
            LiveMapScreen(
                schoolZoneDao = schoolZoneDao,
                speedCameraDao = speedCameraDao,
                onPlanRouteClick = { navController.navigate(STUDENT_PROFILES) },
                onGenerateTripClick = { navController.navigate(ROUTE_GENERATE) },
            )
        }
        composable(STUDENT_PROFILES) {
            StudentProfilesScreen(
                profileDao = profileDao,
                onProfileClick = { profileId ->
                    routeListFilter = profileId
                    navController.navigate(ROUTE_LIST)
                },
                onAllRoutesClick = {
                    routeListFilter = null
                    navController.navigate(ROUTE_LIST)
                },
                // Returns to the existing live-map instance (the start destination,
                // always at the bottom of the stack) rather than pushing a new one --
                // that screen runs continuous GPS tracking, so reusing it avoids
                // restarting location listeners unnecessarily.
                onOverviewClick = { navController.popBackStack(LIVE_MAP, inclusive = false) },
            )
        }
        composable(ROUTE_LIST) {
            RouteListScreen(
                dao = dao,
                profileDao = profileDao,
                filterProfileId = routeListFilter,
                onRouteClick = { routeId -> navController.navigate("routeDetail/$routeId") },
                onCreateClick = { navController.navigate(ROUTE_CREATE) },
                onSettingsClick = { navController.navigate(SETTINGS) },
                onProfilesClick = { navController.navigate(STUDENT_PROFILES) },
                onOverviewClick = { navController.popBackStack(LIVE_MAP, inclusive = false) },
            )
        }
        composable(SETTINGS) {
            SettingsScreen(routeDao = dao, onBack = { navController.popBackStack() })
        }
        composable(
            route = ROUTE_DETAIL,
            arguments = listOf(navArgument("routeId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val routeId = backStackEntry.arguments?.getLong("routeId") ?: return@composable
            RouteDetailScreen(
                routeId = routeId,
                dao = dao,
                onFollowClick = { navController.navigate("follow/$it") },
            )
        }
        composable(ROUTE_CREATE) {
            CreateRouteScreen(
                dao = dao,
                profileDao = profileDao,
                preselectedProfileId = routeListFilter,
                onSaved = { navController.popBackStack() },
                onCancel = { navController.popBackStack() },
            )
        }
        composable(
            route = ROUTE_FOLLOW,
            arguments = listOf(navArgument("routeId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val routeId = backStackEntry.arguments?.getLong("routeId") ?: return@composable
            FollowScreen(routeId = routeId, dao = dao)
        }
        composable(ROUTE_GENERATE) {
            GenerateRouteScreen(
                dao = dao,
                profileDao = profileDao,
                schoolZoneDao = schoolZoneDao,
                speedCameraDao = speedCameraDao,
                preselectedProfileId = null,
                onBack = { navController.popBackStack() },
                // Deliberately doesn't navigate away -- after saving, the
                // instructor might still want to Regenerate or Open in nav app
                // from the same screen. "Close" (onBack) is the manual exit.
                onSaved = {},
            )
        }
    }
}
