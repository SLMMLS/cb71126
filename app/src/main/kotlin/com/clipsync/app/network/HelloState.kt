package com.clipsync.app.network

import kotlin.math.abs

/** What the last hello came to - shown by "Проверить связь". */
data class HelloState(
    val at: Long,
    val ok: Boolean,
    val message: String,
    /** PC clock minus phone clock (ms), known after a successful hello. */
    val clockSkewMs: Long?,
)

/** The PC refuses hellos whose clock differs by more than five minutes (PROTOCOL.md §3b). */
private const val CLOCK_WARNING_MS = 4 * 60 * 1000L

/** A plain-language line about [state] for the diagnostics screen. */
fun describeHello(state: HelloState, now: Long): String {
    val seconds = ((now - state.at) / 1000).coerceAtLeast(0)
    val whenText = if (seconds < 90) "$seconds с назад" else "${seconds / 60} мин назад"
    if (!state.ok) return "«Я в сети» не доставлено ($whenText): ${state.message}"
    val skew = state.clockSkewMs
    val clockNote = if (skew != null && abs(skew) > CLOCK_WARNING_MS) {
        "\nВнимание: часы телефона и ПК расходятся на ${abs(skew) / 60_000} мин. ПК отклоняет такие сообщения, " +
            "если разница больше 5 минут: включите автоматическую дату и время на обоих устройствах."
    } else {
        ""
    }
    return "ПК принял «я в сети» ($whenText)$clockNote"
}
