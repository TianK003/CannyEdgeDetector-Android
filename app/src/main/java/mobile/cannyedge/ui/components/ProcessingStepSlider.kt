package mobile.cannyedge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ProcessingStepSlider(
    sliderPosition: Float,
    onPositionChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val positions = 5
    val stageLabels = listOf(
        "Original Image",
        "Noise Reduction (Smoothing)",
        "Gradient Detection",
        "Non-Maxima Suppression",
        "Hysteresis (Final)"
    )

    val currentStage = sliderPosition.toInt().coerceIn(0, positions - 1)

    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth(0.9f)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .shadow(8.dp, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Text(
                text = stageLabels[currentStage],
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(bottom = 8.dp)
            )


            Slider(
                value = sliderPosition,
                onValueChange = onPositionChanged,
                valueRange = 0f..(positions - 1).toFloat(),
                steps = positions - 2,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.surface,
                    activeTrackColor = MaterialTheme.colorScheme.surface,
                    inactiveTrackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (i in 0 until positions) {
                    Text(
                        text = i.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (i == currentStage) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        }
                    )
                }
            }
        }
    }
}