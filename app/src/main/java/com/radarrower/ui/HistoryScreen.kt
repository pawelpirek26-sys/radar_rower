package com.radarrower.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radarrower.data.RideRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Historia przejazdów (Pro). Świadomie tylko podsumowania — bez map i wykresów,
 * bo aplikacja ma zostać narzędziem bezpieczeństwa, a nie kolejnym trackerem
 * treningowym konkurującym ze Stravą.
 */
@Composable
fun HistoryScreen(rides: List<RideRecord>, onClear: () -> Unit) {
    val dateFormat = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
    ) {
        Text(
            "Historia przejazdów",
            fontSize = 26.sp,
            color = MaterialTheme.colorScheme.primary,
        )

        if (rides.isEmpty()) {
            Text(
                "Tu pojawią się podsumowania przejazdów, na których radar wykrył " +
                    "jakiekolwiek pojazdy. Przejazd zapisuje się po zatrzymaniu radaru.",
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
            return@Column
        }

        Text(
            "Ostatnie ${rides.size} " + if (rides.size == 1) "przejazd" else "przejazdy",
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(rides) { ride ->
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            dateFormat.format(Date(ride.startedAtMs)) +
                                "  ·  " + formatDuration(ride.durationMs),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "Pojazdy: ${ride.vehicles}",
                            fontSize = 15.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            "Najbliższe minięcie: " +
                                (ride.closestPassM?.let { "$it m" } ?: "—"),
                            fontSize = 15.sp,
                        )
                        Text(
                            "Największe zbliżanie: " +
                                (if (ride.maxClosingKmh > 0) "${ride.maxClosingKmh} km/h" else "—"),
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }

        OutlinedButton(onClick = onClear, modifier = Modifier.padding(top = 8.dp)) {
            Text("Wyczyść historię")
        }
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    return if (minutes < 60) {
        "$minutes min"
    } else {
        "${minutes / 60} h ${minutes % 60} min"
    }
}
