package com.nvmex.networkhelper.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.util.home.DeveloperModePrefs
import com.nvmex.networkhelper.viewmodel.signal.DeveloperAuthViewModel

@Composable
fun DeveloperAuthDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    authVm: DeveloperAuthViewModel = hiltViewModel()
) {
    if (!show) return

    val context = LocalContext.current
    val authUi by authVm.uiState.collectAsState()
    var devPassword by rememberSaveable { mutableStateOf("") }
    var devPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var devRole by remember { mutableStateOf(DeveloperModePrefs.getRole(context)) }

    LaunchedEffect(authUi.verifiedRole) {
        val role = authUi.verifiedRole ?: return@LaunchedEffect
        DeveloperModePrefs.saveAuthSession(
            context = context,
            role = role,
            authToken = authUi.authToken,
            expiresAtMs = authUi.tokenExpiresAtMs
        )
        devRole = role
        devPassword = ""
        devPasswordVisible = false
        authVm.consumeVerifiedRole()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = {
            authVm.clearError()
            onDismiss()
        },
        title = { Text(stringResource(R.string.developer_auth_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val roleText = when (devRole) {
                    DeveloperModePrefs.Role.SUPER_ADMIN -> stringResource(R.string.developer_auth_role_super_admin)
                    DeveloperModePrefs.Role.DEVELOPER -> stringResource(R.string.developer_auth_role_developer)
                    DeveloperModePrefs.Role.NONE -> stringResource(R.string.developer_auth_role_none)
                }

                Text(text = roleText, style = MaterialTheme.typography.bodyMedium)

                authUi.errorMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                OutlinedTextField(
                    value = devPassword,
                    onValueChange = { devPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.developer_auth_password_label)) },
                    visualTransformation = if (devPasswordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { devPasswordVisible = !devPasswordVisible }) {
                            Icon(
                                imageVector = if (devPasswordVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (devPasswordVisible) {
                                    stringResource(R.string.developer_auth_hide_password)
                                } else {
                                    stringResource(R.string.developer_auth_show_password)
                                }
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !authUi.isVerifying,
                onClick = { authVm.verifyPassword(devPassword) }
            ) {
                Text(if (authUi.isVerifying) stringResource(R.string.developer_auth_verifying) else stringResource(R.string.developer_auth_verify))
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        DeveloperModePrefs.clear(context)
                        devPassword = ""
                        devRole = DeveloperModePrefs.Role.NONE
                        authVm.clearError()
                        onDismiss()
                    }
                ) {
                    Text(stringResource(R.string.action_clear))
                }
                TextButton(
                    onClick = {
                        authVm.clearError()
                        onDismiss()
                    }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}
