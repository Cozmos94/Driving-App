package com.instructor.lessonroutes.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.instructor.lessonroutes.data.AppDatabase
import com.instructor.lessonroutes.ui.map.LiveMapScreen
import com.instructor.lessonroutes.ui.routes.CreateRouteScreen
import com.instructor.lessonroutes.ui.routes.FollowScreen
import com.instructor.lessonroutes.ui.routes.RouteDetailScreen
import com.instructor.lessonroutes.ui.routes.RouteListScreen

private const val LIVE_MAP = "liveMap"
private const val ROUTE_LIST = "routeList"
private const val ROUTE_DETAIL = "routeDetail/{routeId}"
private const val ROUTE_CREATE = "createRoute"
private const val ROUTE_FOLLOW = "follow/{routeId}"

@Composable
fun AppNavHost(database: AppDatabase, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val dao = remember { database.routeDao() }

    NavHost(navController = navController, startDestination = LIVE_MAP, modifier = modifier) {
        composable(LIVE_MAP) {
            LiveMapScreen(onPlanRouteClick = { navController.navigate(ROUTE_LIST) })
        }
        composable(ROUTE_LIST) {
            RouteListScreen(
                dao = dao,
                onRouteClick = { routeId -> navController.navigate("routeDetail/$routeId") },
                onCreateClick = { navController.navigate(ROUTE_CREATE) },
            )
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
