package mobile.cannyedge.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import mobile.cannyedge.R

@Composable
fun CameraSwitchButton(
    onSwitchCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onSwitchCamera,
        modifier = modifier
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_switch_camera),
            contentDescription = "Switch camera",
            modifier = Modifier.size(36.dp)
        )
    }
}