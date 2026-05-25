package com.streamdek.tv.nativeapp.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import com.streamdek.tv.nativeapp.data.TvSessionInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AuthMode {
    SignIn,
    SignUp,
    TvCode,
}

@Composable
fun AuthScreen(
    repository: StreamDekRepository,
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(AuthMode.TvCode) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var tvSession by remember { mutableStateOf<TvSessionInfo?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(mode) {
        if (mode != AuthMode.TvCode) return@LaunchedEffect
        TvDebugLogger.i("AuthUi", "starting TV code flow")
        busy = true
        status = null
        tvSession = runCatching { repository.createTvSession() }
            .onFailure { status = it.message ?: "Could not start TV sign-in" }
            .getOrNull()
        TvDebugLogger.i("AuthUi", "tvSession created code=${tvSession?.userCode ?: "none"}")
        busy = false
    }

    LaunchedEffect(tvSession?.deviceCode) {
        val session = tvSession ?: return@LaunchedEffect
        while (true) {
            delay(session.interval.coerceAtLeast(3) * 1000L)
            val result = repository.pollTvSession(session.deviceCode)
            when (result.status) {
                "approved" -> {
                    TvDebugLogger.i("AuthUi", "tvSession approved")
                    runCatching {
                        repository.completeTvSession(result)
                    }.onSuccess {
                        TvDebugLogger.i("AuthUi", "tvSession sign-in complete, navigating back")
                        onSignedIn()
                    }.onFailure {
                        TvDebugLogger.e("AuthUi", "tvSession bootstrap completion failed", it)
                        status = it.message ?: "TV sign-in finished, but account setup failed"
                    }
                    return@LaunchedEffect
                }
                "authorization_pending" -> {
                    TvDebugLogger.d("AuthUi", "tvSession pending")
                    status = "Waiting for approval on your phone"
                }
                "slow_down" -> {
                    TvDebugLogger.w("AuthUi", "tvSession slow_down")
                    status = "Approval pending. Polling more slowly"
                }
                "expired_token" -> {
                    TvDebugLogger.w("AuthUi", "tvSession expired")
                    status = "That code expired. Reload TV sign-in"
                    return@LaunchedEffect
                }
                else -> {
                    TvDebugLogger.w("AuthUi", "tvSession failed status=${result.status}")
                    status = "TV sign-in failed"
                    return@LaunchedEffect
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07090E)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF152238), Color(0xFF07090E)),
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(40.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Button(
                    onClick = onBack,
                    shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
                    modifier = Modifier.fillMaxWidth(0.32f),
                ) {
                    Text("Back")
                }
                Text(
                    text = "Link StreamDek TV",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Use your phone or sign in directly. Once linked, playback progress, addons, and account settings stay in sync.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterButton("TV Code", mode == AuthMode.TvCode) { mode = AuthMode.TvCode }
                    FilterButton("Sign In", mode == AuthMode.SignIn) { mode = AuthMode.SignIn }
                    FilterButton("Sign Up", mode == AuthMode.SignUp) { mode = AuthMode.SignUp }
                }

                when (mode) {
                    AuthMode.TvCode -> TvCodePanel(
                        session = tvSession,
                        busy = busy,
                        status = status,
                        onReload = { mode = AuthMode.SignIn; mode = AuthMode.TvCode },
                    )
                    AuthMode.SignIn -> CredentialPanel(
                        title = "Direct Sign In",
                        fields = {
                            TvField("Email", email) { email = it }
                            TvPasswordField("Password", password) { password = it }
                        },
                        busy = busy,
                        status = status,
                        buttonLabel = "Sign In",
                        onSubmit = {
                        busy = true
                            scope.launch {
                                status = runCatching {
                                    repository.signIn(email.trim(), password)
                                    onSignedIn()
                                    "Signed in"
                                }.getOrElse { it.message ?: "Sign in failed" }
                                busy = false
                            }
                        },
                    )
                    AuthMode.SignUp -> CredentialPanel(
                        title = "Create Account",
                        fields = {
                            TvField("Display Name", displayName) { displayName = it }
                            TvField("Email", email) { email = it }
                            TvPasswordField("Password", password) { password = it }
                        },
                        busy = busy,
                        status = status,
                        buttonLabel = "Create Account",
                        onSubmit = {
                        busy = true
                            scope.launch {
                                status = runCatching {
                                    repository.register(email.trim(), password, displayName.trim())
                                    onSignedIn()
                                    "Account created"
                                }.getOrElse { it.message ?: "Sign up failed" }
                                busy = false
                            }
                        },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(0.76f)
                    .fillMaxSize()
                    .background(Color(0x22000000), RoundedCornerShape(28.dp)),
            ) {
                AsyncImage(
                    model = tvSession?.verificationUriComplete?.let {
                        "https://api.qrserver.com/v1/create-qr-code/?size=720x720&data=$it"
                    },
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(360.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
private fun FilterButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun TvCodePanel(
    session: TvSessionInfo?,
    busy: Boolean,
    status: String?,
    onReload: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = session?.userCode ?: if (busy) "Loading code..." else "No code available",
            style = MaterialTheme.typography.displaySmall,
            color = Color(0xFFF0BA66),
        )
        Text(
            text = session?.verificationUrl ?: "Open StreamDek on your phone and approve this TV.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
        )
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            )
        }
        OutlinedButton(onClick = onReload, enabled = !busy) {
            Text("Reload Code")
        }
    }
}

@Composable
private fun CredentialPanel(
    title: String,
    fields: @Composable () -> Unit,
    busy: Boolean,
    status: String?,
    buttonLabel: String,
    onSubmit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        fields()
        Button(onClick = onSubmit, enabled = !busy) {
            Text(buttonLabel)
        }
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun TvField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { androidx.compose.material3.Text(label) },
        modifier = Modifier.fillMaxWidth(0.72f),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFF12161F),
            unfocusedContainerColor = Color(0xFF12161F),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
        ),
    )
}

@Composable
private fun TvPasswordField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { androidx.compose.material3.Text(label) },
        modifier = Modifier.fillMaxWidth(0.72f),
        visualTransformation = PasswordVisualTransformation(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFF12161F),
            unfocusedContainerColor = Color(0xFF12161F),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
        ),
    )
}
