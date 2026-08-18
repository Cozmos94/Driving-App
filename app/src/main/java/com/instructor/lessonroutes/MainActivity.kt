package com.instructor.lessonroutes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.instructor.lessonroutes.data.AppDatabase
import com.instructor.lessonroutes.navigation.AppNavHost
import com.instructor.lessonroutes.ui.theme.LessonRoutesTheme

class MainActivity : ComponentActivity() {
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
