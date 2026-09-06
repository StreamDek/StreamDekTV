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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamdek.tv.R
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

    /**
     * Why the last session ended, when it ended by itself.
     *
     * A suspended account is otherwise dropped at this screen with no explanation, starts the
     * pairing flow again, and is refused -- with nothing on screen distinguishing "your account
     * has been stopped" from "something went wrong". Shown as the status line, which is where a
     * viewer is already looking.
     */
    val sessionEndedMessage by repository.sessionEndedMessage.collectAsState()

    LaunchedEffect(sessionEndedMessage) {
        sessionEndedMessage?.let { status = it }
    }

    // Everything below reports its outcome from a coroutine, which is not a composition.
    val authResources = LocalContext.current.resources

    LaunchedEffect(mode) {
        if (mode != AuthMode.TvCode) return@LaunchedEffect
        TvDebugLogger.i("AuthUi", "starting TV code flow")
        busy = true
        // Starting a fresh pairing clears whatever ended the last session.
        repository.clearSessionExpired()
        status = null
        tvSession = runCatching { repository.createTvSession() }
            .onFailure { status = authResources.getString(R.string.auth_could_not_start) }
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
                        status = authResources.getString(R.string.auth_setup_failed)
                    }
                    return@LaunchedEffect
                }
                "authorization_pending" -> {
                    TvDebugLogger.d("AuthUi", "tvSession pending")
                    status = authResources.getString(R.string.auth_waiting_approval)
                }
                "slow_down" -> {
                    TvDebugLogger.w("AuthUi", "tvSession slow_down")
                    status = authResources.getString(R.string.auth_approval_pending)
                }
                "expired_token" -> {
                    TvDebugLogger.w("AuthUi", "tvSession expired")
                    status = authResources.getString(R.string.auth_code_expired)
                    return@LaunchedEffect
                }
                else -> {
                    TvDebugLogger.w("AuthUi", "tvSession failed status=${result.status}")
                    status = authResources.getString(R.string.auth_tv_sign_in_failed)
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
                    Text(stringResource(R.string.action_back))
                }
                Text(
                    text = stringResource(R.string.auth_link_tv),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.auth_link_explainer),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterButton(stringResource(R.string.auth_tv_code), mode == AuthMode.TvCode) { mode = AuthMode.TvCode }
                    FilterButton(stringResource(R.string.action_sign_in), mode == AuthMode.SignIn) { mode = AuthMode.SignIn }
                    FilterButton(stringResource(R.string.auth_sign_up), mode == AuthMode.SignUp) { mode = AuthMode.SignUp }
                }

                when (mode) {
                    AuthMode.TvCode -> TvCodePanel(
                        session = tvSession,
                        busy = busy,
                        status = status,
                        onReload = { mode = AuthMode.SignIn; mode = AuthMode.TvCode },
                    )
                    AuthMode.SignIn -> CredentialPanel(
                        title = stringResource(R.string.auth_direct_sign_in),
                        fields = {
                            TvField(stringResource(R.string.info_email), email) { email = it }
                            TvPasswordField(stringResource(R.string.auth_password), password) { password = it }
                        },
                        busy = busy,
                        status = status,
                        buttonLabel = stringResource(R.string.action_sign_in),
                        onSubmit = {
                        busy = true
                            scope.launch {
                                status = runCatching {
                                    repository.signIn(email.trim(), password)
                                    onSignedIn()
                                    authResources.getString(R.string.auth_signed_in)
                                }.getOrElse { authResources.getString(R.string.auth_sign_in_failed) }
                                busy = false
                            }
                        },
                    )
                    AuthMode.SignUp -> CredentialPanel(
                        title = stringResource(R.string.auth_create_account),
                        fields = {
                            TvField(stringResource(R.string.display_name_label), displayName) { displayName = it }
                            TvField(stringResource(R.string.info_email), email) { email = it }
                            TvPasswordField(stringResource(R.string.auth_password), password) { password = it }
                        },
                        busy = busy,
                        status = status,
                        buttonLabel = stringResource(R.string.auth_create_account),
                        onSubmit = {
                        busy = true
                            scope.launch {
                                status = runCatching {
                                    repository.register(email.trim(), password, displayName.trim())
                                    onSignedIn()
                                    authResources.getString(R.string.auth_account_created)
                                }.getOrElse { authResources.getString(R.string.auth_sign_up_failed) }
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
            text = session?.userCode
                ?: stringResource(if (busy) R.string.auth_loading_code else R.string.auth_no_code),
            style = MaterialTheme.typography.displaySmall,
            color = Color(0xFFF0BA66),
        )
        Text(
            text = session?.verificationUrl ?: stringResource(R.string.auth_approve_on_phone),
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
            Text(stringResource(R.string.auth_reload_code))
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
