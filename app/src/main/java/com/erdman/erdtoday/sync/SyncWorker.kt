package com.erdman.erdtoday.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.erdman.erdtoday.TodayApp
import com.erdman.erdtoday.vikunja.VikunjaApiClient

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult {
        val container = (applicationContext as TodayApp).container
        val credentials = container.credentialsManager.credentials.value ?: return WorkResult.success() // nothing to sync yet
        return VikunjaApiClient(credentials.baseUrl, credentials.apiToken).use { api ->
            val engine = SyncEngine(
                taskDao = container.database.taskDao(),
                tagDao = container.database.tagDao(),
                syncStateDao = container.database.syncStateDao(),
                api = api,
            )
            when (val result = engine.sync()) {
                is SyncResult.Success -> WorkResult.success()
                is SyncResult.Failure -> WorkResult.retry()
            }
        }
    }
}
