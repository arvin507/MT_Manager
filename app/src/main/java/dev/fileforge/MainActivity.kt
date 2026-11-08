package dev.fileforge

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fileforge.ui.ApkScreen
import dev.fileforge.ui.AppsScreen
import dev.fileforge.ui.BrowserScreen
import dev.fileforge.ui.BrowserViewModel
import dev.fileforge.ui.FileForgeTheme
import dev.fileforge.ui.ViewerScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FileForgeTheme {
                val vm: BrowserViewModel = viewModel()
                val context = LocalContext.current
                var granted by remember { mutableStateOf(hasStorageAccess(context)) }

                DisposableEffect(context) {
                    val activity = context as? ComponentActivity
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            granted = hasStorageAccess(context)
                        }
                    }
                    activity?.lifecycle?.addObserver(observer)
                    onDispose { activity?.lifecycle?.removeObserver(observer) }
                }

                if (!granted) {
                    PermissionScreen(
                        onRequest = { requestStorageAccess(context) },
                        onRecheck = { granted = hasStorageAccess(context) }
                    )
                } else {
                    val viewerData = vm.viewer
                    val report = vm.report
                    when {
                        viewerData != null -> {
                            BackHandler { vm.closeViewer() }
                            ViewerScreen(data = viewerData, onClose = { vm.closeViewer() })
                        }

                        report != null -> {
                            BackHandler { vm.closeReport() }
                            ApkScreen(report = report, onBack = { vm.closeReport() })
                        }

                        vm.showApps -> {
                            BackHandler { vm.closeApps() }
                            AppsScreen(vm)
                        }

                        else -> BrowserScreen(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit, onRecheck: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "FileForge",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "需要「所有文件访问权限」才能浏览和修改存储里的文件。本应用不联网，所有操作只发生在本地。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        Button(onClick = onRequest) { Text("去授权") }
        TextButton(onClick = onRecheck) { Text("已授权，重新检测") }
    }
}

private fun hasStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

private fun requestStorageAccess(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        val result = runCatching { context.startActivity(appIntent) }
        if (result.isFailure) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    } else {
        (context as? Activity)?.let {
            ActivityCompat.requestPermissions(
                it,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                1001
            )
        }
    }
}
