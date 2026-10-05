package io.github.veritasx1.lidio

import io.github.veritasx1.lidio.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Connecting a server (first start, or "Server hinzufügen"): kind, address, name, password – an iOS form. Jellyfin and
 *  Emby keep only the access token they hand out; the password is not stored for them. */
@Composable
fun Connect(onDone: (Account) -> Unit, onCancel: (() -> Unit)?, check: ((Account) -> String)? = null, onLocal: (() -> Unit)? = null) {
    val ink = Ink
    val context = androidx.compose.ui.platform.LocalContext.current
    val verify = check ?: { account: Account -> account.server(context).check() }
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(ServerKind.Emby) }
    var address by remember { mutableStateOf("") }
    var external by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun connect() {
        if (address.isBlank() || user.isBlank()) { error = tr("Bitte Adresse und Benutzername eintragen."); return }
        busy = true; error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val clean = address.trim().trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }
                    val away = external.trim().trimEnd('/').let { if (it.isEmpty() || it.startsWith("http")) it else "https://$it" }
                    // Signing in works through whichever address answers now (at home the WLAN one, away the other).
                    val now = if (away.isNotEmpty() && !Reach.answers(kind, clean, 2500)) away else clean
                    val account = if (kind == ServerKind.Navidrome) Account(java.util.UUID.randomUUID().toString(), kind, clean, user.trim(), password, external = away)
                    else {
                        val (userId, token) = MediaBrowserServer.login(kind, now, user.trim(), password)
                        Account(java.util.UUID.randomUUID().toString(), kind, clean, user.trim(), token, userId = userId, external = away)
                    }
                    account.copy(name = verify(account.copy(address = now)))
                }
            }
            busy = false
            result.onSuccess(onDone).onFailure { error = (it as? ServerError)?.message ?: tr("Keine Verbindung – Adresse prüfen.") }
        }
    }

    Column(Modifier.fillMaxSize().background(ink.grouped).verticalScroll(rememberScrollState()).imePadding()
        .windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onCancel != null) Label(tr("Abbrechen"), 17f, 400, ink.tint, Modifier.clickable(role = Role.Button, onClick = onCancel))
        }
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(Modifier.size(88.dp))
            Label(if (onCancel == null) tr("Willkommen bei LiDio") else tr("Server hinzufügen"), 28f, 700, modifier = Modifier.padding(top = 16.dp))
            Label(tr("Deine Musik von deinem eigenen Server – ohne Abo."), 15f, color = ink.secondary, modifier = Modifier.padding(top = 6.dp, start = 32.dp, end = 32.dp),
                lines = 2, align = TextAlign.Center)
        }
        Group("SERVER") {
            val kinds = ServerKind.entries.filter { it != ServerKind.Local && it != ServerKind.Web }
            Segmented(kinds.map { it.label }, kinds.indexOf(kind), Modifier.padding(12.dp)) { kind = kinds[it] }
        }
        Group(null) {
            Field(tr("Adresse"), address, { address = it }, placeholder = tr("im WLAN, z. B. 192.168.1.20:8096"), keyboard = KeyboardType.Uri)
            Line()
            Field("Unterwegs", external, { external = it }, placeholder = tr("optional, z. B. musik.example.de"), keyboard = KeyboardType.Uri)
            Line()
            Field(tr("Benutzer"), user, { user = it }, placeholder = tr("Name"))
            Line()
            Field(tr("Passwort"), password, { password = it }, placeholder = tr("Passwort"), secret = true)
        }
        Label(tr("Im WLAN nimmt LiDio die schnelle Adresse, unterwegs die zweite – ganz von selbst."), 13f, color = ink.secondary,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp), lines = 2)
        error?.let { Label(it, 15f, 500, Color(0xFFFF3B30), Modifier.padding(start = 32.dp, end = 32.dp, top = 10.dp), lines = 3) }
        Box(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (busy) ink.fill else ink.tint)
            .clickable(enabled = !busy, role = Role.Button, onClick = ::connect).padding(vertical = 15.dp), contentAlignment = Alignment.Center) {
            Label(if (busy) tr("Verbinde …") else tr("Verbinden"), 17f, 600, Color.White)
        }
        onLocal?.let { local ->
            Label(tr("Oder Musik auf diesem Gerät verwenden …"), 17f, 400, ink.tint, Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = local)
                .padding(bottom = 16.dp), align = TextAlign.Center)
        }
        Label(tr("LiDio spricht nur mit diesem Server. {if} verschlüsselt auf diesem Gerät gespeichert. Keine Werbung, keine Statistik, kein Konto bei uns.", "if" to (if (kind == ServerKind.Navidrome) "Das Passwort wird" else "Der Zugangsschlüssel wird")),
            13f, color = ink.secondary, modifier = Modifier.padding(horizontal = 32.dp).widthIn(max = 520.dp), lines = 5)
    }
}

@Composable
private fun Group(caption: String?, content: @Composable () -> Unit) {
    val ink = Ink
    caption?.let { Label(it, 13f, color = ink.secondary, modifier = Modifier.padding(start = 32.dp, top = 24.dp, bottom = 6.dp)) }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = if (caption == null) 20.dp else 0.dp).clip(RoundedCornerShape(10.dp)).background(ink.card)) { content() }
}

@Composable
private fun Line() = Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(Ink.separator))

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String, keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false) {
    val ink = Ink
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(label, 17f, modifier = Modifier.widthIn(min = 96.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Label(placeholder, 17f, color = ink.tertiary)
            BasicTextField(value, onChange, singleLine = true, textStyle = style(17f, color = ink.label), cursorBrush = SolidColor(ink.tint),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
        }
    }
}

/** The app icon drawn in Compose (same drawing as the launcher icon), for the welcome screen. */
@Composable
fun AppIcon(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier.clip(RoundedCornerShape(22))) { drawLiDioIcon() }
}
