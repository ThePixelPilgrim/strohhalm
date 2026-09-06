package de.nereide.strohhalm.ui.common

import android.text.format.DateUtils

/** "5 minutes ago", minute resolution. */
fun relative(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
