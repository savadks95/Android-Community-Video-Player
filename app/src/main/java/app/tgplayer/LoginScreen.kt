package app.tgplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import app.tgplayer.ui.Cinema
import app.tgplayer.ui.LogoMark
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale
import org.drinkless.tdlib.TdApi

/** Phone -> code -> 2FA password. TDLib drives the steps through [state]. */
@Composable
fun LoginScreen(state: TdApi.AuthorizationState?, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var changingNumber by remember { mutableStateOf(false) }
    LaunchedEffect(state?.javaClass) { error = null }

    fun submit(request: TdApi.Function<TdApi.Ok>) {
        scope.launch {
            busy = true
            error = null
            try {
                Telegram.send(request)
                changingNumber = false
            } catch (e: TdException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Cinema.AccentFill.copy(alpha = 0.30f), Color.Transparent), radius = 900f))
            .padding(28.dp)
            .imePadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        LogoMark(64.dp)
        Spacer(Modifier.height(20.dp))
        Text("TG Player", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Stream every video and song in your Telegram chats. Log in with your Telegram account to start.\n\n" +
                "TG Player is an unofficial app built on the Telegram API.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(36.dp))
        when {
            state is TdApi.AuthorizationStateWaitPhoneNumber || (changingNumber && state is TdApi.AuthorizationStateWaitCode) ->
                PhoneStep(busy) { submit(TdApi.SetAuthenticationPhoneNumber(it, null)) }

            state is TdApi.AuthorizationStateWaitCode -> {
                Text(codeSentTo(state.codeInfo), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                InputStep("Code", "Log in", KeyboardType.Number, busy) { submit(TdApi.CheckAuthenticationCode(it)) }
                TextButton(onClick = { changingNumber = true }, enabled = !busy) { Text("Use a different number") }
            }

            state is TdApi.AuthorizationStateWaitPassword -> {
                Text("Your account has two-step verification.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                val label = if (state.passwordHint.isNullOrEmpty()) "Password" else "Password (hint: ${state.passwordHint})"
                InputStep(label, "Log in", KeyboardType.Password, busy, secret = true) {
                    submit(TdApi.CheckAuthenticationPassword(it))
                }
            }

            state == null || state is TdApi.AuthorizationStateWaitTdlibParameters ||
                state is TdApi.AuthorizationStateLoggingOut || state is TdApi.AuthorizationStateClosing ||
                state is TdApi.AuthorizationStateClosed -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))

            else -> Text(
                "This sign-in step (${state.javaClass.simpleName.removePrefix("AuthorizationState")}) isn't supported yet. " +
                    "Finish it in the official Telegram app, then open TG Player again.",
            )
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Country dropdown (type to filter by name or code) above the number; starts on the current IP's country. */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneStep(busy: Boolean, onSubmit: (String) -> Unit) {
    var countries by remember { mutableStateOf(emptyList<TdApi.CountryInfo>()) }
    var iso by rememberSaveable { mutableStateOf("") } // ISO 3166-1 alpha-2, e.g. "IN"
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            countries = Telegram.send(TdApi.GetCountries()).countries
                .filter { !it.isHidden && it.callingCodes.isNotEmpty() }
                .sortedBy { it.name }
            if (iso.isEmpty()) iso = Telegram.send(TdApi.GetCountryCode()).text
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // No list (offline, or no client in the debug demo): the number field still takes a full +91… number.
        }
        if (iso.isEmpty()) iso = Locale.getDefault().country
    }
    val selected = countries.firstOrNull { it.countryCode == iso }
    val q = query.trim().removePrefix("+")
    val shown = countries.filter {
        q.isEmpty() || it.name.contains(q, true) || it.englishName.contains(q, true) || it.callingCodes.any { c -> c.startsWith(q) }
    }

    ExposedDropdownMenuBox(open, { open = it && !busy; query = "" }) {
        OutlinedTextField(
            value = if (open) query else selected?.let { "${it.flagEmoji}  ${it.name}  +${it.callingCodes[0]}" }.orEmpty(),
            onValueChange = { query = it },
            label = { Text("Country") },
            placeholder = { Text("Search country or code") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            singleLine = true,
            enabled = !busy,
            shape = MaterialTheme.shapes.medium,
            colors = fieldColors(),
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(open, { open = false }, containerColor = Cinema.PanelHigh) {
            shown.forEach { c ->
                DropdownMenuItem(
                    text = { Text("${c.flagEmoji}  ${c.name}") },
                    trailingIcon = { Text("+${c.callingCodes[0]}", color = Cinema.TextDim) },
                    onClick = { iso = c.countryCode; open = false },
                )
            }
            if (shown.isEmpty()) DropdownMenuItem(text = { Text("No match", color = Cinema.TextDim) }, onClick = {}, enabled = false)
        }
    }
    Spacer(Modifier.height(12.dp))
    InputStep("Phone number", "Send code", KeyboardType.Phone, busy, prefix = selected?.let { "+${it.callingCodes[0]}" }, onSubmit = onSubmit)
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Cinema.AccentText,
    unfocusedBorderColor = Cinema.Line,
    focusedContainerColor = Cinema.Panel,
    unfocusedContainerColor = Cinema.Panel,
    cursorColor = Cinema.AccentText,
    focusedLabelColor = Cinema.AccentText,
)

/** [prefix] (a calling code) is shown in the field and put in front of the input, unless the input already starts with "+". */
@Composable
private fun InputStep(
    label: String,
    action: String,
    keyboardType: KeyboardType,
    busy: Boolean,
    secret: Boolean = false,
    prefix: String? = null,
    onSubmit: (String) -> Unit,
) {
    var text by rememberSaveable(label) { mutableStateOf("") }
    val password = rememberTextFieldState() // not saveable on purpose: a password shouldn't outlive the screen
    val typed = if (secret) password.text.toString() else text.trim() // passwords may legitimately contain spaces
    val value = if (prefix == null || typed.isEmpty() || typed.startsWith("+")) typed else "$prefix $typed"
    val canSubmit = !busy && value.isNotEmpty()
    val colors = fieldColors()
    if (secret) {
        OutlinedSecureTextField(
            state = password,
            label = { Text(label) },
            enabled = !busy,
            shape = MaterialTheme.shapes.medium,
            colors = colors,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = { if (canSubmit) onSubmit(value) },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            prefix = prefix?.let { { Text("$it ") } },
            singleLine = true,
            enabled = !busy,
            shape = MaterialTheme.shapes.medium,
            colors = colors,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSubmit) onSubmit(value) }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(16.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(if (canSubmit || busy) Cinema.Accent else SolidColor(Cinema.PanelHigh))
            .clickable(enabled = canSubmit) { onSubmit(value) }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Cinema.OnAccent, strokeWidth = 2.dp)
        else Text(action, style = MaterialTheme.typography.labelLarge, color = if (canSubmit) Cinema.OnAccent else Cinema.TextDim)
    }
}

private fun codeSentTo(info: TdApi.AuthenticationCodeInfo): String = when (info.type) {
    is TdApi.AuthenticationCodeTypeTelegramMessage -> "Telegram sent the code as a message in your Telegram app."
    is TdApi.AuthenticationCodeTypeSms, is TdApi.AuthenticationCodeTypeSmsWord,
    is TdApi.AuthenticationCodeTypeSmsPhrase -> "We sent the code by SMS to ${info.phoneNumber}."
    is TdApi.AuthenticationCodeTypeCall -> "You'll get a phone call with the code."
    is TdApi.AuthenticationCodeTypeFragment -> "Get the code on fragment.com."
    else -> "Enter the code sent to ${info.phoneNumber}."
}
