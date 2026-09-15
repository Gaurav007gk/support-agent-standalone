package com.example.supportagent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

data class Message(val sender: String, val text: String)
data class Ticket(val id: Int, val summary: String, var status: String = "OPEN")
data class Order(val id: String, var status: String, val item: String, val price: Int)

enum class PhotoIssue(val displayName: String) {
    BAD_FOOD("Bad food"),
    EXPIRED("Expired product"),
    SPILLAGE("Spillage"),
    BROKEN("Broken/damaged product")
}

data class VisionResult(
    val verdict: String,
    val confidence: Double,
    val evidence: String,
    val nextAction: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

class SupportEngine {
    val orders = mutableListOf(
        Order("ORD-1001", "OUT_FOR_DELIVERY", "Paneer Biryani", 320),
        Order("ORD-1002", "PREPARING", "Burger Combo", 280),
        Order("ORD-1003", "DELIVERED", "Pizza", 450)
    )
    val tickets = mutableStateListOf<Ticket>()
    private var nextTicket = 1001

    fun detectPhotoIssue(input: String): PhotoIssue? {
        val text = input.lowercase()
        return when {
            listOf("expired", "expiry", "past expiry", "out of date").any { text.contains(it) } -> PhotoIssue.EXPIRED
            listOf("spill", "spilled", "spillage", "leaking", "leaked", "leakage").any { text.contains(it) } -> PhotoIssue.SPILLAGE
            listOf("broken", "damaged", "cracked", "crushed").any { text.contains(it) } -> PhotoIssue.BROKEN
            listOf("bad food", "bad product", "spoiled", "spoilt", "rotten", "stale", "food is bad").any { text.contains(it) } -> PhotoIssue.BAD_FOOD
            else -> null
        }
    }

    fun reply(input: String): String {
        val text = input.lowercase().trim()

        if (listOf("human", "executive", "agent please", "talk to someone").any { text.contains(it) }) {
            escalate("Customer requested a human executive")
            return "I'll connect you with a human executive. Ticket #${tickets.last().id} has been created."
        }

        if (listOf("medical emergency", "severe chest pain", "unconscious").any { text.contains(it) }) {
            escalate("Safety-sensitive issue")
            return "This may require immediate human assistance. I have escalated this conversation to an executive."
        }

        val photoIssue = detectPhotoIssue(text)
        if (photoIssue != null) {
            val order = findOrder(text)
            return if (order == null) {
                "I'm sorry about the ${photoIssue.displayName.lowercase()} issue. Please share your order ID and upload at least 2 clear photos of the product."
            } else {
                "I'm sorry about the ${photoIssue.displayName.lowercase()} issue with ${order.id}. Please upload at least 2 clear photos showing the issue. I'll check the photos before proceeding."
            }
        }

        val order = findOrder(text)
        val isOrderTrackingRequest = listOf(
            "where is my order", "where are my orders", "where is my orders",
            "track my order", "track my orders", "order status", "order tracking",
            "delivery status", "delivery", "delayed", "late", "orders"
        ).any { text.contains(it) }

        if (isOrderTrackingRequest) {
            if (order == null) return "Sure. Please share your order ID, for example ORD-1001 or 1001."
            return when (order.status) {
                "OUT_FOR_DELIVERY" -> "Your order ${order.id} is out for delivery and should arrive soon."
                "PREPARING" -> "Your order ${order.id} is still being prepared by the restaurant."
                "DELIVERED" -> "Your order ${order.id} shows as delivered."
                "CANCELLED" -> "Your order ${order.id} has been cancelled."
                else -> "I checked ${order.id}, but I need an executive to investigate further."
            }
        }

        if (listOf("cancel", "cancellation").any { text.contains(it) }) {
            if (order == null) return "Please share the order ID you want to cancel."
            if (order.status == "PREPARING") {
                order.status = "CANCELLED"
                return "Order ${order.id} has been cancelled successfully."
            }
            escalate("Cancellation requires executive review for ${order.id}")
            return "I can't safely cancel ${order.id} at this stage, so I've escalated it to an executive."
        }

        if (listOf("missing item", "missing", "wrong item").any { text.contains(it) }) {
            if (order == null) return "Please share the order ID with the missing/wrong item."
            if (order.id == "ORD-1003") {
                escalate("Refund failed for ${order.id}")
                return "I couldn't complete the refund automatically. I've escalated this to an executive."
            }
            return "I've initiated a refund for the affected item in ${order.id}. An executive can follow up if needed."
        }

        if (listOf("refund failed", "refund issue", "refund").any { text.contains(it) }) {
            escalate("Refund issue reported by customer")
            return "I've escalated the refund issue to a human executive for review."
        }

        if (text == "hi" || text == "hello" || text.startsWith("hi ") || text.startsWith("hello ")) {
            return "Hi! I'm your support assistant. Tell me what went wrong and I'll try to resolve it."
        }

        escalate("Unable to confidently understand or resolve the request")
        return "I don't want to give you an incorrect answer. I've escalated this conversation to a human executive."
    }

    fun verifyPhotoEvidence(context: Context, photos: List<Uri>, issue: PhotoIssue, apiKey: String): VisionResult {
        require(photos.size >= 2) { "Please upload at least 2 photos before verification." }
        require(apiKey.isNotBlank()) { "Please configure an OpenAI API key first." }

        val imageDataUrls = photos.mapIndexed { index, uri ->
            val bytes = imageToJpeg(context, uri)
                ?: throw IllegalArgumentException("Photo ${index + 1} could not be read.")
            "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }

        val today = LocalDate.now()
        val prompt = """
You are the visual evidence verifier for a customer-support app.

Customer complaint category: ${issue.displayName}
Today's date: $today
Number of uploaded photos: ${photos.size}

Analyze ALL uploaded photos together. Determine whether the photos actually provide visual evidence for the customer's stated complaint.
Do not approve a complaint just because a product is present. The claimed issue must be visibly supported.

Rules:
- EXPIRED: only mark VERIFIED when an expiry/best-before date is clearly readable and is past today's date. If the date is unreadable, mark UNCLEAR.
- SPILLAGE: look for visible spilled liquid/food, leakage, wet packaging, or an open container with contents visibly spilled.
- BROKEN/DAMAGED: look for visible cracks, breakage, crushing, torn packaging, or other physical damage.
- BAD FOOD: look for visible signs such as obvious spoilage, mold, severe contamination, or clearly abnormal appearance. A photo cannot prove that food is medically unsafe; if it is not visually clear, use UNCLEAR.
- Require the product/problem to be visible in the evidence. Reject unrelated screenshots, selfies, blank images, or unrelated objects.
- Use UNCLEAR when image quality, angle, lighting, or missing label information prevents a reliable conclusion.
- Do not invent details that are not visible.

Return ONLY valid JSON, with exactly these fields:
{
  "verdict": "VERIFIED" | "NOT_VERIFIED" | "UNCLEAR",
  "confidence": number between 0 and 1,
  "evidence": "short explanation of what is visibly supported or missing",
  "next_action": "REFUND_OR_REPLACE" | "ASK_FOR_CLEARER_PHOTOS" | "ESCALATE_TO_HUMAN"
}
""".trimIndent()

        val request = JSONObject().apply {
            // gpt-5 supports image input through the Responses API.
            put("model", "gpt-5")
            put("input", org.json.JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    val content = org.json.JSONArray()
                    content.put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", prompt)
                    })
                    imageDataUrls.forEach { dataUrl ->
                        content.put(JSONObject().apply {
                            put("type", "input_image")
                            put("image_url", dataUrl)
                            put("detail", "high")
                        })
                    }
                    put("content", content)
                }
            ))
        }

        val responseText = postOpenAi(apiKey, request.toString())
        return parseVisionResult(responseText)
    }

    private fun imageToJpeg(context: Context, uri: Uri): ByteArray? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val original = input.use { BitmapFactory.decodeStream(it) } ?: return null

        val maxDimension = 1600
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(original.width, original.height))
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                original,
                (original.width * scale).toInt(),
                (original.height * scale).toInt(),
                true
            )
        } else original

        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
        if (bitmap !== original) bitmap.recycle()
        original.recycle()
        return output.toByteArray()
    }

    private fun postOpenAi(apiKey: String, body: String): String {
        val connection = (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 90_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                throw IllegalStateException("OpenAI API error ($status): ${extractError(response)}")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun extractError(response: String): String {
        return try {
            JSONObject(response).optJSONObject("error")?.optString("message") ?: response.take(300)
        } catch (_: Exception) {
            response.take(300)
        }
    }

    private fun parseVisionResult(response: String): VisionResult {
        val root = JSONObject(response)
        val output = root.optJSONArray("output") ?: throw IllegalStateException("No model output returned.")
        val textBuilder = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") {
                    textBuilder.append(part.optString("text"))
                }
            }
        }

        val raw = textBuilder.toString().trim()
        val jsonText = raw.substringAfter("```json", raw).substringBeforeLast("```").trim()
        val result = JSONObject(jsonText)
        return VisionResult(
            verdict = result.optString("verdict", "UNCLEAR"),
            confidence = result.optDouble("confidence", 0.0),
            evidence = result.optString("evidence", "The model did not provide evidence details."),
            nextAction = result.optString("next_action", "ESCALATE_TO_HUMAN")
        )
    }

    fun createPhotoTicket(issue: PhotoIssue, orderId: String?, verification: VisionResult): Int {
        val summary = buildString {
            append("${issue.displayName} photo verification: ${verification.verdict}")
            if (orderId != null) append(" for $orderId")
            append(" | ${verification.evidence}")
        }
        escalate(summary)
        return tickets.last().id
    }

    private fun findOrder(text: String): Order? {
        return orders.firstOrNull { orderItem ->
            text.contains(orderItem.id.lowercase()) ||
                    text.contains(orderItem.id.removePrefix("ORD-").lowercase())
        }
    }

    private fun escalate(summary: String) {
        tickets.add(Ticket(nextTicket++, summary))
    }
}

@Composable
fun App() {
    val engine = remember { SupportEngine() }
    var screen by remember { mutableStateOf("CUSTOMER") }
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("support_agent_settings", Context.MODE_PRIVATE)
    }
    var apiKey by remember { mutableStateOf(preferences.getString("openai_api_key", "") ?: "") }

    MaterialTheme {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { screen = "CUSTOMER" }) { Text("Customer") }
                Button(onClick = { screen = "EXECUTIVE" }) { Text("Executive") }
                TextButton(onClick = { showSettings = true }) { Text("AI Settings") }
            }
            if (screen == "CUSTOMER") CustomerScreen(engine, apiKey) else ExecutiveScreen(engine)
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("AI image verification") },
                text = {
                    Column {
                        Text("Enter your OpenAI API key. For this demo it is stored locally on this phone. Do not use a production secret in a client app.")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("OpenAI API key") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        preferences.edit().putString("openai_api_key", apiKey.trim()).apply()
                        showSettings = false
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showSettings = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
fun CustomerScreen(engine: SupportEngine, apiKey: String) {
    val context = LocalContext.current
    val messages = remember {
        mutableStateListOf(Message("Agent", "Hi! I'm your support assistant. How can I help?"))
    }
    var input by remember { mutableStateOf("") }
    var photoIssue by remember { mutableStateOf<PhotoIssue?>(null) }
    var selectedPhotos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var verificationMessage by remember { mutableStateOf<String?>(null) }
    var isVerifying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 6)
    ) { uris ->
        selectedPhotos = uris
        verificationMessage = null
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("Customer Support", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages) { m ->
                Surface(
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m.sender, style = MaterialTheme.typography.labelMedium)
                        Text(m.text)
                    }
                }
            }

            photoIssue?.let { issue ->
                item {
                    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("AI photo verification", style = MaterialTheme.typography.titleMedium)
                            Text("Issue: ${issue.displayName}")
                            Text("Upload a minimum of 2 clear photos showing the problem.")
                            Spacer(Modifier.height(8.dp))

                            Button(onClick = {
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }) {
                                Text(if (selectedPhotos.isEmpty()) "Upload photos" else "Add / replace photos")
                            }

                            if (selectedPhotos.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text("Selected photos: ${selectedPhotos.size}/2 minimum")
                                Row(Modifier.fillMaxWidth()) {
                                    selectedPhotos.take(3).forEachIndexed { index, uri ->
                                        AsyncImage(
                                            model = uri,
                                            contentDescription = "Product photo ${index + 1}",
                                            modifier = Modifier.size(88.dp).padding(4.dp),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }

                                Button(
                                    enabled = selectedPhotos.size >= 2 && !isVerifying,
                                    onClick = {
                                        if (apiKey.isBlank()) {
                                            verificationMessage = "Open AI Settings and enter your OpenAI API key first."
                                            return@Button
                                        }

                                        isVerifying = true
                                        verificationMessage = "AI is analyzing all ${selectedPhotos.size} photos..."
                                        val photos = selectedPhotos
                                        scope.launch {
                                            try {
                                                val result = withContext(Dispatchers.IO) {
                                                    engine.verifyPhotoEvidence(context, photos, issue, apiKey)
                                                }
                                                val ticketId = engine.createPhotoTicket(issue, null, result)
                                                verificationMessage = null
                                                messages.add(
                                                    Message(
                                                        "Agent",
                                                        buildString {
                                                            append("AI verification: ${result.verdict}\n")
                                                            append("Confidence: ${(result.confidence * 100).toInt()}%\n")
                                                            append("Evidence: ${result.evidence}\n")
                                                            when (result.nextAction) {
                                                                "REFUND_OR_REPLACE" -> append("The evidence supports the reported issue. The case is ready for refund/replacement review.\n")
                                                                "ASK_FOR_CLEARER_PHOTOS" -> append("Please upload clearer photos so I can verify the issue.\n")
                                                                else -> append("I've sent the case to a human executive for review.\n")
                                                            }
                                                            append("Ticket #$ticketId")
                                                        }
                                                    )
                                                )
                                                photoIssue = null
                                                selectedPhotos = emptyList()
                                            } catch (e: Exception) {
                                                verificationMessage = e.message ?: "Image verification failed. Please try again."
                                            } finally {
                                                isVerifying = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (isVerifying) "Verifying..." else "Verify photos with AI")
                                }
                            }

                            verificationMessage?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Describe your issue...") }
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (input.isNotBlank()) {
                    val userText = input.trim()
                    messages.add(Message("You", userText))
                    messages.add(Message("Agent", engine.reply(userText)))
                    engine.detectPhotoIssue(userText)?.let {
                        photoIssue = it
                        selectedPhotos = emptyList()
                        verificationMessage = null
                    }
                    input = ""
                }
            }) { Text("Send") }
        }
    }
}

@Composable
fun ExecutiveScreen(engine: SupportEngine) {
    var selected by remember { mutableStateOf<Ticket?>(null) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Executive Console", style = MaterialTheme.typography.headlineSmall)
        Text("Escalated tickets: ${engine.tickets.size}")
        Spacer(Modifier.height(8.dp))

        if (engine.tickets.isEmpty()) {
            Text("No escalated tickets yet.")
        } else {
            LazyColumn {
                items(engine.tickets) { ticket ->
                    Card(
                        onClick = { selected = ticket },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Ticket #${ticket.id}")
                            Text(ticket.summary)
                            Text("Status: ${ticket.status}")
                        }
                    }
                }
            }
        }

        selected?.let { ticket ->
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Text("Ticket #${ticket.id}", style = MaterialTheme.typography.titleLarge)
            Text(ticket.summary)
            Row {
                Button(onClick = { ticket.status = "HUMAN_ACTIVE" }) { Text("Take Over") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { ticket.status = "RESOLVED" }) { Text("Resolve") }
            }
        }
    }
}
