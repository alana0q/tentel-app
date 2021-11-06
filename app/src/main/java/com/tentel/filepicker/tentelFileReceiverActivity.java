package com.tentel.filepicker;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Patterns;

import com.tentel.R;
import com.tentel.shared.data.DataUtils;
import com.tentel.shared.data.IntentUtils;
import com.tentel.shared.interact.MessageDialogUtils;
import com.tentel.shared.interact.TextInputDialogUtils;
import com.tentel.shared.tentel.tentelConstants;
import com.tentel.shared.tentel.tentelConstants.tentel_APP.tentel_SERVICE;
import com.tentel.app.tentelService;
import com.tentel.shared.logger.Logger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public class tentelFileReceiverActivity extends Activity {

    static final String tentel_RECEIVEDIR = tentelConstants.tentel_FILES_DIR_PATH + "/home/downloads";
    static final String EDITOR_PROGRAM = tentelConstants.tentel_HOME_DIR_PATH + "/bin/tentel-file-editor";
    static final String URL_OPENER_PROGRAM = tentelConstants.tentel_HOME_DIR_PATH + "/bin/tentel-url-opener";

    /**
     * If the activity should be finished when the name input dialog is dismissed. This is disabled
     * before showing an error dialog, since the act of showing the error dialog will cause the
     * name input dialog to be implicitly dismissed, and we do not want to finish the activity directly
     * when showing the error dialog.
     */
    boolean mFinishOnDismissNameDialog = true;

    private static final String API_TAG = tentelConstants.tentel_APP_NAME + "FileReceiver";

    private static final String LOG_TAG = "tentelFileReceiverActivity";

    static boolean isSharedTextAnUrl(String sharedText) {
        return Patterns.WEB_URL.matcher(sharedText).matches()
            || Pattern.matches("magnet:\\?xt=urn:btih:.*?", sharedText);
    }

    @Override
    protected void onResume() {
        super.onResume();

        final Intent intent = getIntent();
        final String action = intent.getAction();
        final String type = intent.getType();
        final String scheme = intent.getScheme();

        Logger.logVerbose(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent));

        final String sharedTitle = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_TITLE, null);

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            final String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            final Uri sharedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);

            if (sharedUri != null) {
                handleContentUri(sharedUri, sharedTitle);
            } else if (sharedText != null) {
                if (isSharedTextAnUrl(sharedText)) {
                    handleUrlAndFinish(sharedText);
                } else {
                    String subject = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_SUBJECT, null);
                    if (subject == null) subject = sharedTitle;
                    if (subject != null) subject += ".txt";
                    promptNameAndSave(new ByteArrayInputStream(sharedText.getBytes(StandardCharsets.UTF_8)), subject);
                }
            } else {
                showErrorDialogAndQuit("Send action without content - nothing to save.");
            }
        } else {
            Uri dataUri = intent.getData();

            if (dataUri == null) {
                showErrorDialogAndQuit("Data uri not passed.");
                return;
            }

            if ("content".equals(scheme)) {
                handleContentUri(dataUri, sharedTitle);
            } else if ("file".equals(scheme)) {
                // When e.g. clicking on a downloaded apk:
                String path = dataUri.getPath();
                if (DataUtils.isNullOrEmpty(path)) {
                    showErrorDialogAndQuit("File path from data uri is null, empty or invalid.");
                    return;
                }

                File file = new File(path);
                try {
                    FileInputStream in = new FileInputStream(file);
                    promptNameAndSave(in, file.getName());
                } catch (FileNotFoundException e) {
                    showErrorDialogAndQuit("Cannot open file: " + e.getMessage() + ".");
                }
            } else {
                showErrorDialogAndQuit("Unable to receive any file or URL.");
            }
        }
    }

    void showErrorDialogAndQuit(String message) {
        mFinishOnDismissNameDialog = false;
        MessageDialogUtils.showMessage(this,
            API_TAG, message,
            null, (dialog, which) -> finish(),
            null, null,
            dialog -> finish());
    }

    void handleContentUri(final Uri uri, String subjectFromIntent) {
        try {
            String attachmentFileName = null;

            String[] projection = new String[]{OpenableColumns.DISPLAY_NAME};
            try (Cursor c = getContentResolver().query(uri, projection, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    final int fileNameColumnId = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (fileNameColumnId >= 0) attachmentFileName = c.getString(fileNameColumnId);
                }
            }

            if (attachmentFileName == null) attachmentFileName = subjectFromIntent;

            InputStream in = getContentResolver().openInputStream(uri);
            promptNameAndSave(in, attachmentFileName);
        } catch (Exception e) {
            showErrorDialogAndQuit("Unable to handle shared content:\n\n" + e.getMessage());
            Logger.logStackTraceWithMessage(LOG_TAG, "handleContentUri(uri=" + uri + ") failed", e);
        }
    }

    void promptNameAndSave(final InputStream in, final String attachmentFileName) {
        TextInputDialogUtils.textInput(this, R.string.title_file_received, attachmentFileName, R.string.action_file_received_edit, text -> {
            File outFile = saveStreamWithName(in, text);
            if (outFile == null) return;

            final File editorProgramFile = new File(EDITOR_PROGRAM);
            if (!editorProgramFile.isFile()) {
                showErrorDialogAndQuit("The following file does not exist:\n$HOME/bin/tentel-file-editor\n\n"
                    + "Create this file as a script or a symlink - it will be called with the received file as only argument.");
                return;
            }

            // Do this for the user if necessary:
            //noinspection ResultOfMethodCallIgnored
            editorProgramFile.setExecutable(true);

            final Uri scriptUri = new Uri.Builder().scheme("file").path(EDITOR_PROGRAM).build();

            Intent executeIntent = new Intent(tentel_SERVICE.ACTION_SERVICE_EXECUTE, scriptUri);
            executeIntent.setClass(tentelFileReceiverActivity.this, tentelService.class);
            executeIntent.putExtra(tentel_SERVICE.EXTRA_ARGUMENTS, new String[]{outFile.getAbsolutePath()});
            startService(executeIntent);
            finish();
        },
            R.string.action_file_received_open_directory, text -> {
                if (saveStreamWithName(in, text) == null) return;

                Intent executeIntent = new Intent(tentel_SERVICE.ACTION_SERVICE_EXECUTE);
                executeIntent.putExtra(tentel_SERVICE.EXTRA_WORKDIR, tentel_RECEIVEDIR);
                executeIntent.setClass(tentelFileReceiverActivity.this, tentelService.class);
                startService(executeIntent);
                finish();
            },
            android.R.string.cancel, text -> finish(), dialog -> {
                if (mFinishOnDismissNameDialog) finish();
            });
    }

    public File saveStreamWithName(InputStream in, String attachmentFileName) {
        File receiveDir = new File(tentel_RECEIVEDIR);

        if (DataUtils.isNullOrEmpty(attachmentFileName)) {
            showErrorDialogAndQuit("File name cannot be null or empty");
            return null;
        }

        if (!receiveDir.isDirectory() && !receiveDir.mkdirs()) {
            showErrorDialogAndQuit("Cannot create directory: " + receiveDir.getAbsolutePath());
            return null;
        }

        try {
            final File outFile = new File(receiveDir, attachmentFileName);
            try (FileOutputStream f = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[4096];
                int readBytes;
                while ((readBytes = in.read(buffer)) > 0) {
                    f.write(buffer, 0, readBytes);
                }
            }
            return outFile;
        } catch (IOException e) {
            showErrorDialogAndQuit("Error saving file:\n\n" + e);
            Logger.logStackTraceWithMessage(LOG_TAG, "Error saving file", e);
            return null;
        }
    }

    void handleUrlAndFinish(final String url) {
        final File urlOpenerProgramFile = new File(URL_OPENER_PROGRAM);
        if (!urlOpenerProgramFile.isFile()) {
            showErrorDialogAndQuit("The following file does not exist:\n$HOME/bin/tentel-url-opener\n\n"
                + "Create this file as a script or a symlink - it will be called with the shared URL as the first argument.");
            return;
        }

        // Do this for the user if necessary:
        //noinspection ResultOfMethodCallIgnored
        urlOpenerProgramFile.setExecutable(true);

        final Uri urlOpenerProgramUri = new Uri.Builder().scheme("file").path(URL_OPENER_PROGRAM).build();

        Intent executeIntent = new Intent(tentel_SERVICE.ACTION_SERVICE_EXECUTE, urlOpenerProgramUri);
        executeIntent.setClass(tentelFileReceiverActivity.this, tentelService.class);
        executeIntent.putExtra(tentel_SERVICE.EXTRA_ARGUMENTS, new String[]{url});
        startService(executeIntent);
        finish();
    }

}
