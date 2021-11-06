package com.tentel.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.tentel.R;
import com.tentel.shared.settings.preferences.tentelFloatAppSharedPreferences;

@Keep
public class tentelFloatPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(tentelFloatPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.tentel_float_preferences, rootKey);
    }

}

class tentelFloatPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final tentelFloatAppSharedPreferences mPreferences;

    private static tentelFloatPreferencesDataStore mInstance;

    private tentelFloatPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = tentelFloatAppSharedPreferences.build(context, true);
    }

    public static synchronized tentelFloatPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new tentelFloatPreferencesDataStore(context);
        }
        return mInstance;
    }

}
