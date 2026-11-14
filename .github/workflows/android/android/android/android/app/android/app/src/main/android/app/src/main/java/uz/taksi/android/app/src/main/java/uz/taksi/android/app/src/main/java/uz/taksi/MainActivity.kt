package uz.taksi

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("taksi", Context.MODE_PRIVATE)
        Api.token = prefs.getString("token", "") ?: ""
        Api.base = prefs.getString("base", Api.base) ?: Api.base
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App(prefs) } } }
    }
}

@Composable
fun App(prefs: SharedPreferences) {
    var role by remember { mutableStateOf(prefs.getString("role", "") ?: "") }
    val logout = {
        prefs.edit().remove("token").remove("role").apply()
        Api.token = ""
        role = ""
    }
    when (role) {
        "passenger" -> PassengerScreen(logout)
        "driver" -> DriverScreen(logout)
        else -> LoginScreen(prefs) { role = it }
    }
}

// ───────────────────────── Umumiy elementlar ─────────────────────────

@Composable
fun Choice(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = modifier) { Text(text) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
}

@Composable
fun Header(title: String, onLogout: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = onLogout) { Text("Chiqish") }
    }
}

@Composable
fun OrderCard(o: JSONObject, extra: @Composable ColumnScope.() -> Unit = {}) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(STATUS[o.optString("status")] ?: o.optString("status"), style = MaterialTheme.typography.titleMedium)
            Text("${o.optString("from")} → ${o.optString("to")}")
            Text(String.format("%.1f km · %s", o.optDouble("km"), som(o.optLong("price"))))
            Text(payText(o))
            extra()
        }
    }
}

/** Telefondagi navigator ilovasida (Google Maps, Yandex, 2GIS…) nuqtani ochadi. */
fun openNavigator(ctx: Context, lat: Double, lng: Double, label: String) {
    try {
        val uri = Uri.parse("geo:0,0?q=$lat,$lng(${Uri.encode(label)})")
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: Exception) { }
}

/** Joylashuvni serverga yuborib turadi (boshqa tomon xaritada ko'radi). */
@Composable
fun ShareLocation(me: Pair<Double, Double>?) {
    LaunchedEffect(me) {
        if (me != null) {
            try { io { Api.call("POST", "/location", JSONObject().put("lat", me.first).put("lng", me.second)) } } catch (e: Exception) { }
        }
    }
}

// ───────────────────────── Kirish ─────────────────────────

@Composable
fun LoginScreen(prefs: SharedPreferences, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf(Api.base) }
    var phone by remember { mutableStateOf("+998") }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("passenger") }
    var code by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("🚕 Taksi", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = base, onValueChange = { base = it },
            label = { Text("Server manzili") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = phone, onValueChange = { phone = it },
            label = { Text("Telefon raqam") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Ismingiz (birinchi marta kirganda)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Yo'lovchi", role == "passenger", Modifier.weight(1f)) { role = "passenger" }
            Choice("Haydovchi", role == "driver", Modifier.weight(1f)) { role = "driver" }
        }

        if (!sent) {
            Button(
                enabled = !busy, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        busy = true; msg = ""
                        try {
                            Api.base = base.trim().trimEnd('/')
                            val r = io { Api.call("POST", "/auth/request", JSONObject().put("phone", phone.trim())) }
                            sent = true
                            // Test rejimida server kodni o'zi qaytaradi. Haqiqiy SMS ulanganda bu qator o'chadi.
                            if (r.has("devCode")) msg = "Test kodi: ${r.optString("devCode")}"
                        } catch (e: Exception) { msg = e.message ?: "Xato" }
                        busy = false
                    }
                },
            ) { Text("Kod olish") }
        } else {
            OutlinedTextField(
                value = code, onValueChange = { code = it },
                label = { Text("SMS kod") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !busy, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val body = JSONObject()
                                .put("phone", phone.trim()).put("code", code.trim())
                                .put("name", name.trim()).put("role", role)
                            val r = io { Api.call("POST", "/auth/verify", body) }
                            val u = r.getJSONObject("user")
                            Api.token = r.getString("token")
                            prefs.edit()
                                .putString("token", Api.token).putString("role", u.getString("role"))
                                .putString("base", Api.base).apply()
                            onDone(u.getString("role"))
                        } catch (e: Exception) { msg = e.message ?: "Xato" }
                        busy = false
                    }
                },
            ) { Text("Kirish") }
            TextButton(onClick = { sent = false; code = ""; msg = "" }) { Text("Raqamni o'zgartirish") }
        }
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.primary)
    }
}
