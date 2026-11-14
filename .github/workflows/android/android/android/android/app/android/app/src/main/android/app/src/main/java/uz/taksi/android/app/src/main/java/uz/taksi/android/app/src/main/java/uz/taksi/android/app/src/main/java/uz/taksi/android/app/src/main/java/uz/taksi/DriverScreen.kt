package uz.taksi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun DriverScreen(onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by rememberMyLocation()
    var start by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var available by remember { mutableStateOf(listOf<JSONObject>()) }
    var mine by remember { mutableStateOf(listOf<JSONObject>()) }
    var stats by remember { mutableStateOf<JSONObject?>(null) }
    var msg by remember { mutableStateOf("") }

    ShareLocation(me)
    LaunchedEffect(me) {
        val m = me
        if (m != null && start == null) start = m
    }
    LaunchedEffect(Unit) {
        while (true) {
            try {
                mine = io { Api.call("GET", "/orders/mine").getJSONArray("orders").items() }
                available = io { Api.call("GET", "/orders/available").getJSONArray("orders").items() }
                stats = io { Api.call("GET", "/stats") }
            } catch (e: Exception) { }
            delay(3000)
        }
    }

    fun post(path: String, body: JSONObject = JSONObject()) {
        scope.launch {
            try { io { Api.call("POST", path, body) }; msg = "" }
            catch (e: Exception) { msg = e.message ?: "Xato" }
        }
    }

    val active = mine.firstOrNull { it.optString("status") in ACTIVE }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Header("🚖 Haydovchi", onLogout)

        stats?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Bugun: ${it.optInt("todayCount")} ta safar · ${som(it.optLong("todaySum"))}")
                    Text("Naqd: ${som(it.optLong("todayCash"))} · Karta: ${som(it.optLong("todayCard"))}")
                    Text("Jami: ${it.optInt("totalCount")} ta safar · ${som(it.optLong("totalSum"))}")
                }
            }
        }

        if (active != null) {
            val id = active.optInt("id")
            val st = active.optString("status")
            val isCash = active.optString("payMethod") == "cash"
            val pickup = Pair(active.optDouble("fromLat"), active.optDouble("fromLng"))
            val dropoff = Pair(active.optDouble("toLat"), active.optDouble("toLng"))

            val pins = buildList {
                add(Pin(pickup.first, pickup.second, "Yo'lovchini olish joyi", GREEN))
                add(Pin(dropoff.first, dropoff.second, "Borish manzili", RED))
                locOf(active, "passengerLoc")?.let { add(Pin(it.first, it.second, "Yo'lovchi (hozir)", ORANGE)) }
                me?.let { add(Pin(it.first, it.second, "Men", BLUE)) }
            }
            MapBox(pins, pickup, Modifier.height(300.dp))
            Text("🟢 olish joyi · 🔴 manzil · 🟠 yo'lovchi · 🔵 men", style = MaterialTheme.typography.bodySmall)

            OrderCard(active) {
                val p = active.optJSONObject("passenger")
                if (p != null) Text("Yo'lovchi: ${p.optString("name")} · ${p.optString("phone")}")
                if (!isCash) Text("Karta: yo'lovchi safar tugagach ilovada to'laydi", style = MaterialTheme.typography.bodySmall)

                val goTo = if (st == "started") dropoff else pickup
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { openNavigator(ctx, goTo.first, goTo.second, if (st == "started") "Manzil" else "Yo'lovchi") },
                ) { Text("🧭 Navigatorda ochish") }

                val next = mapOf(
                    "accepted" to ("arrived" to "Yetib keldim"),
                    "arrived" to ("started" to "Safarni boshlash"),
                    "started" to ("done" to (if (isCash) "Yakunlash (naqd oldim)" else "Yakunlash")),
                )[st]
                if (next != null) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { post("/orders/$id/status", JSONObject().put("status", next.first)) },
                    ) { Text(next.second) }
                }
                if (st == "accepted" || st == "arrived") {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { post("/orders/$id/cancel") },
                    ) { Text("Rad etish") }
                }
            }
        } else {
            val pins = buildList {
                available.forEach { add(Pin(it.optDouble("fromLat"), it.optDouble("fromLng"), "Buyurtma #${it.optInt("id")}", GREEN)) }
                me?.let { add(Pin(it.first, it.second, "Men", BLUE)) }
            }
            MapBox(pins, start ?: DEFAULT_CENTER, Modifier.height(260.dp))
            Text("🟢 yo'lovchilar · 🔵 men", style = MaterialTheme.typography.bodySmall)

            Text("Yangi buyurtmalar", style = MaterialTheme.typography.titleMedium)
            if (available.isEmpty()) Text("Hozircha buyurtma yo'q…")
            available.forEach { o ->
                OrderCard(o) {
                    if (o.has("pickupKm")) Text(String.format("Yo'lovchigacha: ~%.1f km", o.optDouble("pickupKm")))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { post("/orders/${o.optInt("id")}/accept") },
                    ) { Text("Qabul qilish") }
                }
            }
        }

        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
    }
}
