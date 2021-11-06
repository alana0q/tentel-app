package com.tentel.app.terminal.io;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.drawerlayout.widget.DrawerLayout;

import com.tentel.app.terminal.tentelTerminalSessionClient;
import com.tentel.app.terminal.tentelTerminalViewClient;
import com.tentel.shared.terminal.io.TerminalExtraKeys;
import com.tentel.view.TerminalView;

public class tentelTerminalExtraKeys extends TerminalExtraKeys {


    tentelTerminalViewClient mtentelTerminalViewClient;
    tentelTerminalSessionClient mtentelTerminalSessionClient;

    public tentelTerminalExtraKeys(@NonNull TerminalView terminalView,
                                   tentelTerminalViewClient tentelTerminalViewClient,
                                   tentelTerminalSessionClient tentelTerminalSessionClient) {
        super(terminalView);
        mtentelTerminalViewClient = tentelTerminalViewClient;
        mtentelTerminalSessionClient = tentelTerminalSessionClient;
    }

    @SuppressLint("RtlHardcoded")
    @Override
    public void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
        if ("KEYBOARD".equals(key)) {
            if(mtentelTerminalViewClient != null)
                mtentelTerminalViewClient.onToggleSoftKeyboardRequest();
        } else if ("DRAWER".equals(key)) {
            DrawerLayout drawerLayout = mtentelTerminalViewClient.getActivity().getDrawer();
            if (drawerLayout.isDrawerOpen(Gravity.LEFT))
                drawerLayout.closeDrawer(Gravity.LEFT);
            else
                drawerLayout.openDrawer(Gravity.LEFT);
        } else if ("PASTE".equals(key)) {
            if(mtentelTerminalSessionClient != null)
                mtentelTerminalSessionClient.onPasteTextFromClipboard(null);
        } else {
            super.onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown);
        }
    }

}
