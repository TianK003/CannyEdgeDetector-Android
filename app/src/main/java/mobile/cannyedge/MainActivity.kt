package mobile.cannyedge

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import mobile.cannyedge.ui.screens.CameraScreen
import mobile.cannyedge.ui.theme.CannyEdgeTheme
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CannyEdgeTheme {
                if (OpenCVLoader.initLocal()) {
                    Log.d("OpenCV", "OpenCV loaded successfully")
                }
                CameraScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}