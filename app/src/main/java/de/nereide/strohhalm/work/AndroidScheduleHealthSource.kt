package de.nereide.strohhalm.work

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import de.nereide.strohhalm.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * Reads WorkManager's view of the periodic work and the battery exemption.
 * The exemption is granted in a system screen and cannot be observed, so it
 * is re-read on [refresh], which screens call on resume.
 */
class AndroidScheduleHealthSource(
    context: Context,
    private val settings: SettingsRepository,
) : ScheduleHealthSource {

    private val appContext = context.applicationContext
    private val exempt = MutableStateFlow(BatteryOptimisation.isExempt(appContext))

    override fun observe(): Flow<ScheduleHealth> = combine(
        WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWorkFlow(SyncScheduler.UNIQUE_WORK_NAME),
        settings.syncInterval,
        exempt,
    ) { infos, interval, isExempt ->
        val live = infos.firstOrNull {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
        }
        ScheduleHealth(
            interval = interval,
            registered = live != null,
            nextRunAt = live?.nextScheduleTimeMillis?.takeIf { it != Long.MAX_VALUE },
            batteryExempt = isExempt,
        )
    }

    override fun refresh() {
        exempt.value = BatteryOptimisation.isExempt(appContext)
    }
}
