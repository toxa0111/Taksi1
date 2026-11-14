package uz.taksi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun PassengerScreen(onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by rememberMyLocation()
    var start by remember { mutableStateOf<Pair<Double, Double>?>(null) }   // GPS birinchi topilgan joy
    var focus by remember { mutableStateOf<Pair<Double, Double>?>(null) }   // xarita markaziga o'tkazish
    var pickup by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var dest by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var target by remember { mutableStateOf("from") }
    var pay by remember { mutableStateOf("cash") }
    var search by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf<JSONObject?>(null) }
    var orders by remember { mutableStateOf(listOf<JSONObject>()) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    ShareLocation(me)
    LaunchedEffect(me) {
        val m = me
        if (m != null && start == null) {
            start = m
            if (pickup == null) pickup = m           // odatda yo'lovchi turgan joydan chaqiradi
        }
    }
    LaunchedEffect(pickup, dest) {
        val a = pickup
        val b = dest
        quote = null
        if (a != null && b != null) {
            try {
                quote = io { Api.call("GET", "/estimate?flat=${a.first}&flng=${a.second}&tlat=${b.first}&tlng=${b.second}") }
            } catch (e: Exception) { msg = e.message ?: "Xato" }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            try { orders = io { Api.call("GET", "/orders/mine").getJSONArray("orders").items() } } catch (e: Exception) { }
            delay(3000)
        }
    }

    val active = orders.firstOrNull { it.optString("status") in ACTIVE }
    val needPay = orders.firstOrNull {
        it.optString("status") == "done" && it.optString("payMethod") == "card" && it.optString("payStatus") != "paid"
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Header("🚕 Taksi chaqirish", onLogout)

        if (needPay != null) {
            OrderCard(needPay) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            try { io { Api.call("POST", "/orders/${needPay.optInt("id")}/pay", JSONObject()) }; msg = "" }
                            catch (e: Exception) { msg = e.message ?: "Xato" }
                        }
                    },
                ) { Text("💳 Karta bilan to'lash") }
            }
        }

        if (active != null) {
            val pins = buildList {
                add(Pin(active.optDouble("fromLat"), active.optDouble("fromLng"), "Olinadigan joy", GREEN))
                add(Pin(active.optDouble("toLat"), active.optDouble("toLng"), "Borish manzili", RED))
                locOf(active, "driverLoc")?.let { add(Pin(it.first, it.second, "Haydovchi", ORANGE)) }
                me?.let { add(Pin(it.first, it.second, "Men", BLUE)) }
            }
            MapBox(
                pins,
                Pair(active.optDouble("fromLat"), active.optDouble("fromLng")),
                Modifier.height(300.dp),
            )
            Text("🟢 olinadigan joy · 🔴 manzil · 🟠 haydovchi · 🔵 men", style = MaterialTheme.typography.bodySmall)
            OrderCard(active) {
                val d = active.optJSONObject("driver")
                if (d != null) Text("Haydovchi: ${d.optString("name")} · ${d.optString("phone")}")
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            try { io { Api.call("POST", "/orders/${active.optInt("id")}/cancel", JSONObject()) } }
                            catch (e: Exception) { msg = e.message ?: "Xato" }
                        }
                    },
                ) { Text("Bekor qilish") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("🟢 Qayerdan", target == "from", Modifier.weight(1f)) { target = "from" }
                Choice("🔴 Qayerga", target == "to", Modifier.weight(1f)) { target = "to" }
            }
            Text(
                if (target == "from") "Xaritaga bosib olinadigan joyni belgilang" else "Xaritaga bosib borish manzilini belgilang",
                style = MaterialTheme.typography.bodySmall,
            )

            val pins = buildList {
                pickup?.let { add(Pin(it.first, it.second, "Qayerdan", GREEN)) }
                dest?.let { add(Pin(it.first, it.second, "Qayerga", RED)) }
                me?.let { add(Pin(it.first, it.second, "Men", BLUE)) }
            }
            MapBox(pins, focus ?: start ?: DEFAULT_CENTER, Modifier.height(300.dp)) { lat, lng ->
                if (target == "from") { pickup = Pair(lat, lng); target = "to" } else dest = Pair(lat, lng)
            }

            OutlinedButton(
                enabled = me != null, modifier = Modifier.fillMaxWidth(),
                onClick = { val m = me; if (m != null) { pickup = m; focus = m; target = "to" } },
            ) { Text("📍 Mening joyimdan olib ketish") }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    label = { Text("Manzil bo'yicha qidirish") }, singleLine = true, modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = !busy && search.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true; msg = ""
                            val r = io { geocode(ctx, search) }
                            if (r == null) {
                                msg = "Manzil topilmadi. Xaritaga bosib belgilang"
                            } else {
                                if (target == "from") { pickup = r; target = "to" } else dest = r
                                focus = r
                            }
                            busy = false
                        }
                    },
                ) { Text("Topish") }
            }

            val q = quote
            val a = pickup
            val b = dest
            if (q != null && a != null && b != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(som(q.optLong("price")), style = MaterialTheme.typography.headlineMedium)
                        Text(String.format("Masofa: taxminan %.1f km", q.optDouble("km")))
                        Text("To'lov usuli", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Choice("💵 Naqd", pay == "cash", Modifier.weight(1f)) { pay = "cash" }
                            Choice("💳 Karta", pay == "card", Modifier.weight(1f)) { pay = "card" }
                        }
                        if (pay == "card") Text("Karta bilan safar tugagach to'laysiz.", style = MaterialTheme.typography.bodySmall)
                        Button(
                            enabled = !busy, modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        val fromName = io { reverseGeocode(ctx, a.first, a.second) }
                                        val toName = io { reverseGeocode(ctx, b.first, b.second) }
                                        val body = JSONObject()
                                            .put("from", fromName).put("to", toName)
                                            .put("fromLat", a.first).put("fromLng", a.second)
                                            .put("toLat", b.first).put("toLng", b.second)
                                            .put("payMethod", pay)
                                        io { Api.call("POST", "/orders", body) }
                                        dest = null; target = "to"; msg = ""
                                    } catch (e: Exception) { msg = e.message ?: "Xato" }
                                    busy = false
                                }
                            },
                        ) { Text("Taksi chaqirish") }
                    }
                }
            } else {
                Text("Olinadigan joy va borish manzilini belgilang — narx shu yerda chiqadi.", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)

        val history = orders.filter { it.optString("status") !in ACTIVE && it !== needPay }
        if (history.isNotEmpty()) {
            Text("Oldingi safarlar", style = MaterialTheme.typography.titleMedium)
            history.forEach { OrderCard(it) }
        }
    }
}
