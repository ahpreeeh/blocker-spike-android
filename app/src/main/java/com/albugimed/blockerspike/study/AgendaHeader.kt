package com.albugimed.blockerspike.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** En-tête temporel, volontairement placé sans modifier l'ordre de la file. */
@Composable
internal fun AgendaHeader(state: AgendaState) {
    val content = buildAgendaHeaderPresentation(state)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Agenda", style = MaterialTheme.typography.titleLarge)
            Text(content.freshnessLabel, style = MaterialTheme.typography.bodySmall)
            content.warnings.forEach { warning ->
                Text(warning, color = MaterialTheme.colorScheme.error)
            }

            HorizontalDivider()
            Text("Prochain verrou", style = MaterialTheme.typography.titleMedium)
            val nextLock = content.nextLock
            if (nextLock == null) {
                Text("—")
            } else {
                AgendaRow(nextLock)
            }

            HorizontalDivider()
            Text("Dans les 48 h", style = MaterialTheme.typography.titleMedium)
            if (content.window48h.isEmpty()) {
                Text("—")
            } else {
                content.window48h.forEach { row -> AgendaRow(row) }
            }
        }
    }
}

@Composable
private fun AgendaRow(row: AgendaRowPresentation) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(row.label, style = MaterialTheme.typography.bodyLarge)
        Text(row.kindLabel, style = MaterialTheme.typography.bodySmall)
        Text(row.timeLabel, style = MaterialTheme.typography.bodySmall)
        row.location?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
