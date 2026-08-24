package com.instructor.lessonroutes

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.instructor.lessonroutes.data.AppDatabase
import com.instructor.lessonroutes.navigation.AppNavHost
import com.instructor.lessonroutes.ui.theme.LessonRoutesTheme

// FragmentActivity (not the plain ComponentActivity this used to be) -- a safe
// superclass swap, since FragmentActivity itself extends ComponentActivity, so
// setContent {} and every existing Compose usage here is unaffected. Needed
// for TomTomNavigationScreen.kt's embedded NavigationFragment: a Fragment
// needs a real FragmentManager to attach to (supportFragmentManager), which a
// bare ComponentActivity doesn't provide.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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
