package com.tentel.app;

import android.app.Application;

import com.tentel.shared.crash.tentelCrashUtils;
import com.tentel.shared.settings.preferences.tentelAppSharedPreferences;
import com.tentel.shared.logger.Logger;


public class tentelApplication extends Application {
    public void onCreate() {
        super.onCreate();

        // Set crash handler for the app
        tentelCrashUtils.setCrashHandler(this);

        // Set log level for the app
        setLogLevel();
    }

    private void setLogLevel() {
        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        tentelAppSharedPreferences preferences = tentelAppSharedPreferences.build(getApplicationContext());
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
        Logger.logDebug("Starting Application");
    }
}

