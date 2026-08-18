package com.instructor.lessonroutes.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.instructor.lessonroutes.data.AppDatabase
import com.instructor.lessonroutes.data.Route
import com.instructor.lessonroutes.data.RoutePoint
import com.instructor.lessonroutes.ui.routes.RouteDetailScreen
import com.instructor.lessonroutes.ui.routes.RouteListScreen
import kotlinx.coroutines.flow.first

private const val ROUTE_LIST = "routeList"
private const val ROUTE_DETAIL = "routeDetail/{routeId}"

@Composable
fun AppNavHost(database: AppDatabase, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val dao = remember { database.routeDao() }

    // Temporary: seed one fake route (a right-angle test path near Sydney) so the list
    // isn't empty before step 5 (tap-to-create) exists. Safe to delete once real route
    // creation lands — the empty-state message on the list screen takes over from there.
    LaunchedEffect(Unit) {
        if (dao.getAllRoutes().first().isEmpty()) {
            val id = dao.insertRoute(
                Route(
                    name = "Seed test route",
                    description = "Temporary seed route for steps 2-4",
                    notes = "Right-angle test path near Sydney CBD — safe to delete",
                    dateCreated = System.currentTimeMillis(),
                    tag = "test",
                ),
            )
            dao.insertPoints(
                listOf(
                    RoutePoint(routeId = id, latitude = -33.8688, longitude = 151.2093, sequenceOrder = 0),
                    RoutePoint(routeId = id, latitude = -33.8700, longitude = 151.2140, sequenceOrder = 1),
                    RoutePoint(routeId = id, latitude = -33.8735, longitude = 151.2110, sequenceOrder = 2),
                ),
            )
        }
    }

    NavHost(navController = navController, startDestination = ROUTE_LIST, modifier = modifier) {
        composable(ROUTE_LIST) {
            RouteListScreen(
                dao = dao,
                onRouteClick = { routeId -> navController.navigate("routeDetail/$routeId") },
            )
        }
        composable(
            route = ROUTE_DETAIL,
            arguments = listOf(navArgument("routeId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val routeId = backStackEntry.arguments?.getLong("routeId") ?: return@composable
            RouteDetailScreen(routeId = routeId, dao = dao)
        }
    }
}
