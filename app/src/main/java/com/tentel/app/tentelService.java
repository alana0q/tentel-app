package com.tentel.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.ArrayAdapter;

import androidx.annotation.Nullable;

import com.tentel.R;
import com.tentel.app.settings.properties.tentelAppSharedProperties;
import com.tentel.app.terminal.tentelTerminalSessionClient;
import com.tentel.app.utils.PluginUtils;
import com.tentel.shared.data.IntentUtils;
import com.tentel.shared.models.errors.Errno;
import com.tentel.shared.shell.ShellUtils;
import com.tentel.shared.shell.tentelShellEnvironmentClient;
import com.tentel.shared.shell.tentelShellUtils;
import com.tentel.shared.tentel.tentelConstants;
import com.tentel.shared.tentel.tentelConstants.tentel_APP.tentel_ACTIVITY;
import com.tentel.shared.tentel.tentelConstants.tentel_APP.tentel_SERVICE;
import com.tentel.shared.settings.preferences.tentelAppSharedPreferences;
import com.tentel.shared.shell.tentelSession;
import com.tentel.shared.terminal.tentelTerminalSessionClientBase;
import com.tentel.shared.logger.Logger;
import com.tentel.shared.notification.NotificationUtils;
import com.tentel.shared.packages.PermissionUtils;
import com.tentel.shared.data.DataUtils;
import com.tentel.shared.models.ExecutionCommand;
import com.tentel.shared.shell.tentelTask;
import com.tentel.terminal.TerminalEmulator;
import com.tentel.terminal.TerminalSession;
import com.tentel.terminal.TerminalSessionClient;

import java.util.ArrayList;
import java.util.List;

/**
 * A service holding a list of {@link tentelSession} in {@link #mtentelSessions} and background {@link tentelTask}
 * in {@link #mtentelTasks}, showing a foreground notification while running so that it is not terminated.
 * The user interacts with the session through {@link tentelActivity}, but this service may outlive
 * the activity when the user or the system disposes of the activity. In that case the user may
 * restart {@link tentelActivity} later to yet again access the sessions.
 * <p/>
 * In order to keep both terminal sessions and spawned processes (who may outlive the terminal sessions) alive as long
 * as wanted by the user this service is a foreground service, {@link Service#startForeground(int, Notification)}.
 * <p/>
 * Optionally may hold a wake and a wifi lock, in which case that is shown in the notification - see
 * {@link #buildNotification()}.
 */
public final class tentelService extends Service implements tentelTask.tentelTaskClient, tentelSession.tentelSessionClient {

    private static int EXECUTION_ID = 1000;

    /** This service is only bound from inside the same process and never uses IPC. */
    class LocalBinder extends Binder {
        public final tentelService service = tentelService.this;
    }

    private final IBinder mBinder = new LocalBinder();

    private final Handler mHandler = new Handler();

    /**
     * The foreground tentelSessions which this service manages.
     * Note that this list is observed by {@link tentelActivity#mtentelSessionListViewController},
     * so any changes must be made on the UI thread and followed by a call to
     * {@link ArrayAdapter#notifyDataSetChanged()} }.
     */
    final List<tentelSession> mtentelSessions = new ArrayList<>();

    /**
     * The background tentelTasks which this service manages.
     */
    final List<tentelTask> mtentelTasks = new ArrayList<>();

    /**
     * The pending plugin ExecutionCommands that have yet to be processed by this service.
     */
    final List<ExecutionCommand> mPendingPluginExecutionCommands = new ArrayList<>();

    /** The full implementation of the {@link TerminalSessionClient} interface to be used by {@link TerminalSession}
     * that holds activity references for activity related functions.
     * Note that the service may often outlive the activity, so need to clear this reference.
     */
    tentelTerminalSessionClient mtentelTerminalSessionClient;

    /** The basic implementation of the {@link TerminalSessionClient} interface to be used by {@link TerminalSession}
     * that does not hold activity references.
     */
    final tentelTerminalSessionClientBase mtentelTerminalSessionClientBase = new tentelTerminalSessionClientBase();

    /** The wake lock and wifi lock are always acquired and released together. */
    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;

    /** If the user has executed the {@link tentel_SERVICE#ACTION_STOP_SERVICE} intent. */
    boolean mWantsToStop = false;

    public Integer mTerminalTranscriptRows;

    private static final String LOG_TAG = "tentelService";

    @Override
    public void onCreate() {
        Logger.logVerbose(LOG_TAG, "onCreate");
        runStartForeground();
    }

    @SuppressLint("Wakelock")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.logDebug(LOG_TAG, "onStartCommand");

        // Run again in case service is already started and onCreate() is not called
        runStartForeground();

        String action = intent.getAction();

        if (action != null) {
            switch (action) {
                case tentel_SERVICE.ACTION_STOP_SERVICE:
                    Logger.logDebug(LOG_TAG, "ACTION_STOP_SERVICE intent received");
                    actionStopService();
                    break;
                case tentel_SERVICE.ACTION_WAKE_LOCK:
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_LOCK intent received");
                    actionAcquireWakeLock();
                    break;
                case tentel_SERVICE.ACTION_WAKE_UNLOCK:
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_UNLOCK intent received");
                    actionReleaseWakeLock(true);
                    break;
                case tentel_SERVICE.ACTION_SERVICE_EXECUTE:
                    Logger.logDebug(LOG_TAG, "ACTION_SERVICE_EXECUTE intent received");
                    actionServiceExecute(intent);
                    break;
                default:
                    Logger.logError(LOG_TAG, "Invalid action: \"" + action + "\"");
                    break;
            }
        }

        // If this service really do get killed, there is no point restarting it automatically - let the user do on next
        // start of {@link Term):
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Logger.logVerbose(LOG_TAG, "onDestroy");

        tentelShellUtils.cleartentelTMPDIR(true);

        actionReleaseWakeLock(false);
        if (!mWantsToStop)
            killAlltentelExecutionCommands();
        runStopForeground();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Logger.logVerbose(LOG_TAG, "onBind");
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Logger.logVerbose(LOG_TAG, "onUnbind");

        // Since we cannot rely on {@link tentelActivity.onDestroy()} to always complete,
        // we unset clients here as well if it failed, so that we do not leave service and session
        // clients with references to the activity.
        if (mtentelTerminalSessionClient != null)
            unsettentelTerminalSessionClient();
        return false;
    }

    /** Make service run in foreground mode. */
    private void runStartForeground() {
        setupNotificationChannel();
        startForeground(tentelConstants.tentel_APP_NOTIFICATION_ID, buildNotification());
    }

    /** Make service leave foreground mode. */
    private void runStopForeground() {
        stopForeground(true);
    }

    /** Request to stop service. */
    private void requestStopService() {
        Logger.logDebug(LOG_TAG, "Requesting to stop service");
        runStopForeground();
        stopSelf();
    }

    /** Process action to stop service. */
    private void actionStopService() {
        mWantsToStop = true;
        killAlltentelExecutionCommands();
        requestStopService();
    }

    /** Kill all tentelSessions and tentelTasks by sending SIGKILL to their processes.
     *
     * For tentelSessions, all sessions will be killed, whether user manually exited tentel or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will only be done if user manually exited tentel or if the session was started by a plugin
     * which **expects** the result back via a pending intent.
     *
     * For tentelTasks, only tasks that were started by a plugin which **expects** the result
     * back via a pending intent will be killed, whether user manually exited tentel or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will always be done for the tasks that are killed. The remaining processes will keep on
     * running until the tentel app process is killed by android, like by OOM, so we let them run
     * as long as they can.
     *
     * Some plugin execution commands may not have been processed and added to mtentelSessions and
     * mtentelTasks lists before the service is killed, so we maintain a separate
     * mPendingPluginExecutionCommands list for those, so that we can notify the pending intent
     * creators that execution was cancelled.
     *
     * Note that if user didn't manually exit tentel and if onDestroy() was directly called because
     * of unintended shutdown, like android deciding to kill the service, then there will be no
     * guarantee that onDestroy() will be allowed to finish and tentel app process may be killed before
     * it has finished. This means that in those cases some results may not be sent back to their
     * creators for plugin commands but we still try to process whatever results can be processed
     * despite the unreliable behaviour of onDestroy().
     *
     * Note that if don't kill the processes started by plugins which **expect** the result back
     * and notify their creators that they have been killed, then they may get stuck waiting for
     * the results forever like in case of commands started by tentel:Tasker or RUN_COMMAND intent,
     * since once tentelService has been killed, no result will be sent back. They may still get
     * stuck if tentel app process gets killed, so for this case reasonable timeout values should
     * be used, like in Tasker for the tentel:Tasker actions.
     *
     * We make copies of each list since items are removed inside the loop.
     */
    private synchronized void killAlltentelExecutionCommands() {
        boolean processResult;

        Logger.logDebug(LOG_TAG, "Killing tentelSessions=" + mtentelSessions.size() + ", tentelTasks=" + mtentelTasks.size() + ", PendingPluginExecutionCommands=" + mPendingPluginExecutionCommands.size());

        List<tentelSession> tentelSessions = new ArrayList<>(mtentelSessions);
        for (int i = 0; i < tentelSessions.size(); i++) {
            ExecutionCommand executionCommand = tentelSessions.get(i).getExecutionCommand();
            processResult = mWantsToStop || executionCommand.isPluginExecutionCommandWithPendingResult();
            tentelSessions.get(i).killIfExecuting(this, processResult);
        }

        List<tentelTask> tentelTasks = new ArrayList<>(mtentelTasks);
        for (int i = 0; i < tentelTasks.size(); i++) {
            ExecutionCommand executionCommand = tentelTasks.get(i).getExecutionCommand();
            if (executionCommand.isPluginExecutionCommandWithPendingResult())
                tentelTasks.get(i).killIfExecuting(this, true);
        }

        List<ExecutionCommand> pendingPluginExecutionCommands = new ArrayList<>(mPendingPluginExecutionCommands);
        for (int i = 0; i < pendingPluginExecutionCommands.size(); i++) {
            ExecutionCommand executionCommand = pendingPluginExecutionCommands.get(i);
            if (!executionCommand.shouldNotProcessResults() && executionCommand.isPluginExecutionCommandWithPendingResult()) {
                if (executionCommand.setStateFailed(Errno.ERRNO_CANCELLED.getCode(), this.getString(com.tentel.shared.R.string.error_execution_cancelled))) {
                    PluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand);
                }
            }
        }
    }



    /** Process action to acquire Power and Wi-Fi WakeLocks. */
    @SuppressLint({"WakelockTimeout", "BatteryLife"})
    private void actionAcquireWakeLock() {
        if (mWakeLock != null) {
            Logger.logDebug(LOG_TAG, "Ignoring acquiring WakeLocks since they are already held");
            return;
        }

        Logger.logDebug(LOG_TAG, "Acquiring WakeLocks");

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tentelConstants.tentel_APP_NAME.toLowerCase() + ":service-wakelock");
        mWakeLock.acquire();

        // http://tools.android.com/tech-docs/lint-in-studio-2-3#TOC-WifiManager-Leak
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, tentelConstants.tentel_APP_NAME.toLowerCase());
        mWifiLock.acquire();

        String packageName = getPackageName();
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Intent whitelist = new Intent();
            whitelist.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            whitelist.setData(Uri.parse("package:" + packageName));
            whitelist.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            try {
                startActivity(whitelist);
            } catch (ActivityNotFoundException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to call ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", e);
            }
        }

        updateNotification();

        Logger.logDebug(LOG_TAG, "WakeLocks acquired successfully");

    }

    /** Process action to release Power and Wi-Fi WakeLocks. */
    private void actionReleaseWakeLock(boolean updateNotification) {
        if (mWakeLock == null && mWifiLock == null) {
            Logger.logDebug(LOG_TAG, "Ignoring releasing WakeLocks since none are already held");
            return;
        }

        Logger.logDebug(LOG_TAG, "Releasing WakeLocks");

        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }

        if (mWifiLock != null) {
            mWifiLock.release();
            mWifiLock = null;
        }

        if (updateNotification)
            updateNotification();

        Logger.logDebug(LOG_TAG, "WakeLocks released successfully");
    }

    /** Process {@link tentel_SERVICE#ACTION_SERVICE_EXECUTE} intent to execute a shell command in
     * a foreground tentelSession or in a background tentelTask. */
    private void actionServiceExecute(Intent intent) {
        if (intent == null) {
            Logger.logError(LOG_TAG, "Ignoring null intent to actionServiceExecute");
            return;
        }

        ExecutionCommand executionCommand = new ExecutionCommand(getNextExecutionId());

        executionCommand.executableUri = intent.getData();
        executionCommand.inBackground = intent.getBooleanExtra(tentel_SERVICE.EXTRA_BACKGROUND, false);

        if (executionCommand.executableUri != null) {
            executionCommand.executable = executionCommand.executableUri.getPath();
            executionCommand.arguments = IntentUtils.getStringArrayExtraIfSet(intent, tentel_SERVICE.EXTRA_ARGUMENTS, null);
            if (executionCommand.inBackground)
                executionCommand.stdin = IntentUtils.getStringExtraIfSet(intent, tentel_SERVICE.EXTRA_STDIN, null);
                executionCommand.backgroundCustomLogLevel = IntentUtils.getIntegerExtraIfSet(intent, tentel_SERVICE.EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL, null);
        }

        executionCommand.workingDirectory = IntentUtils.getStringExtraIfSet(intent, tentel_SERVICE.EXTRA_WORKDIR, null);
        executionCommand.isFailsafe = intent.getBooleanExtra(tentel_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
        executionCommand.sessionAction = intent.getStringExtra(tentel_SERVICE.EXTRA_SESSION_ACTION);
        executionCommand.commandLabel = IntentUtils.getStringExtraIfSet(intent, tentel_SERVICE.EXTRA_COMMAND_LABEL, "Execution Intent Command");
        executionCommand.commandDescription = IntentUtils.getStringExtraIfSet(intent, tentel_SERVICE.EXTRA_COMMAND_DESCRIPTION, null);
        executionCommand.commandHelp = IntentUtils.getStringExtraIfSet(intent, tentel_SERVICE.EXTRA_COMMAND_HELP, null);
        executionCommand.pluginAPIHelp = IntentUtils.getStringExtraIfSet(intent, tentel_SERVICE.EXTRA_PLUGIN_API_HELP, null);
        executionCommand.isPluginExecutionCommand = true;
        executionCommand.resultConfig.resultPendingIntent = intent.getParcelableExtra(tentel_SERVICE.EXTRA_PENDING_INTENT);
        executionCommand.resultConfig.resultDirectoryPath = IntentUtils.getStringExtraIfSet(intent, tentel_SERVICE.EXTRA_RESULT_DIRECTORY, null);
        if (executionCommand.resultConfig.resultDirectoryPath != null) {
            executionCommand.resultConfig.resultSingleFile = intent.getBooleanExtra(tentel_SERVICE.EXTRA_RESULT_SINGLE_FILE, false);
            executionCommand.resultConfig.resultFileBasename = IntentUtils.getStringExtraIfSet(intent, tentel_SERVICE.EXTRA_RESULT_FILE_BASENAME, null);
            executionCommand.resultConfig.resultFileOutputFormat = IntentUtils.getStringExtraIfSet(intent, tentel_SERVICE.EXTRA_RESULT_FILE_OUTPUT_FORMAT, null);
            executionCommand.resultConfig.resultFileErrorFormat = IntentUtils.getStringExtraIfSet(intent, tentel_SERVICE.EXTRA_RESULT_FILE_ERROR_FORMAT, null);
            executionCommand.resultConfig.resultFilesSuffix = IntentUtils.getStringExtraIfSet(intent, tentel_SERVICE.EXTRA_RESULT_FILES_SUFFIX, null);
        }

        // Add the execution command to pending plugin execution commands list
        mPendingPluginExecutionCommands.add(executionCommand);

        if (executionCommand.inBackground) {
            executetentelTaskCommand(executionCommand);
        } else {
            executetentelSessionCommand(executionCommand);
        }
    }





    /** Execute a shell command in background {@link tentelTask}. */
    private void executetentelTaskCommand(ExecutionCommand executionCommand) {
        if (executionCommand == null) return;

        Logger.logDebug(LOG_TAG, "Executing background \"" + executionCommand.getCommandIdAndLabelLogString() + "\" tentelTask command");

        tentelTask newtentelTask = createtentelTask(executionCommand);
    }

    /** Create a {@link tentelTask}. */
    @Nullable
    public tentelTask createtentelTask(String executablePath, String[] arguments, String stdin, String workingDirectory) {
        return createtentelTask(new ExecutionCommand(getNextExecutionId(), executablePath, arguments, stdin, workingDirectory, true, false));
    }

    /** Create a {@link tentelTask}. */
    @Nullable
    public synchronized tentelTask createtentelTask(ExecutionCommand executionCommand) {
        if (executionCommand == null) return null;

        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.getCommandIdAndLabelLogString() + "\" tentelTask");

        if (!executionCommand.inBackground) {
            Logger.logDebug(LOG_TAG, "Ignoring a foreground execution command passed to createtentelTask()");
            return null;
        }

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE)
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString());

        tentelTask newtentelTask = tentelTask.execute(this, executionCommand, this, new tentelShellEnvironmentClient(), false);
        if (newtentelTask == null) {
            Logger.logError(LOG_TAG, "Failed to execute new tentelTask command for:\n" + executionCommand.getCommandIdAndLabelLogString());
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand)
                PluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            else
                Logger.logErrorExtended(LOG_TAG, executionCommand.toString());
            return null;
        }

        mtentelTasks.add(newtentelTask);

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand)
            mPendingPluginExecutionCommands.remove(executionCommand);

        updateNotification();

        return newtentelTask;
    }

    /** Callback received when a {@link tentelTask} finishes. */
    @Override
    public void ontentelTaskExited(final tentelTask tentelTask) {
        mHandler.post(() -> {
            if (tentelTask != null) {
                ExecutionCommand executionCommand = tentelTask.getExecutionCommand();

                Logger.logVerbose(LOG_TAG, "The ontentelTaskExited() callback called for \"" + executionCommand.getCommandIdAndLabelLogString() + "\" tentelTask command");

                // If the execution command was started for a plugin, then process the results
                if (executionCommand != null && executionCommand.isPluginExecutionCommand)
                    PluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand);

                mtentelTasks.remove(tentelTask);
            }

            updateNotification();
        });
    }





    /** Execute a shell command in a foreground {@link tentelSession}. */
    private void executetentelSessionCommand(ExecutionCommand executionCommand) {
        if (executionCommand == null) return;

        Logger.logDebug(LOG_TAG, "Executing foreground \"" + executionCommand.getCommandIdAndLabelLogString() + "\" tentelSession command");

        String sessionName = null;

        // Transform executable path to session name, e.g. "/bin/do-something.sh" => "do something.sh".
        if (executionCommand.executable != null) {
            sessionName = ShellUtils.getExecutableBasename(executionCommand.executable).replace('-', ' ');
        }

        tentelSession newtentelSession = createtentelSession(executionCommand, sessionName);
        if (newtentelSession == null) return;

        handleSessionAction(DataUtils.getIntFromString(executionCommand.sessionAction,
            tentel_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY),
            newtentelSession.getTerminalSession());
    }

    /**
     * Create a {@link tentelSession}.
     * Currently called by {@link tentelTerminalSessionClient#addNewSession(boolean, String)} to add a new {@link tentelSession}.
     */
    @Nullable
    public tentelSession createtentelSession(String executablePath, String[] arguments, String stdin, String workingDirectory, boolean isFailSafe, String sessionName) {
        return createtentelSession(new ExecutionCommand(getNextExecutionId(), executablePath, arguments, stdin, workingDirectory, false, isFailSafe), sessionName);
    }

    /** Create a {@link tentelSession}. */
    @Nullable
    public synchronized tentelSession createtentelSession(ExecutionCommand executionCommand, String sessionName) {
        if (executionCommand == null) return null;

        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.getCommandIdAndLabelLogString() + "\" tentelSession");

        if (executionCommand.inBackground) {
            Logger.logDebug(LOG_TAG, "Ignoring a background execution command passed to createtentelSession()");
            return null;
        }

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE)
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString());

        // If the execution command was started for a plugin, only then will the stdout be set
        // Otherwise if command was manually started by the user like by adding a new terminal session,
        // then no need to set stdout
        executionCommand.terminalTranscriptRows = getTerminalTranscriptRows();
        tentelSession newtentelSession = tentelSession.execute(this, executionCommand, gettentelTerminalSessionClient(), this, new tentelShellEnvironmentClient(), sessionName, executionCommand.isPluginExecutionCommand);
        if (newtentelSession == null) {
            Logger.logError(LOG_TAG, "Failed to execute new tentelSession command for:\n" + executionCommand.getCommandIdAndLabelLogString());
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand)
                PluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            else
                Logger.logErrorExtended(LOG_TAG, executionCommand.toString());
            return null;
        }

        mtentelSessions.add(newtentelSession);

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand)
            mPendingPluginExecutionCommands.remove(executionCommand);

        // Notify {@link tentelSessionsListViewController} that sessions list has been updated if
        // activity in is foreground
        if (mtentelTerminalSessionClient != null)
            mtentelTerminalSessionClient.tentelSessionListNotifyUpdated();

        updateNotification();
        tentelActivity.updatetentelActivityStyling(this);

        return newtentelSession;
    }

    /** Remove a tentelSession. */
    public synchronized int removetentelSession(TerminalSession sessionToRemove) {
        int index = getIndexOfSession(sessionToRemove);

        if (index >= 0)
            mtentelSessions.get(index).finish();

        return index;
    }

    /** Callback received when a {@link tentelSession} finishes. */
    @Override
    public void ontentelSessionExited(final tentelSession tentelSession) {
        if (tentelSession != null) {
            ExecutionCommand executionCommand = tentelSession.getExecutionCommand();

            Logger.logVerbose(LOG_TAG, "The ontentelSessionExited() callback called for \"" + executionCommand.getCommandIdAndLabelLogString() + "\" tentelSession command");

            // If the execution command was started for a plugin, then process the results
            if (executionCommand != null && executionCommand.isPluginExecutionCommand)
                PluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand);

            mtentelSessions.remove(tentelSession);

            // Notify {@link tentelSessionsListViewController} that sessions list has been updated if
            // activity in is foreground
            if (mtentelTerminalSessionClient != null)
                mtentelTerminalSessionClient.tentelSessionListNotifyUpdated();
        }

        updateNotification();
    }

    /** Get the terminal transcript rows to be used for new {@link tentelSession}. */
    public Integer getTerminalTranscriptRows() {
        if (mTerminalTranscriptRows == null)
            setTerminalTranscriptRows();
        return mTerminalTranscriptRows;
    }

    public void setTerminalTranscriptRows() {
        // tentelService only uses this tentel property currently, so no need to load them all into
        // an internal values map like tentelActivity does
        mTerminalTranscriptRows = tentelAppSharedProperties.getTerminalTranscriptRows(this);
    }





    /** Process session action for new session. */
    private void handleSessionAction(int sessionAction, TerminalSession newTerminalSession) {
        Logger.logDebug(LOG_TAG, "Processing sessionAction \"" + sessionAction + "\" for session \"" + newTerminalSession.mSessionName + "\"");

        switch (sessionAction) {
            case tentel_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY:
                setCurrentStoredTerminalSession(newTerminalSession);
                if (mtentelTerminalSessionClient != null)
                    mtentelTerminalSessionClient.setCurrentSession(newTerminalSession);
                starttentelActivity();
                break;
            case tentel_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_OPEN_ACTIVITY:
                if (gettentelSessionsSize() == 1)
                    setCurrentStoredTerminalSession(newTerminalSession);
                starttentelActivity();
                break;
            case tentel_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY:
                setCurrentStoredTerminalSession(newTerminalSession);
                if (mtentelTerminalSessionClient != null)
                    mtentelTerminalSessionClient.setCurrentSession(newTerminalSession);
                break;
            case tentel_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_DONT_OPEN_ACTIVITY:
                if (gettentelSessionsSize() == 1)
                    setCurrentStoredTerminalSession(newTerminalSession);
                break;
            default:
                Logger.logError(LOG_TAG, "Invalid sessionAction: \"" + sessionAction + "\". Force using default sessionAction.");
                handleSessionAction(tentel_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY, newTerminalSession);
                break;
        }
    }

    /** Launch the {@link }tentelActivity} to bring it to foreground. */
    private void starttentelActivity() {
        // For android >= 10, apps require Display over other apps permission to start foreground activities
        // from background (services). If it is not granted, then tentelSessions that are started will
        // show in tentel notification but will not run until user manually clicks the notification.
        if (PermissionUtils.validateDisplayOverOtherAppsPermissionForPostAndroid10(this, true)) {
            tentelActivity.starttentelActivity(this);
        } else {
            tentelAppSharedPreferences preferences = tentelAppSharedPreferences.build(this);
            if (preferences == null) return;
            if (preferences.arePluginErrorNotificationsEnabled())
                Logger.showToast(this, this.getString(R.string.error_display_over_other_apps_permission_not_granted), true);
        }
    }





    /** If {@link tentelActivity} has not bound to the {@link tentelService} yet or is destroyed, then
     * interface functions requiring the activity should not be available to the terminal sessions,
     * so we just return the {@link #mtentelTerminalSessionClientBase}. Once {@link tentelActivity} bind
     * callback is received, it should call {@link #settentelTerminalSessionClient} to set the
     * {@link tentelService#mtentelTerminalSessionClient} so that further terminal sessions are directly
     * passed the {@link tentelTerminalSessionClient} object which fully implements the
     * {@link TerminalSessionClient} interface.
     *
     * @return Returns the {@link tentelTerminalSessionClient} if {@link tentelActivity} has bound with
     * {@link tentelService}, otherwise {@link tentelTerminalSessionClientBase}.
     */
    public synchronized tentelTerminalSessionClientBase gettentelTerminalSessionClient() {
        if (mtentelTerminalSessionClient != null)
            return mtentelTerminalSessionClient;
        else
            return mtentelTerminalSessionClientBase;
    }

    /** This should be called when {@link tentelActivity#onServiceConnected} is called to set the
     * {@link tentelService#mtentelTerminalSessionClient} variable and update the {@link TerminalSession}
     * and {@link TerminalEmulator} clients in case they were passed {@link tentelTerminalSessionClientBase}
     * earlier.
     *
     * @param tentelTerminalSessionClient The {@link tentelTerminalSessionClient} object that fully
     * implements the {@link TerminalSessionClient} interface.
     */
    public synchronized void settentelTerminalSessionClient(tentelTerminalSessionClient tentelTerminalSessionClient) {
        mtentelTerminalSessionClient = tentelTerminalSessionClient;

        for (int i = 0; i < mtentelSessions.size(); i++)
            mtentelSessions.get(i).getTerminalSession().updateTerminalSessionClient(mtentelTerminalSessionClient);
    }

    /** This should be called when {@link tentelActivity} has been destroyed and in {@link #onUnbind(Intent)}
     * so that the {@link tentelService} and {@link TerminalSession} and {@link TerminalEmulator}
     * clients do not hold an activity references.
     */
    public synchronized void unsettentelTerminalSessionClient() {
        for (int i = 0; i < mtentelSessions.size(); i++)
            mtentelSessions.get(i).getTerminalSession().updateTerminalSessionClient(mtentelTerminalSessionClientBase);

        mtentelTerminalSessionClient = null;
    }





    private Notification buildNotification() {
        Resources res = getResources();

        // Set pending intent to be launched when notification is clicked
        Intent notificationIntent = tentelActivity.newInstance(this);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);


        // Set notification text
        int sessionCount = gettentelSessionsSize();
        int taskCount = mtentelTasks.size();
        String notificationText = sessionCount + " session" + (sessionCount == 1 ? "" : "s");
        if (taskCount > 0) {
            notificationText += ", " + taskCount + " task" + (taskCount == 1 ? "" : "s");
        }

        final boolean wakeLockHeld = mWakeLock != null;
        if (wakeLockHeld) notificationText += " (wake lock held)";


        // Set notification priority
        // If holding a wake or wifi lock consider the notification of high priority since it's using power,
        // otherwise use a low priority
        int priority = (wakeLockHeld) ? Notification.PRIORITY_HIGH : Notification.PRIORITY_LOW;


        // Build the notification
        Notification.Builder builder =  NotificationUtils.geNotificationBuilder(this,
            tentelConstants.tentel_APP_NOTIFICATION_CHANNEL_ID, priority,
            tentelConstants.tentel_APP_NAME, notificationText, null,
            contentIntent, null, NotificationUtils.NOTIFICATION_MODE_SILENT);
        if (builder == null)  return null;

        // No need to show a timestamp:
        builder.setShowWhen(false);

        // Set notification icon
        builder.setSmallIcon(R.drawable.ic_service_notification);

        // Set background color for small notification icon
        builder.setColor(0xFF607D8B);

        // tentelSessions are always ongoing
        builder.setOngoing(true);


        // Set Exit button action
        Intent exitIntent = new Intent(this, tentelService.class).setAction(tentel_SERVICE.ACTION_STOP_SERVICE);
        builder.addAction(android.R.drawable.ic_delete, res.getString(R.string.notification_action_exit), PendingIntent.getService(this, 0, exitIntent, 0));


        // Set Wakelock button actions
        String newWakeAction = wakeLockHeld ? tentel_SERVICE.ACTION_WAKE_UNLOCK : tentel_SERVICE.ACTION_WAKE_LOCK;
        Intent toggleWakeLockIntent = new Intent(this, tentelService.class).setAction(newWakeAction);
        String actionTitle = res.getString(wakeLockHeld ? R.string.notification_action_wake_unlock : R.string.notification_action_wake_lock);
        int actionIcon = wakeLockHeld ? android.R.drawable.ic_lock_idle_lock : android.R.drawable.ic_lock_lock;
        builder.addAction(actionIcon, actionTitle, PendingIntent.getService(this, 0, toggleWakeLockIntent, 0));


        return builder.build();
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationUtils.setupNotificationChannel(this, tentelConstants.tentel_APP_NOTIFICATION_CHANNEL_ID,
            tentelConstants.tentel_APP_NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
    }

    /** Update the shown foreground service notification after making any changes that affect it. */
    private synchronized void updateNotification() {
        if (mWakeLock == null && mtentelSessions.isEmpty() && mtentelTasks.isEmpty()) {
            // Exit if we are updating after the user disabled all locks with no sessions or tasks running.
            requestStopService();
        } else {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(tentelConstants.tentel_APP_NOTIFICATION_ID, buildNotification());
        }
    }





    private void setCurrentStoredTerminalSession(TerminalSession session) {
        if (session == null) return;
        // Make the newly created session the current one to be displayed
        tentelAppSharedPreferences preferences = tentelAppSharedPreferences.build(this);
        if (preferences == null) return;
        preferences.setCurrentSession(session.mHandle);
    }

    public synchronized boolean istentelSessionsEmpty() {
        return mtentelSessions.isEmpty();
    }

    public synchronized int gettentelSessionsSize() {
        return mtentelSessions.size();
    }

    public synchronized List<tentelSession> gettentelSessions() {
        return mtentelSessions;
    }

    @Nullable
    public synchronized tentelSession gettentelSession(int index) {
        if (index >= 0 && index < mtentelSessions.size())
            return mtentelSessions.get(index);
        else
            return null;
    }

    public synchronized tentelSession getLasttentelSession() {
        return mtentelSessions.isEmpty() ? null : mtentelSessions.get(mtentelSessions.size() - 1);
    }

    public synchronized int getIndexOfSession(TerminalSession terminalSession) {
        for (int i = 0; i < mtentelSessions.size(); i++) {
            if (mtentelSessions.get(i).getTerminalSession().equals(terminalSession))
                return i;
        }
        return -1;
    }

    public synchronized TerminalSession getTerminalSessionForHandle(String sessionHandle) {
        TerminalSession terminalSession;
        for (int i = 0, len = mtentelSessions.size(); i < len; i++) {
            terminalSession = mtentelSessions.get(i).getTerminalSession();
            if (terminalSession.mHandle.equals(sessionHandle))
                return terminalSession;
        }
        return null;
    }



    public static synchronized int getNextExecutionId() {
        return EXECUTION_ID++;
    }

    public boolean wantsToStop() {
        return mWantsToStop;
    }

}
