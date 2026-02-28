package app.seamlessupdate.client;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.os.PersistableBundle;
import android.os.SystemProperties;
import android.util.Log;

import java.util.Objects;

public class PeriodicJob extends JobService {
    private static final String TAG = "PeriodicJob";
    private static final int JOB_ID_PERIODIC = 1;
    private static final int JOB_ID_RETRY = 2;
    private static final long INTERVAL_MILLIS = 6 * 60 * 60 * 1000;
    private static final long INITIAL_RETRY_MILLIS = 30 * 60 * 1000;
    private static final long MAX_RETRY_MILLIS = 6 * 60 * 60 * 1000;
    private static final String PREF_RETRY_COUNT = "retry_count";
    private static final String EXTRA_JOB_CHANNEL = "extra_job_channel";

    static void schedule(final Context context) {
        final String channel = SystemProperties.get("sys.update.channel", Settings.getChannel(context));
        final int networkType = Settings.getNetworkType(context);
        final boolean batteryNotLow = Settings.getBatteryNotLow(context);
        final boolean requiresCharging = Settings.getRequiresCharging(context);
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        final JobInfo jobInfo = scheduler.getPendingJob(JOB_ID_PERIODIC);
        if (jobInfo != null &&
                jobInfo.getNetworkType() == networkType &&
                jobInfo.isRequireBatteryNotLow() == batteryNotLow &&
                jobInfo.isRequireCharging() == requiresCharging &&
                jobInfo.isPersisted() &&
                jobInfo.getIntervalMillis() == INTERVAL_MILLIS &&
                Objects.equals(jobInfo.getExtras().getString(EXTRA_JOB_CHANNEL), channel)) {
            Log.d(TAG, "Periodic job already registered");
            return;
        }
        final PersistableBundle extras = new PersistableBundle();
        extras.putString(EXTRA_JOB_CHANNEL, channel);
        final ComponentName serviceName = new ComponentName(context, PeriodicJob.class);
        final int result = scheduler.schedule(new JobInfo.Builder(JOB_ID_PERIODIC, serviceName)
            .setRequiredNetworkType(networkType)
            .setRequiresBatteryNotLow(batteryNotLow)
            .setRequiresCharging(requiresCharging)
            .setPersisted(true)
            .setPeriodic(INTERVAL_MILLIS)
            .setExtras(extras)
            .build());
        if (result == JobScheduler.RESULT_FAILURE) {
            Log.d(TAG, "Periodic job schedule failed");
        }
    }

    static void scheduleRetry(final Context context) {
        final var preferences = Settings.getPreferences(context);
        final int retryCount = preferences.getInt(PREF_RETRY_COUNT, 0);
        final long delay = Math.min(INITIAL_RETRY_MILLIS * (1L << Math.min(retryCount, 4)), MAX_RETRY_MILLIS);
        preferences.edit().putInt(PREF_RETRY_COUNT, retryCount + 1).apply();
        Log.d(TAG, "scheduling retry #" + (retryCount + 1) + " with " + (delay / 60000) + "min delay");

        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        final ComponentName serviceName = new ComponentName(context, PeriodicJob.class);
        final int result = scheduler.schedule(new JobInfo.Builder(JOB_ID_RETRY, serviceName)
            .setRequiredNetworkType(Settings.getNetworkType(context))
            .setRequiresBatteryNotLow(Settings.getBatteryNotLow(context))
            .setRequiresCharging(Settings.getRequiresCharging(context))
            .setMinimumLatency(delay)
            .build());
        if (result == JobScheduler.RESULT_FAILURE) {
            Log.d(TAG, "Retry job schedule failed");
        }
    }

    static void resetRetryCount(final Context context) {
        Settings.getPreferences(context).edit().remove(PREF_RETRY_COUNT).apply();
    }

    static void cancel(final Context context) {
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancel(JOB_ID_PERIODIC);
        scheduler.cancel(JOB_ID_RETRY);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        Log.d(TAG, "onStartJob id: " + params.getJobId());
        final Network network = params.getNetwork();
        if (network == null) {
            Log.e(TAG, "JobParameters have a null Network");
            return false;
        }
        final Intent intent = new Intent(this, Service.class);
        intent.putExtra(Service.INTENT_EXTRA_NETWORK, network);
        startForegroundService(intent);
        return false;
    }

    @Override
    public boolean onStopJob(final JobParameters params) {
        return false;
    }
}
