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
import com.instructor.lessonroutes.ui.map.LiveMapScreen
import com.instructor.lessonroutes.ui.routes.CreateRouteScreen
import com.instructor.lessonroutes.ui.routes.FollowScreen
import com.instructor.lessonroutes.ui.routes.RouteDetailScreen
import com.instructor.lessonroutes.ui.routes.RouteListScreen
import com.instructor.lessonroutes.ui.settings.SettingsScreen

private const val LIVE_MAP = "liveMap"
private const val ROUTE_LIST = "routeList"
private const val ROUTE_DETAIL = "routeDetail/{routeId}"
private const val ROUTE_CREATE = "createRoute"
private const val ROUTE_FOLLOW = "follow/{routeId}"
private const val SETTINGS = "settings"

@Composable
fun AppNavHost(database: AppDatabase, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val dao = remember { database.routeDao() }
    val schoolZoneDao = remember { database.schoolZoneDao() }
    val speedCameraDao = remember { database.speedCameraDao() }

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
                onPlanRouteClick = { navController.navigate(ROUTE_LIST) },
            )
        }
        composable(ROUTE_LIST) {
            RouteListScreen(
                dao = dao,
                onRouteClick = { routeId -> navController.navigate("routeDetail/$routeId") },
                onCreateClick = { navController.navigate(ROUTE_CREATE) },
                onSettingsClick = { navController.navigate(SETTINGS) },
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
    }
}
