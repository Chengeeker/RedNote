package com.red.note

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

@Composable
private fun OriginalDialogWindow() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        window?.setWindowAnimations(R.style.OriginalDialogAnimation)
        window?.setDimAmount(.5f)
    }
}

@Composable
internal fun AppDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest) {
        OriginalDialogWindow()
        content()
    }
}

@Composable
internal fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    title: @Composable () -> Unit,
    text: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest,
        confirmButton,
        title = {
            OriginalDialogWindow()
            title()
        },
        text = text,
        dismissButton = dismissButton,
        properties = properties,
    )
}
