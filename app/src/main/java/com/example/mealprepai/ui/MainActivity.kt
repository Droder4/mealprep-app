package com.example.mealprepai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.mealprepai.ui.theme.MealPrepAITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64

private val Context.dataStore by preferencesDataStore(name = "meal_prep_settings")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MealPrepAITheme {
                val vm: MealPrepViewModel = viewModel(
                    factory = MealPrepViewModel.factory(applicationContext)
                )
                App(vm)
            }
        }
    }
}

data class SettingsState(
    val apiKey: String = "",
    val endpoint: String = BuildConfig.DEFAULT_API_URL,
    val model: String = BuildConfig.DEFAULT_MODEL,
    val prepGoal: String = "High-protein meal prep for 3 to 5 days",
    val includeMacros: Boolean = true
)

data class UiState(
    val imageUri: Uri? = null,
    val resultText: String = "Take or choose a food photo, then tap Analyze.",
    val isLoading: Boolean = false,
    val errorText: String? = null
)

class MealPrepViewModel(
    private val repo: MealPrepRepository,
    private val settingsStore: SettingsStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _settings = MutableStateFlow(SettingsState())
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    init {
        viewModelScope.launch {
            _settings.value = settingsStore.load()
        }
    }

    fun onImageSelected(uri: Uri?) {
        _uiState.value = _uiState.value.copy(imageUri = uri, errorText = null)
    }

    fun updateSettings(newState: SettingsState) {
        _settings.value = newState
        viewModelScope.launch {
            settingsStore.save(newState)
        }
    }

    fun analyze(context: Context) {
        val uri = _uiState.value.imageUri ?: run {
            _uiState.value = _uiState.value.copy(errorText = "Choose a photo first.")
            return
        }
        val currentSettings = _settings.value
        if (currentSettings.apiKey.isBlank()) {
            _uiState.value = _uiState.value.copy(errorText = "Add your API key in Settings first.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, errorText = null)
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readAllBytesSafely() }
                    ?: error("Could not read the selected image.")
                val result = repo.analyzePhoto(bytes, currentSettings)
                _uiState.value = _uiState.value.copy(isLoading = false, resultText = result, errorText = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorText = e.message ?: "Something went wrong while analyzing the image."
                )
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MealPrepViewModel(MealPrepRepository(), SettingsStore(context)) as T
            }
        }
    }
}

class MealPrepRepository {
    private val client = OkHttpClient()

    fun analyzePhoto(imageBytes: ByteArray, settings: SettingsState): String {
        val base64Image = Base64.getEncoder().encodeToString(imageBytes)
        val instructions = buildString {
            append("You are a meal-prep coach. Analyze the food photo and give practical meal prep ideas. ")
            append("Goal: ${settings.prepGoal}. ")
            append("Return: 1) what foods you can identify, 2) 3 meal prep ideas, 3) shopping add-ons if needed, ")
            append("4) safe storage tips, 5) quick batch-cooking steps.")
            if (settings.includeMacros) append(" Also estimate rough protein, carbs, and fats per serving.")
        }

        val payload = JSONObject()
            .put("model", settings.model)
            .put("input", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray()
                        .put(JSONObject().put("type", "input_text").put("text", instructions))
                        .put(JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64,$base64Image"))
                    )
            ))

        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(settings.endpoint)
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("API error ${response.code}: ${raw.take(300)}")
            }
            return extractOutputText(raw)
        }
    }

    private fun extractOutputText(raw: String): String {
        val json = JSONObject(raw)

        if (json.has("output_text")) {
            return json.optString("output_text").ifBlank { "No meal prep text returned." }
        }

        val output = json.optJSONArray("output")
        if (output != null) {
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    val text = part.optString("text")
                    if (text.isNotBlank()) return text
                }
            }
        }

        return raw
    }
}

class SettingsStore(private val context: Context) {
    suspend fun load(): SettingsState {
        val prefs = context.dataStore.data.first()
        return SettingsState(
            apiKey = prefs[Keys.API_KEY] ?: "",
            endpoint = prefs[Keys.ENDPOINT] ?: BuildConfig.DEFAULT_API_URL,
            model = prefs[Keys.MODEL] ?: BuildConfig.DEFAULT_MODEL,
            prepGoal = prefs[Keys.PREP_GOAL] ?: "High-protein meal prep for 3 to 5 days",
            includeMacros = prefs[Keys.INCLUDE_MACROS] ?: true
        )
    }

    suspend fun save(settings: SettingsState) {
        context.dataStore.edit { prefs ->
            prefs[Keys.API_KEY] = settings.apiKey
            prefs[Keys.ENDPOINT] = settings.endpoint
            prefs[Keys.MODEL] = settings.model
            prefs[Keys.PREP_GOAL] = settings.prepGoal
            prefs[Keys.INCLUDE_MACROS] = settings.includeMacros
        }
    }

    private object Keys {
        val API_KEY = stringPreferencesKey("api_key")
        val ENDPOINT = stringPreferencesKey("endpoint")
        val MODEL = stringPreferencesKey("model")
        val PREP_GOAL = stringPreferencesKey("prep_goal")
        val INCLUDE_MACROS = booleanPreferencesKey("include_macros")
    }
}

private fun InputStream.readAllBytesSafely(): ByteArray {
    val buffer = ByteArrayOutputStream()
    val data = ByteArray(8 * 1024)
    while (true) {
        val count = read(data)
        if (count == -1) break
        buffer.write(data, 0, count)
    }
    return buffer.toByteArray()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MealPrepViewModel) {
    val uiState by vm.uiState.collectAsState()
    val settings by vm.settings.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meal Prep AI") },
                colors = TopAppBarDefaults.topAppBarColors(),
                actions = {
                    TextButton(onClick = { showSettings = !showSettings }) {
                        Text(if (showSettings) "Done" else "Settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (showSettings) {
                SettingsCard(settings = settings, onSettingsChanged = vm::updateSettings)
            }

            PhotoPickerCard(
                imageUri = uiState.imageUri,
                onImageSelected = vm::onImageSelected
            )

            Button(
                onClick = { vm.analyze(context) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isLoading
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Analyzing...")
                } else {
                    Text("Analyze food photo")
                }
            }

            uiState.errorText?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }

            ResultCard(uiState.resultText)
        }
    }
}

@Composable
fun SettingsCard(settings: SettingsState, onSettingsChanged: (SettingsState) -> Unit) {
    var apiKey by remember(settings) { mutableStateOf(settings.apiKey) }
    var endpoint by remember(settings) { mutableStateOf(settings.endpoint) }
    var model by remember(settings) { mutableStateOf(settings.model) }
    var prepGoal by remember(settings) { mutableStateOf(settings.prepGoal) }
    var includeMacros by remember(settings) { mutableStateOf(settings.includeMacros) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    onSettingsChanged(SettingsState(apiKey, endpoint, model, prepGoal, includeMacros))
                },
                label = { Text("API key") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = endpoint,
                onValueChange = {
                    endpoint = it
                    onSettingsChanged(SettingsState(apiKey, endpoint, model, prepGoal, includeMacros))
                },
                label = { Text("API endpoint") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = model,
                onValueChange = {
                    model = it
                    onSettingsChanged(SettingsState(apiKey, endpoint, model, prepGoal, includeMacros))
                },
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = prepGoal,
                onValueChange = {
                    prepGoal = it
                    onSettingsChanged(SettingsState(apiKey, endpoint, model, prepGoal, includeMacros))
                },
                label = { Text("Meal prep goal") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = includeMacros,
                    onCheckedChange = {
                        includeMacros = it
                        onSettingsChanged(SettingsState(apiKey, endpoint, model, prepGoal, includeMacros))
                    }
                )
                Spacer(Modifier.width(12.dp))
                Text("Include rough macros")
            }
        }
    }
}

@Composable
fun PhotoPickerCard(imageUri: Uri?, onImageSelected: (Uri?) -> Unit) {
    val context = LocalContext.current
    val takePicturePreview = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        bitmap?.let { onImageSelected(ImageUtils.saveBitmapAndGetUri(context, it)) }
    }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> onImageSelected(uri) }

    val requestPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) takePicturePreview.launch(null)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Food photo", style = MaterialTheme.typography.titleLarge)
            if (imageUri != null) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = "Selected food image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No photo selected")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            takePicturePreview.launch(null)
                        } else {
                            requestPermission.launch(Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Take photo")
                }
                Button(
                    onClick = { pickImage.launch("image/*") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Choose photo")
                }
            }
        }
    }
}

@Composable
fun ResultCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Meal prep ideas", style = MaterialTheme.typography.titleLarge)
            Text(text)
        }
    }
}
