package dev.openauth.app.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import dev.openauth.app.R
import dev.openauth.app.ui.graphics.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrScannerScreen(onResult: (String) -> Unit, onCancel: () -> Unit, onManual: () -> Unit = onCancel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var unavailable by remember { mutableStateOf(!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    val result by rememberUpdatedState(onResult)
    LaunchedEffect(Unit) { if (!allowed && !unavailable) launcher.launch(Manifest.permission.CAMERA) }
    if (!allowed || unavailable) {
        CameraUnavailable(onManual, onCancel, onRetry = if (!unavailable) ({ launcher.launch(Manifest.permission.CAMERA) }) else null)
        return
    }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(preview, lifecycleOwner) {
        val disposed = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val cameraPreview = Preview.Builder().build().apply { surfaceProvider = preview.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }
        analysis.setAnalyzer(executor) { frame ->
            try {
                if (!disposed.get() && !delivered.get()) {
                    val plane = frame.planes[0]
                    val buffer = plane.buffer
                    val bytes = ByteArray(frame.width * frame.height)
                    for (y in 0 until frame.height) for (x in 0 until frame.width) bytes[y * frame.width + x] = buffer.get(y * plane.rowStride + x * plane.pixelStride)
                    val source = PlanarYUVLuminanceSource(bytes, frame.width, frame.height, 0, 0, frame.width, frame.height, false)
                    val text = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
                    if (text.length <= 8192 && text.toByteArray(Charsets.UTF_8).size <= 8192 && delivered.compareAndSet(false, true)) {
                        ContextCompat.getMainExecutor(context).execute { if (!disposed.get()) result(text) }
                    }
                }
            } catch (_: ReaderException) {
                // Отсутствие QR в очередном кадре — нормальный результат сканирования.
            } finally { reader.reset(); frame.close() }
        }
        future.addListener({
            if (!disposed.get()) {
                try {
                    provider = future.get()
                    provider!!.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis)
                } catch (_: Exception) { unavailable = true }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { disposed.set(true); analysis.clearAnalyzer(); provider?.unbind(cameraPreview, analysis); executor.shutdown() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val edge = size.minDimension * .72f
                drawRoundRect(Color.White, Offset((size.width-edge)/2, (size.height-edge)/2), Size(edge,edge), CornerRadius(22.dp.toPx()), style = Stroke(3.dp.toPx()))
            }
        }
        Text(stringResource(R.string.scan_hint), modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) { Text(stringResource(R.string.manual_entry)) }
    }
}

@Composable
fun CameraUnavailable(onManual: () -> Unit, onCancel: () -> Unit, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        OpenAuthIllustration(IllustrationKind.EMPTY_VAULT, Modifier.widthIn(max = 280.dp))
        Text(stringResource(R.string.camera_unavailable_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.camera_unavailable_body), modifier = Modifier.padding(vertical = 16.dp))
        Button(onClick = onManual, modifier = Modifier.testTag("camera-manual")) { Text(stringResource(R.string.manual_entry)) }
        if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.camera_permission)) }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}
