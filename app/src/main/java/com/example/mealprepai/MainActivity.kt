package com.example.mealprepai

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MealPrepApp()
        }
    }
}

private object AppPrefsKeys {
    const val PREFS = "mealprep_prefs"
    const val API_KEY = "api_key"
    const val ENDPOINT = "endpoint"
    const val MODEL = "model"
    const val GOAL = "goal"
}

private data class AppSettings(
    val apiKey: String,
    val endpoint: String,
    val model: String,
    val goal: String
)

private fun loadSettings(context: Context): AppSettings {
    val prefs = context.getSharedPreferences(AppPrefsKeys.PREFS, Context.MODE_PRIVATE)
    return AppSettings(
        apiKey = prefs.getString(AppPrefsKeys.API_KEY, "") ?: "",
        endpoint = prefs.getString(
            AppPrefsKeys.ENDPOINT,
            "https://api.openai.com/v1/chat/completions"
        ) ?: "https://api.openai.com/v1/chat/completions",
        model = prefs.getString(AppPrefsKeys.MODEL, "gpt-4.1-mini") ?: "gpt-4.1-mini",
        goal = prefs.getString(AppPrefsKeys.GOAL, "High protein meal prep") ?: "High protein meal prep"
    )
}

private fun saveSettings(context: Context, settings: AppSettings) {
    context.getSharedPreferences(AppPrefsKeys.PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(AppPrefsKeys.API_KEY, settings.apiKey)
        .putString(AppPrefsKeys.ENDPOINT, settings.endpoint)
        .putString(AppPrefsKeys.MODEL, settings.model)
        .putString(AppPrefsKeys.GOAL, settings.goal)
        .apply()
}

@Composable
fun MealPrepApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(onOpenSettings = { navController.navigate("settings") })
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultText by remember { mutableStateOf("Take or choose a food photo to get meal prep ideas.") }
    var loading by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // no-op, actual camera launcher is triggered manually after permission is granted if needed
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            selectedBitmap = bitmap
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedBitmap = uriToBitmap(context, uri)
        }
    }

    suspend fun runAnalysis() {
        val bitmap = selectedBitmap ?: run {
            snackbarHostState.showSnackbar("Please add a food photo first.")
            return
        }

        val settings = loadSettings(context)
        if (settings.apiKey.isBlank()) {
            snackbarHostState.showSnackbar("Add your API key in Settings first.")
            return
        }

        loading = true
        try {
            resultText = withContext(Dispatchers.IO) { analyzeFoodPhoto(bitmap, settings) }
        } catch (e: Exception) {
            resultText = "Error: ${e.message ?: "Unknown error"}"
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meal Prep AI") },
                actions = {
                    TextButton(onClick = onOpenSettings) {
                        Text("Settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Snap a photo of ingredients, leftovers, or a meal and get meal prep ideas.",
                style = MaterialTheme.typography.bodyLarge
            )

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedBitmap != null) {
                        Image(
                            bitmap = selectedBitmap!!.asImageBitmap(),
                            contentDescription = "Selected food photo",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text("No photo selected")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    when {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED -> cameraLauncher.launch(null)
                        else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }) {
                    Text("Take Photo")
                }

                Button(onClick = { galleryLauncher.launch("image/*") }) {
                    Text("Choose Photo")
                }
            }
            val scope = rememberCoroutineScope()
            Button(
                onClick = { scope.launch { runAnalysis() } },
                enabled = !loading && selectedBitmap != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Get Meal Prep Ideas")
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Meal Prep Suggestions", fontWeight = FontWeight.Bold)
                    Text(resultText)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(loadSettings(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = settings.apiKey,
                onValueChange = { settings = settings.copy(apiKey = it) },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = settings.endpoint,
                onValueChange = { settings = settings.copy(endpoint = it) },
                label = { Text("Endpoint") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = settings.model,
                onValueChange = { settings = settings.copy(model = it) },
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = settings.goal,
                onValueChange = { settings = settings.copy(goal = it) },
                label = { Text("Meal Prep Goal") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    saveSettings(context, settings)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Settings")
            }

            Text(
                "Tip: The default endpoint and model are already filled in. Usually you only need to paste your API key.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun uriToBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        val input: InputStream? = context.contentResolver.openInputStream(uri)
        BitmapFactory.decodeStream(input)
    } catch (e: Exception) {
        null
    }
}

private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
    val outputStream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
    return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
}

private fun analyzeFoodPhoto(bitmap: Bitmap, settings: AppSettings): String {
    val client = OkHttpClient()
    val base64 = bitmapToBase64Jpeg(bitmap)

    val prompt = """
        You are a meal prep coach.
        Look at this food image and suggest practical meal prep ideas.
        The user's goal is: ${settings.goal}

        Return:
        1. what you think is in the photo
        2. 3 to 5 meal prep ideas
        3. estimated protein/carb/fat balance for each idea
        4. one budget-friendly version
        5. one healthier version
        6. a short grocery add-on list
        Keep it easy to read and useful.
    """.trimIndent()

    val contentArray = JSONArray().apply {
        put(JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        })
        put(JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().apply {
                put("url", "data:image/jpeg;base64,$base64")
            })
        })
    }

    val message = JSONObject().apply {
        put("role", "user")
        put("content", contentArray)
    }

    val bodyJson = JSONObject().apply {
        put("model", settings.model)
        put("messages", JSONArray().put(message))
        put("max_tokens", 700)
    }

    val request = Request.Builder()
        .url(settings.endpoint)
        .addHeader("Authorization", "Bearer ${settings.apiKey}")
        .addHeader("Content-Type", "application/json")
        .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
        .build()

    client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IllegalStateException("API ${response.code}: $body")
        }

        val json = JSONObject(body)
        val choices = json.optJSONArray("choices")
            ?: throw IllegalStateException("No choices in API response: $body")
        val first = choices.getJSONObject(0)
        val messageObj = first.getJSONObject("message")
        return messageObj.optString("content", "No content returned.")
    }
}
