package com.instructor.lessonroutes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.instructor.lessonroutes.ui.map.RouteMapView
import com.instructor.lessonroutes.ui.theme.LessonRoutesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LessonRoutesTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Step 1: just prove the map renders from a free tile source.
                        // Route list / create / follow screens replace this in later steps.
                        RouteMapView(modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
