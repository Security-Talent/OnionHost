package com.onionhost.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel
) {
    val autoStart by viewModel.autoStartOnBoot.collectAsState()
    val defaultPort by viewModel.defaultPort.collectAsState()
    val rateLimit by viewModel.rateLimitPerMin.collectAsState()

    var portInput by remember(defaultPort) { mutableStateOf(defaultPort.toString()) }
    var rateLimitInput by remember(rateLimit) { mutableStateOf(rateLimit.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto Start on Reboot", fontWeight = FontWeight.Bold)
                        Text(
                            "Automatically resume hosting after device reboots.",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Switch(
                        checked = autoStart,
                        onCheckedChange = { viewModel.toggleAutoStart(it) }
                    )
                }
            }
        }

        Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Default Local Port", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = portInput,
                    onValueChange = {
                        portInput = it
                        it.toIntOrNull()?.let { p -> viewModel.updateDefaultPort(p) }
                    },
                    label = { Text("Port Number") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        if (portInput.toIntOrNull()?.let { it !in 1024..65535 } != false) Text("Use a port from 1024 to 65535.")
                    }
                )
            }
        }

        Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Rate Limiting (Req / min)", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = rateLimitInput,
                    onValueChange = {
                        rateLimitInput = it
                        it.toIntOrNull()?.let { r -> viewModel.updateRateLimit(r) }
                    },
                    label = { Text("Max Requests Per Minute") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        if (rateLimitInput.toIntOrNull()?.let { it !in 1..10_000 } != false) Text("Use a value from 1 to 10000.")
                    }
                )
            }
        }
    }
}
