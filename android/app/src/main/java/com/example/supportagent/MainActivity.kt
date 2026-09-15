package com.example.supportagent

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

data class Message(val sender: String, val text: String)
data class Ticket(val id: Int, val summary: String, var status: String = "OPEN")
data class Order(val id: String, var status: String, val item: String, val price: Int)

enum class PhotoIssue(val displayName: String) {
    BAD_FOOD("Bad food"),
    EXPIRED("Expired product"),
    SPILLAGE("Spillage"),
    BROKEN("Broken/damaged product")
}

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

    fun verifyPhotoEvidence(context: Context, photos: List<Uri>, issue: PhotoIssue): String {
        if (photos.size < 2) return "Please upload at least 2 photos before verification."

        for ((index, uri) in photos.withIndex()) {
            val valid = try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream) != null
                } ?: false
            } catch (_: Exception) {
                false
            }
            if (!valid) return "Photo ${index + 1} could not be read. Please upload a clear image."
        }

        return "${photos.size} photos received for '${issue.displayName}'. Photo quality/count checks passed."
    }

    fun createPhotoTicket(issue: PhotoIssue, orderId: String?): Int {
        val summary = if (orderId != null) {
            "${issue.displayName} complaint with photo evidence for $orderId"
        } else {
            "${issue.displayName} complaint with photo evidence"
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

    MaterialTheme {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = { screen = "CUSTOMER" }) { Text("Customer") }
                Button(onClick = { screen = "EXECUTIVE" }) { Text("Executive") }
            }
            if (screen == "CUSTOMER") CustomerScreen(engine) else ExecutiveScreen(engine)
        }
    }
}

@Composable
fun CustomerScreen(engine: SupportEngine) {
    val context = LocalContext.current
    val messages = remember {
        mutableStateListOf(Message("Agent", "Hi! I'm your support assistant. How can I help?"))
    }
    var input by remember { mutableStateOf("") }
    var photoIssue by remember { mutableStateOf<PhotoIssue?>(null) }
    var selectedPhotos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var verificationMessage by remember { mutableStateOf<String?>(null) }

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
                            Text("Photo evidence required", style = MaterialTheme.typography.titleMedium)
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
                                    enabled = selectedPhotos.size >= 2,
                                    onClick = {
                                        val result = engine.verifyPhotoEvidence(context, selectedPhotos, issue)
                                        verificationMessage = result
                                        if (selectedPhotos.size >= 2 && result.contains("quality/count checks passed")) {
                                            val ticketId = engine.createPhotoTicket(issue, null)
                                            messages.add(
                                                Message(
                                                    "Agent",
                                                    "$result\n\nI've created ticket #$ticketId for human review. The standalone build currently validates the image files and photo count; true visual verification (for example, deciding whether a photo actually shows spillage or an expired label) needs a vision model/API."
                                                )
                                            )
                                            photoIssue = null
                                            selectedPhotos = emptyList()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Verify photos")
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
