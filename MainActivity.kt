package com.resumeai.app

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ResumeAiScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResumeAiScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var jobDescription by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Upload your existing CV to get started.") }
    var resultBytes by remember { mutableStateOf<ByteArray?>(null) }
    var resultName by remember { mutableStateOf("ResumeAI_Updated_CV.docx") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected resume"
            resultBytes = null
            status = "Resume selected. Add your instructions below."
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF3157D5),
            secondary = Color(0xFF6578A8),
            background = Color(0xFFF6F7FB)
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("CV Modifier-by Sourov", fontWeight = FontWeight.Bold)
                            Text("Your AI-powered CV editor", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).background(Color(0xFFF6F7FB))
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF14234B)),
                    shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Build a CV that opens doors.", style = MaterialTheme.typography.headlineSmall,
                            color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Upload your resume. Tell us what you want to improve. Let AI do the editing.",
                            color = Color(0xFFD8E1FF), style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFF9DB7FF))
                            Spacer(Modifier.width(8.dp))
                            Text("ATS-friendly • Job-specific • Editable", color = Color.White,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Text("1. Upload your resume", fontWeight = FontWeight.SemiBold)
                OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CloudUpload, null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(34.dp))
                        Text(if (fileName.isBlank()) "PDF or Word document" else fileName,
                            fontWeight = FontWeight.Medium)
                        Text("Choose a PDF or DOCX file", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { picker.launch(arrayOf("application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) }) {
                            Text("Choose resume")
                        }
                    }
                }

                Text("2. Tell us what to change", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = instructions, onValueChange = { instructions = it },
                    modifier = Modifier.fillMaxWidth(), minLines = 4,
                    label = { Text("Your instructions") },
                    placeholder = { Text("Example: Rewrite my CV for a Territory Sales Manager role. Make it ATS-friendly and highlight industrial sales, dealer management and revenue growth.") },
                    shape = RoundedCornerShape(16.dp)
                )
                OutlinedTextField(
                    value = jobDescription, onValueChange = { jobDescription = it },
                    modifier = Modifier.fillMaxWidth(), minLines = 3,
                    label = { Text("Target job description (optional)") },
                    placeholder = { Text("Paste the job description here to tailor your CV...") },
                    shape = RoundedCornerShape(16.dp)
                )

                Button(
                    onClick = {
                        val uri = selectedUri
                        if (uri == null) { status = "Please select a resume first."; return@Button }
                        if (instructions.isBlank()) { status = "Please describe the changes you want."; return@Button }
                        scope.launch {
                            busy = true
                            status = "Reading your resume and generating an improved version..."
                            resultBytes = null
                            try {
                                val bytes = withContext(Dispatchers.IO) {
                                    val input = context.contentResolver.openInputStream(uri)
                                        ?: error("Could not open selected file")
                                    val temp = File.createTempFile("resumeai_upload_", ".bin", context.cacheDir)
                                    input.use { ins -> temp.outputStream().use { outs -> ins.copyTo(outs) } }
                                    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                                    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                                        .addFormDataPart("instructions", instructions)
                                        .addFormDataPart("job_description", jobDescription)
                                        .addFormDataPart("resume", fileName.ifBlank { "resume" },
                                            temp.asRequestBody(mime.toMediaType()))
                                        .build()
                                    val request = Request.Builder()
                                        .url(BuildConfig.BASE_URL.trimEnd('/') + "/api/v1/rewrite")
                                        .post(body).build()
                                    val client = OkHttpClient.Builder()
                                        .callTimeout(180, TimeUnit.SECONDS).build()
                                    client.newCall(request).execute().use { response ->
                                        if (!response.isSuccessful) {
                                            error("Server error ${response.code}: ${response.body?.string()?.take(500)}")
                                        }
                                        response.body?.bytes() ?: error("Empty response from server")
                                    }
                                }
                                resultBytes = bytes
                                status = "Your revised CV is ready. Download it to your device."
                            } catch (e: Exception) {
                                status = "Could not generate CV: ${e.message ?: "Please try again."}"
                            } finally { busy = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White,
                        strokeWidth = 2.dp)
                    else {
                        Icon(Icons.Default.AutoAwesome, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Generate improved CV")
                    }
                }

                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Status", fontWeight = FontWeight.SemiBold)
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        if (resultBytes != null) {
                            Button(onClick = {
                                try {
                                    val values = ContentValues().apply {
                                        put(MediaStore.Downloads.DISPLAY_NAME, resultName)
                                        put(MediaStore.Downloads.MIME_TYPE,
                                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                                        put(MediaStore.Downloads.IS_PENDING, 1)
                                    }
                                    val resolver = context.contentResolver
                                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                        ?: error("Unable to create download")
                                    resolver.openOutputStream(uri)?.use { it.write(resultBytes!!) }
                                        ?: error("Unable to save file")
                                    values.clear()
                                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                                    resolver.update(uri, values, null, null)
                                    status = "Saved to Downloads/ResumeAI_Updated_CV.docx"
                                } catch (e: Exception) {
                                    status = "Download failed: ${e.message}"
                                }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Download, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Download modified CV")
                            }
                        }
                    }
                }
                Text("Your CV contains personal information. Only upload documents you have permission to process. Review every AI-generated detail before using it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
