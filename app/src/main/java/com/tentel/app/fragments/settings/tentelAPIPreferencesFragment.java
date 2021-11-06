package com.tentel.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.tentel.R;
import com.tentel.shared.settings.preferences.tentelAPIAppSharedPreferences;

@Keep
public class tentelAPIPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(tentelAPIPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.tentel_api_preferences, rootKey);
    }

}

class tentelAPIPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final tentelAPIAppSharedPreferences mPreferences;

    private static tentelAPIPreferencesDataStore mInstance;

    private tentelAPIPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = tentelAPIAppSharedPreferences.build(context, true);
    }

    public static synchronized tentelAPIPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new tentelAPIPreferencesDataStore(context);
        }
        return mInstance;
    }

}
