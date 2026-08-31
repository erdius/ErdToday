package com.erdman.erdtoday.ui.accountsetup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.erdman.erdtoday.di.appContainer
import com.erdman.erdtoday.di.viewModelCreator
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD

/** First-run screen: collect the Fastmail account email + app-specific password, then connect. */
@Composable
fun AccountSetupScreen() {
    val container = appContainer()
    val vm: AccountSetupViewModel = viewModel(
        factory = viewModelCreator { AccountSetupViewModel(container.credentialsManager) },
    )

    val email by vm.email.collectAsState()
    val appPassword by vm.appPassword.collectAsState()
    val canConnect = email.isNotBlank() && appPassword.isNotBlank()

    Column(Modifier.fillMaxSize()) {
        TopAppBarMMD(title = { TextMMD("Connect Fastmail") })

        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            TextMMD("Enter your Fastmail email and an app-specific password to sync to-dos.")
            Spacer(Modifier.height(16.dp))

            TextFieldMMD(
                value = email,
                onValueChange = vm::setEmail,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { TextMMD("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(12.dp))

            TextFieldMMD(
                value = appPassword,
                onValueChange = vm::setAppPassword,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { TextMMD("App password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canConnect) vm.connect() }),
            )
            Spacer(Modifier.height(24.dp))

            ButtonMMD(
                onClick = vm::connect,
                enabled = canConnect,
                modifier = Modifier.fillMaxWidth(),
            ) { TextMMD("Connect") }
        }
    }
}
