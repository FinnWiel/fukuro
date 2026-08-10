package fukuro

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(vm: ShelfViewModel, onBack: () -> Unit) {
    val upload by vm.upload.collectAsState()
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var series by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) picked = uris }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Upload a book") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            Text(
                "Uploads go to your Audiobookshelf server and appear in the library after its scan.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(title, { title = it }, label = { Text("Title *") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(author, { author = it }, label = { Text("Author") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(series, { series = it }, label = { Text("Series (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { picker.launch("audio/*") }, modifier = Modifier.fillMaxWidth()) {
                Text(if (picked.isEmpty()) "Pick audio file(s)" else "${picked.size} file(s) selected — tap to change")
            }
            Spacer(Modifier.height(16.dp))
            if (upload.running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            upload.message?.let {
                Text(
                    it,
                    color = if (upload.success) MaterialTheme.colorScheme.primary
                    else if (upload.running) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = { vm.uploadBook(title, author, series, picked) },
                enabled = !upload.running && title.isNotBlank() && picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (upload.running) "Uploading…" else "Upload") }
            if (upload.success) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.resetUpload(); title = ""; author = ""; series = ""; picked = emptyList() },
                    modifier = Modifier.fillMaxWidth()) { Text("Upload another") }
            }
        }
    }
}
