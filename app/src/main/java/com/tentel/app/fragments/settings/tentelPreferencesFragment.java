package com.tentel.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.tentel.R;
import com.tentel.shared.settings.preferences.tentelAppSharedPreferences;

@Keep
public class tentelPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(tentelPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.tentel_preferences, rootKey);
    }

}

class tentelPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final tentelAppSharedPreferences mPreferences;

    private static tentelPreferencesDataStore mInstance;

    private tentelPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = tentelAppSharedPreferences.build(context, true);
    }

    public static synchronized tentelPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new tentelPreferencesDataStore(context);
        }
        return mInstance;
    }

}
