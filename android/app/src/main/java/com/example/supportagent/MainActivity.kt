package com.example.supportagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class Message(val sender: String, val text: String)
data class Ticket(val id: Int, val summary: String, var status: String = "OPEN")
data class Order(val id: String, var status: String, val item: String, val price: Int)

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

        // Accept both "ORD-1001" and just "1001" as an order ID.
        val order = orders.firstOrNull { orderItem ->
            text.contains(orderItem.id.lowercase()) ||
                    text.contains(orderItem.id.removePrefix("ORD-").lowercase())
        }

        val isOrderTrackingRequest = listOf(
            "where is my order",
            "where are my orders",
            "where is my orders",
            "track my order",
            "track my orders",
            "order status",
            "order tracking",
            "delivery status",
            "delivery",
            "delayed",
            "late",
            "orders"
        ).any { text.contains(it) }

        if (isOrderTrackingRequest) {
            if (order == null) {
                return "Sure. Please share your order ID, for example ORD-1001 or 1001."
            }
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
    val messages = remember {
        mutableStateListOf(Message("Agent", "Hi! I'm your support assistant. How can I help?"))
    }
    var input by remember { mutableStateOf("") }

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
