package com.tentel.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.tentel.R;
import com.tentel.shared.settings.preferences.tentelWidgetAppSharedPreferences;

@Keep
public class tentelWidgetPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(tentelWidgetPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.tentel_widget_preferences, rootKey);
    }

}

class tentelWidgetPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final tentelWidgetAppSharedPreferences mPreferences;

    private static tentelWidgetPreferencesDataStore mInstance;

    private tentelWidgetPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = tentelWidgetAppSharedPreferences.build(context, true);
    }

    public static synchronized tentelWidgetPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new tentelWidgetPreferencesDataStore(context);
        }
        return mInstance;
    }

}
