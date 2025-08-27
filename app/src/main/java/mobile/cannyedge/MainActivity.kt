package mobile.cannyedge

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import mobile.cannyedge.ui.screens.CameraScreen
import mobile.cannyedge.ui.theme.CannyEdgeTheme
import org.opencv.android.OpenCVLoader
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import mobile.cannyedge.ui.screens.HomeScreen
import mobile.cannyedge.ui.screens.LivePreviewScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CannyEdgeTheme {
                if (OpenCVLoader.initLocal()) {
                    Log.d("OpenCV", "OpenCV loaded successfully")
                }

                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = "home"
                ) {
                    composable("home") {
                        HomeScreen(
                            onNavigateToCamera = { navController.navigate("camera") },
                            onNavigateToLivePreview = { navController.navigate("livepreview") }
                        )
                    }
                    composable("camera") {
                        CameraScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("livepreview") {
                        LivePreviewScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}