package dev.openauth.app.clipboard

import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Живёт в shell и продолжает проверять owner после блокировки vault или переключения вкладки. */
@Composable
fun ClipboardExpiryEffect(clipboard: SensitiveClipboard) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle, clipboard) {
        val focusObserver = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) clipboard.retryExpiredClip()
        }
        val observer = view.viewTreeObserver
        observer.addOnWindowFocusChangeListener(focusObserver)
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) clipboard.retryExpiredClip()
        }
        lifecycle.addObserver(lifecycleObserver)
        if (view.hasWindowFocus()) clipboard.retryExpiredClip()
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            val liveObserver = if (observer.isAlive) observer else view.viewTreeObserver
            if (liveObserver.isAlive) liveObserver.removeOnWindowFocusChangeListener(focusObserver)
        }
    }
}
