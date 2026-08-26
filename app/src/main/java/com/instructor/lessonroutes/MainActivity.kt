package com.instructor.lessonroutes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.instructor.lessonroutes.data.AppDatabase
import com.instructor.lessonroutes.navigation.AppNavHost
import com.instructor.lessonroutes.ui.theme.LessonRoutesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must come before super.onCreate() (AndroidX's own documented
        // requirement) -- see Theme.LessonRoutes.Splash in themes.xml for
        // what this actually shows (a flat background, no app icon at all).
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val database = AppDatabase.getInstance(applicationContext)
        setContent {
            LessonRoutesTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(database = database, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
