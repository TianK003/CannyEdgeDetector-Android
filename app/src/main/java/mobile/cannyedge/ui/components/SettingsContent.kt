package mobile.cannyedge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsContent(
    currentStage: Int,
    kernelSize: Int,
    onKernelChange: (Int) -> Unit,
    lowOverride: Int?,
    highOverride: Int?,
    autoLow: Int,
    autoHigh: Int,
    onThresholdsChange: (Int?, Int?) -> Unit,
    onClose: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        when (currentStage) {
            // Stage 1 — Smoothing
            1 -> {
                Text("Gaussian Kernel Size (odd)", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(3, 5, 7, 9).forEach { k ->
                        FilterChip(
                            selected = kernelSize == k,
                            onClick = { onKernelChange(k) },
                            label = { Text("$k") }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                var kernelText by remember(kernelSize) { mutableStateOf(kernelSize.toString()) }
                OutlinedTextField(
                    value = kernelText,
                    onValueChange = {
                        kernelText = it.filter { ch -> ch.isDigit() }
                        kernelText.toIntOrNull()?.let { v ->
                            val odd = if (v % 2 == 0) v + 1 else v
                            if (odd in 3..25) onKernelChange(odd)
                        }
                    },
                    label = { Text("Custom kernel") },
                    supportingText = { Text("Odd number, e.g., 3, 5, 7…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Stage 4 — Double Thresholds/Hysteresis
            4 -> {
                Text("Double Thresholds (0–255)", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                // Text fields show overrides if set; else empty with auto placeholders
                var lowText by remember(lowOverride, autoLow) {
                    mutableStateOf(lowOverride?.toString() ?: "")
                }
                var highText by remember(highOverride, autoHigh) {
                    mutableStateOf(highOverride?.toString() ?: "")
                }

                OutlinedTextField(
                    value = lowText,
                    onValueChange = { new ->
                        val filtered = new.filter { it.isDigit() }
                        lowText = filtered
                        val l = filtered.toIntOrNull()?.coerceIn(0, 255)
                        val h = highText.toIntOrNull()?.coerceIn(0, 255) ?: highOverride
                        val (ll, hh) = if (l != null && h != null && l > h) h to l else l to h
                        onThresholdsChange(ll, hh)
                    },
                    label = { Text("Low (auto: $autoLow)") },
                    placeholder = { Text("$autoLow") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = highText,
                    onValueChange = { new ->
                        val filtered = new.filter { it.isDigit() }
                        highText = filtered
                        val l = lowText.toIntOrNull()?.coerceIn(0, 255) ?: lowOverride
                        val h = filtered.toIntOrNull()?.coerceIn(0, 255)
                        val (ll, hh) = if (l != null && h != null && l > h) h to l else l to h
                        onThresholdsChange(ll, hh)
                    },
                    label = { Text("High (auto: $autoHigh)") },
                    placeholder = { Text("$autoHigh") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                // Sliders for quick tuning (initialize from effective values)
                var lowSlider by remember(lowOverride, autoLow) {
                    mutableStateOf((lowOverride ?: autoLow).toFloat())
                }
                var highSlider by remember(highOverride, autoHigh) {
                    mutableStateOf((highOverride ?: autoHigh).toFloat())
                }

                Text("Quick adjust")
                Spacer(Modifier.height(8.dp))

                Text("Low: ${lowSlider.toInt()}")
                Slider(
                    value = lowSlider,
                    onValueChange = {
                        lowSlider = it.coerceIn(0f, highSlider)
                        onThresholdsChange(lowSlider.toInt(), highSlider.toInt())
                        lowText = lowSlider.toInt().toString()
                    },
                    valueRange = 0f..255f
                )

                Text("High: ${highSlider.toInt()}")
                Slider(
                    value = highSlider,
                    onValueChange = {
                        highSlider = it.coerceIn(lowSlider, 255f)
                        onThresholdsChange(lowSlider.toInt(), highSlider.toInt())
                        highText = highSlider.toInt().toString()
                    },
                    valueRange = 0f..255f
                )

                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            // Clear overrides → revert to auto
                            lowText = ""
                            highText = ""
                            onThresholdsChange(null, null)
                            lowSlider = autoLow.toFloat()
                            highSlider = autoHigh.toFloat()
                        }
                    ) { Text("Reset to Auto") }
                }
            }

            else -> {
                Text("No adjustable settings for this stage.")
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.align(Alignment.End)
        ) { Text("Close") }

        Spacer(Modifier.height(8.dp))
    }
}