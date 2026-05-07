package app.sanctum.machina.crash

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.sanctum.machina.R
import app.sanctum.machina.logexport.ExportSource
import app.sanctum.machina.logexport.LogExportManager
import app.sanctum.machina.ui.theme.SanctumTheme
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private const val LOGS_DIR = "logs"
private const val CRASH_LOG = "crash.log"
private const val TIMESTAMP_PATTERN = "yyyyMMdd-HHmm"

/**
 * Two-button recovery screen that lets the user save the diagnostic `.txt`
 * after an uncaught exception in the main process.
 *
 * Intentionally a plain [ComponentActivity] — NOT `@AndroidEntryPoint`
 * (Decision 5): the `:crash` process has a fresh Hilt graph that we refuse to
 * rely on during recovery. [LogExportManager] is built via its non-Hilt
 * secondary constructor, which assembles its collaborators from [android.content.Context]
 * alone. The activity runs in `android:process=":crash"` (declared in
 * `AndroidManifest.xml` by Task 5) so it survives `Process.killProcess` of
 * the main process.
 */
class CrashReportActivity : ComponentActivity() {

    private lateinit var logExportManager: LogExportManager

    /**
     * In-flight guard against a second SAF launch while the first dialog is
     * still open. Backed by `mutableStateOf` (rather than the plain `var`
     * named in the task hints) so Compose can recompose the save button's
     * `enabled` state atomically when the guard flips. Activity-owned, not
     * `rememberSaveable`: the SAF launcher survives rotation via
     * [ActivityResultRegistry], so resetting the guard across configuration
     * changes is acceptable.
     */
    private var launching by mutableStateOf(false)

    private val saveLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        lifecycleScope.launch {
            try {
                handleSafResult(uri)
            } finally {
                launching = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the crash screen out of Recents thumbnails and OEM screen-capture collectors.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        logExportManager = LogExportManager(applicationContext)

        setContent {
            SanctumTheme {
                CrashReportScreen(
                    launching = launching,
                    onSaveClick = ::onSaveClicked,
                    onCloseClick = ::finish,
                )
            }
        }
    }

    private fun onSaveClicked() {
        if (launching) return
        launching = true
        saveLogLauncher.launch("sanctum-log-${filenameTimestamp()}.txt")
    }

    private suspend fun handleSafResult(uri: Uri?) {
        if (uri == null) return
        try {
            val content = logExportManager.buildExport(ExportSource.CrashReport)
            logExportManager.writeTo(uri, content)
            File(filesDir, "$LOGS_DIR/$CRASH_LOG").delete()
            Toast.makeText(applicationContext, R.string.log_export_success_toast, Toast.LENGTH_SHORT).show()
            finish()
        } catch (_: IOException) {
            Toast.makeText(applicationContext, R.string.log_export_error_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun filenameTimestamp(): String =
        SimpleDateFormat(TIMESTAMP_PATTERN, Locale.ROOT).format(Date())
}

@Composable
private fun CrashReportScreen(
    launching: Boolean,
    onSaveClick: () -> Unit,
    onCloseClick: () -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = stringResource(R.string.crash_report_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.crash_report_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSaveClick, enabled = !launching) {
                    Text(stringResource(R.string.log_export_save_button))
                }
                TextButton(onClick = onCloseClick) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        }
    }
}
