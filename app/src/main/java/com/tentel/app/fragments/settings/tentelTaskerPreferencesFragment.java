package com.tentel.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.tentel.R;
import com.tentel.shared.settings.preferences.tentelTaskerAppSharedPreferences;

@Keep
public class tentelTaskerPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(tentelTaskerPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.tentel_tasker_preferences, rootKey);
    }

}

class tentelTaskerPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final tentelTaskerAppSharedPreferences mPreferences;

    private static tentelTaskerPreferencesDataStore mInstance;

    private tentelTaskerPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = tentelTaskerAppSharedPreferences.build(context, true);
    }

    public static synchronized tentelTaskerPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new tentelTaskerPreferencesDataStore(context);
        }
        return mInstance;
    }

}
