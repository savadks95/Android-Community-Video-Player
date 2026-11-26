package app.tgplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.tgplayer.Telegram
import app.tgplayer.data.TelegramLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/** Shown instead of Telegram while a password is set and not yet entered. */
@Composable
fun TelegramLockScreen(onUnlocked: () -> Unit, onExit: () -> Unit, onForgot: () -> Unit) {
    val scope = rememberCoroutineScope()
    val password = rememberTextFieldState()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var wait by remember { mutableLongStateOf(TelegramLock.waitSeconds()) }
    var confirmForgot by remember { mutableStateOf(false) }
    LaunchedEffect(wait) {
        if (wait > 0) {
            delay(1_000)
            wait = TelegramLock.waitSeconds()
        }
    }
    fun submit() {
        if (busy || wait > 0 || password.text.isEmpty()) return
        busy = true
        scope.launch {
            when (val result = TelegramLock.check(password.text.toString())) {
                TelegramLock.Result.Ok -> onUnlocked()
                is TelegramLock.Result.Wrong -> message =
                    if (result.attemptsBeforeWait > 0) "Wrong password. ${result.attemptsBeforeWait} more tries before a short wait."
                    else "Wrong password. One more wrong try adds a 30-second wait."
                is TelegramLock.Result.Wait -> { message = null; wait = result.seconds }
            }
            password.clearText()
            busy = false
        }
    }
    BackHandler(onBack = onExit)

    Box(Modifier.fillMaxSize().background(Cinema.Stage)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            IconTile(Icons.Rounded.Lock, size = 64.dp)
            Spacer(Modifier.height(20.dp))
            Text("Telegram is locked", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text("Enter your TG Player password to open your chats.", style = MaterialTheme.typography.bodyLarge, color = Cinema.TextDim)
            Spacer(Modifier.height(28.dp))
            PasswordField(password, "Password", enabled = !busy && wait == 0L, onDone = ::submit)
            val note = if (wait > 0) "Too many wrong tries. Try again in ${formatDuration(wait.toInt())}." else message
            note?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(18.dp))
            PrimaryButton(if (busy) "Checking…" else "Unlock", onClick = ::submit)
            TextButton(onClick = { confirmForgot = true }, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) {
                Text("Forgot password?", color = Cinema.TextDim)
            }
        }
        IconButton(onClick = onExit, modifier = Modifier.safeDrawingPadding().padding(8.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
        }
    }

    if (confirmForgot) {
        AlertDialog(
            onDismissRequest = { confirmForgot = false },
            title = { Text("Log out of Telegram?") },
            text = {
                Text(
                    "The password can't be recovered. Logging out signs this phone out of Telegram and removes the password; " +
                        "log in again to get back in. Your favourites and watch later lists stay on this phone.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmForgot = false; onForgot() }) { Text("Log out", color = Cinema.Danger) } },
            dismissButton = { TextButton(onClick = { confirmForgot = false }) { Text("Cancel") } },
            containerColor = Cinema.PanelHigh,
        )
    }
}

/** Account, password lock and log out for the Telegram section. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramAccountSheet(onDismiss: () -> Unit, onLogout: () -> Unit) {
    val me by produceState<TdApi.User?>(null) { value = runCatching { Telegram.send(TdApi.GetMe()) }.getOrNull() }
    var locked by remember { mutableStateOf(TelegramLock.isSet) }
    var dialog by remember { mutableStateOf<PasswordMode?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Cinema.Panel) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            val user = me
            val name = user?.let { "${it.firstName} ${it.lastName}".trim() }.orEmpty()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(paletteFor(name.ifEmpty { "?" })), contentAlignment = Alignment.Center) {
                    Text(name.take(1).uppercase().ifEmpty { "?" }, style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(name.ifEmpty { "Telegram" }, style = MaterialTheme.typography.titleLarge)
                    val handle = listOfNotNull(
                        user?.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" },
                        user?.phoneNumber?.takeIf { it.isNotEmpty() }?.let { "+$it" },
                    ).joinToString(" · ")
                    if (handle.isNotEmpty()) Text(handle, style = MaterialTheme.typography.bodyMedium, color = Cinema.TextDim)
                }
            }

            Text("Password lock", Modifier.padding(top = 24.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
            Text(
                if (locked) "On. TG Player asks for this password every time you open Telegram."
                else "Ask for a password every time Telegram is opened in TG Player.",
                style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim,
            )
            Spacer(Modifier.height(6.dp))
            if (locked) {
                SheetAction("Change password", Icons.Rounded.Password) { dialog = PasswordMode.CHANGE }
                SheetAction("Remove password", Icons.Rounded.LockOpen) { dialog = PasswordMode.REMOVE }
            } else {
                SheetAction("Set a password", Icons.Rounded.Lock) { dialog = PasswordMode.SET }
            }

            Spacer(Modifier.height(14.dp))
            SheetAction("Log out of Telegram", Icons.AutoMirrored.Rounded.Logout, tint = Cinema.Danger, onClick = onLogout)
        }
    }

    dialog?.let { mode ->
        PasswordDialog(mode, onDismiss = { dialog = null }, onDone = { dialog = null; locked = TelegramLock.isSet })
    }
}

private enum class PasswordMode(val title: String, val needsCurrent: Boolean, val needsNew: Boolean) {
    SET("Set a password", false, true),
    CHANGE("Change password", true, true),
    REMOVE("Remove password", true, false),
}

@Composable
private fun PasswordDialog(mode: PasswordMode, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val current = rememberTextFieldState()
    val new = rememberTextFieldState()
    val repeat = rememberTextFieldState()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun confirm() {
        if (busy) return
        if (mode.needsNew) {
            val pw = new.text.toString()
            if (pw.length < TelegramLock.MIN_LENGTH) return run { error = "Use at least ${TelegramLock.MIN_LENGTH} characters." }
            if (pw != repeat.text.toString()) return run { error = "The two passwords don't match." }
        }
        busy = true
        scope.launch {
            if (mode.needsCurrent) {
                when (val r = TelegramLock.check(current.text.toString())) {
                    TelegramLock.Result.Ok -> Unit
                    is TelegramLock.Result.Wrong -> { error = "Current password is wrong."; current.clearText(); busy = false; return@launch }
                    is TelegramLock.Result.Wait -> { error = "Too many wrong tries. Try again in ${formatDuration(r.seconds.toInt())}."; busy = false; return@launch }
                }
            }
            if (mode.needsNew) TelegramLock.set(new.text.toString()) else TelegramLock.clear()
            busy = false
            onDone()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(mode.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (mode == PasswordMode.SET) {
                    Text(
                        "You'll need it every time you open Telegram in TG Player. It can't be recovered; " +
                            "if you forget it, log out of Telegram and log in again.",
                        style = MaterialTheme.typography.bodySmall, color = Cinema.TextDim,
                    )
                }
                if (mode.needsCurrent) PasswordField(current, "Current password", enabled = !busy, onDone = if (mode.needsNew) ({}) else ::confirm)
                if (mode.needsNew) {
                    PasswordField(new, "New password", enabled = !busy, onDone = {})
                    PasswordField(repeat, "Repeat new password", enabled = !busy, onDone = ::confirm)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = ::confirm, enabled = !busy) { Text(if (busy) "Checking…" else if (mode == PasswordMode.REMOVE) "Remove" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Cinema.PanelHigh,
    )
}

@Composable
private fun PasswordField(state: TextFieldState, label: String, enabled: Boolean, onDone: () -> Unit) {
    OutlinedSecureTextField(
        state = state,
        label = { Text(label) },
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Cinema.AccentText,
            unfocusedBorderColor = Cinema.Line,
            focusedContainerColor = Cinema.Panel,
            unfocusedContainerColor = Cinema.Panel,
            cursorColor = Cinema.AccentText,
            focusedLabelColor = Cinema.AccentText,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        onKeyboardAction = { onDone() },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SheetAction(label: String, icon: ImageVector, tint: Color = Color.White, onClick: () -> Unit) {
    ListRow(label, null, onClick) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint) }
    }
}
