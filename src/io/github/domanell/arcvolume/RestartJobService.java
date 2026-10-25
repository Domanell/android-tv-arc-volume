package io.github.domanell.arcvolume;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Periodic persisted job that starts VolumeService when it is not running.
 *
 * <p>Brings the service back after the system kills the process. JobScheduler also restores
 * persisted jobs after a reboot, but whether a vendor auto-start block (TCL TclAppBoot) lets the
 * job start the app when BOOT_COMPLETED was refused is not verified.
 */
public class RestartJobService extends JobService {
    private static final int JOB_ID = 1;
    private static final long PERIOD_MS = 15 * 60 * 1000;

    /** Schedules the job unless it is already pending, which would reset its period. */
    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(JOB_ID) != null) {
            return;
        }
        scheduler.schedule(new JobInfo.Builder(JOB_ID,
                new ComponentName(context, RestartJobService.class))
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        VolumeService.start(this, "job");
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
