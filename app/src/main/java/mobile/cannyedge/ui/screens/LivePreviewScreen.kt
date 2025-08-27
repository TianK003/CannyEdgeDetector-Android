package mobile.cannyedge.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mobile.cannyedge.ui.components.BackButton

@Composable
fun LivePreviewScreen(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Your live preview content here
        Text(
            "Live Preview Screen",
            modifier = Modifier.align(Alignment.Center)
        )

        BackButton(
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        )
    }
}