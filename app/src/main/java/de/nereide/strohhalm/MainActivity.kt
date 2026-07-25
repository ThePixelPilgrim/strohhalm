package de.nereide.strohhalm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import de.nereide.strohhalm.ui.theme.StrohhalmTheme

/** Single-activity host for the Compose navigation graph. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StrohhalmTheme {
                Surface {
                    Text("Strohhalm")
                }
            }
        }
    }
}
