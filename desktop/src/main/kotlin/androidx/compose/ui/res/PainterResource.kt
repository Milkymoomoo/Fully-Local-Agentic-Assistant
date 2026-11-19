package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

/** Desktop stand-in for painterResource(R.drawable.x): the app's drawables are PNGs under the desktop resources. */
@Composable
fun painterResource(id: Int): Painter = painterResource(app.belle.assistant.R.drawable.path(id))
