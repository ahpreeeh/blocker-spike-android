package com.albugimed.blockerspike.ui.settings

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

internal fun formatTime(millis: Long): String = timeFormat.format(Date(millis))
