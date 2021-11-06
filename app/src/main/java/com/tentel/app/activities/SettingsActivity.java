package com.tentel.app.activities;

import android.content.Context;
import android.os.Bundle;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.tentel.R;
import com.tentel.shared.activities.ReportActivity;
import com.tentel.shared.file.FileUtils;
import com.tentel.shared.models.ReportInfo;
import com.tentel.app.models.UserAction;
import com.tentel.shared.interact.ShareUtils;
import com.tentel.shared.packages.PackageUtils;
import com.tentel.shared.settings.preferences.tentelAPIAppSharedPreferences;
import com.tentel.shared.settings.preferences.tentelFloatAppSharedPreferences;
import com.tentel.shared.settings.preferences.tentelTaskerAppSharedPreferences;
import com.tentel.shared.settings.preferences.tentelWidgetAppSharedPreferences;
import com.tentel.shared.tentel.AndroidUtils;
import com.tentel.shared.tentel.tentelConstants;
import com.tentel.shared.tentel.tentelUtils;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new RootPreferencesFragment())
                .commit();
        }
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowHomeEnabled(true);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public static class RootPreferencesFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = getContext();
            if (context == null) return;

            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            configuretentelAPIPreference(context);
            configuretentelFloatPreference(context);
            configuretentelTaskerPreference(context);
            configuretentelWidgetPreference(context);
            configureAboutPreference(context);
            configureDonatePreference(context);
        }

        private void configuretentelAPIPreference(@NonNull Context context) {
            Preference tentelAPIPreference = findPreference("tentel_api");
            if (tentelAPIPreference != null) {
                tentelAPIAppSharedPreferences preferences = tentelAPIAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                tentelAPIPreference.setVisible(preferences != null);
            }
        }

        private void configuretentelFloatPreference(@NonNull Context context) {
            Preference tentelFloatPreference = findPreference("tentel_float");
            if (tentelFloatPreference != null) {
                tentelFloatAppSharedPreferences preferences = tentelFloatAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                tentelFloatPreference.setVisible(preferences != null);
            }
        }

        private void configuretentelTaskerPreference(@NonNull Context context) {
            Preference tentelTaskerPreference = findPreference("tentel_tasker");
            if (tentelTaskerPreference != null) {
                tentelTaskerAppSharedPreferences preferences = tentelTaskerAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                tentelTaskerPreference.setVisible(preferences != null);
            }
        }

        private void configuretentelWidgetPreference(@NonNull Context context) {
            Preference tentelWidgetPreference = findPreference("tentel_widget");
            if (tentelWidgetPreference != null) {
                tentelWidgetAppSharedPreferences preferences = tentelWidgetAppSharedPreferences.build(context, false);
                // If failed to get app preferences, then likely app is not installed, so do not show its preference
                tentelWidgetPreference.setVisible(preferences != null);
            }
        }

        private void configureAboutPreference(@NonNull Context context) {
            Preference aboutPreference = findPreference("about");
            if (aboutPreference != null) {
                aboutPreference.setOnPreferenceClickListener(preference -> {
                    new Thread() {
                        @Override
                        public void run() {
                            String title = "About";

                            StringBuilder aboutString = new StringBuilder();
                            aboutString.append(tentelUtils.getAppInfoMarkdownString(context, false));

                            String tentelPluginAppsInfo =  tentelUtils.gettentelPluginAppsInfoMarkdownString(context);
                            if (tentelPluginAppsInfo != null)
                                aboutString.append("\n\n").append(tentelPluginAppsInfo);

                            aboutString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context));
                            aboutString.append("\n\n").append(tentelUtils.getImportantLinksMarkdownString(context));

                            String userActionName = UserAction.ABOUT.getName();
                            ReportActivity.startReportActivity(context, new ReportInfo(userActionName,
                                tentelConstants.tentel_APP.tentel_SETTINGS_ACTIVITY_NAME, title, null,
                                aboutString.toString(), null, false,
                                userActionName,
                                Environment.getExternalStorageDirectory() + "/" +
                                    FileUtils.sanitizeFileName(tentelConstants.tentel_APP_NAME + "-" + userActionName + ".log", true, true)));
                        }
                    }.start();

                    return true;
                });
            }
        }

        private void configureDonatePreference(@NonNull Context context) {
            Preference donatePreference = findPreference("donate");
            if (donatePreference != null) {
                String signingCertificateSHA256Digest = PackageUtils.getSigningCertificateSHA256DigestForPackage(context);
                if (signingCertificateSHA256Digest != null) {
                    // If APK is a Google Playstore release, then do not show the donation link
                    // since tentel isn't exempted from the playstore policy donation links restriction
                    // Check Fund solicitations: https://pay.google.com/intl/en_in/about/policy/
                    String apkRelease = tentelUtils.getAPKRelease(signingCertificateSHA256Digest);
                    if (apkRelease == null || apkRelease.equals(tentelConstants.APK_RELEASE_GOOGLE_PLAYSTORE_SIGNING_CERTIFICATE_SHA256_DIGEST)) {
                        donatePreference.setVisible(false);
                        return;
                    } else {
                        donatePreference.setVisible(true);
                    }
                }

                donatePreference.setOnPreferenceClickListener(preference -> {
                    ShareUtils.openURL(context, tentelConstants.tentel_DONATE_URL);
                    return true;
                });
            }
        }
    }

}
