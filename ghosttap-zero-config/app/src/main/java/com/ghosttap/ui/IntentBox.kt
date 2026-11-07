package com.ghosttap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * ZERO-CONFIG — the intent box (2026-11-06).
 *
 * One box on every function's main screen AND on the connected-control
 * page: the user types what they are trying to do in plain words
 * ("what I'm tryna do") and the AI makes sense of it and does it.
 * This is INTENT ("do X"), not configuration — it never asks for a
 * host, port, address, or password.
 *
 * The box itself matches nothing and runs nothing: [onIntent] hands
 * the typed words to the app shell, which routes them through
 * [com.ghosttap.core.IntentRouter] against the REAL registered
 * operations and runs the match through the existing OperationRunner.
 * An intent that matches nothing is answered honestly in [answer]
 * instead of running something wrong. Null [onIntent] hides the box
 * (standalone use); the box is never a chat interface — the answer
 * is one plain-words outcome line, not a conversation.
 */
@Composable
fun IntentBox(
    onIntent: ((String) -> Unit)?,
    answer: String = ""
) {
    if (onIntent == null) return
    var text by remember { mutableStateOf("") }
    PanelBox("WHAT ARE YOU TRYING TO DO?") {
        Text(
            "Type it in plain words — Ghost Tap figures out the right thing to do and does it.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("What are you trying to do?") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        TerminalButton("Do it") {
            if (text.isNotBlank()) onIntent(text.trim())
        }
        if (answer.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                answer,
                color = Color(0xFFA9E5C5),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
