package dev.picori.tmc;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Launch-time screen selection, before SDL creates or attaches its surface. */
final class GameScreenLayout {
    private static final String TAG = "GameScreenLayout";
    private static final int CONFIG_MAX_BYTES = 1 << 20;

    private GameScreenLayout() {}

    /**
     * Find the one powered presentation display for the swapped layout. On
     * GammaOS Nano / RG DS Plus the Activity stays on the lower/default panel
     * and this is the upper panel. Do not assume a display number or guess
     * between multiple monitors. The normal Thor layout does not use this.
     */
    static Display findGameDisplay(Activity activity) {
        if (!readSwapFlag(activity)) {
            return null;
        }
        int activityDisplay = activity.getWindowManager().getDefaultDisplay().getDisplayId();
        if (activityDisplay != Display.DEFAULT_DISPLAY) {
            return null;
        }
        DisplayManager dm = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            return null;
        }
        Display found = null;
        for (Display display : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY
                    || !display.isValid()
                    || (display.getFlags() & Display.FLAG_PRESENTATION) == 0) {
                continue;
            }
            int state = display.getState();
            if (state != Display.STATE_ON && state != Display.STATE_UNKNOWN) {
                continue;
            }
            if (found != null) {
                Log.w(TAG, "Multiple presentation displays; keeping the default layout");
                return null;
            }
            found = display;
        }
        if (found == null) {
            Log.w(TAG, "Swap requested but no ready presentation display; using the default layout");
        }
        return found;
    }

    /** Same external-first config lookup as port_main.c; never rewrite it. */
    private static boolean readSwapFlag(Context context) {
        File[] dirs = { context.getExternalFilesDir(null), context.getFilesDir() };
        for (File dir : dirs) {
            if (dir == null) {
                continue;
            }
            File config = new File(dir, "config.json");
            if (!config.isFile()) {
                continue;
            }
            if (!config.canRead()) {
                Log.w(TAG, "Screen config is not readable by the app; keeping the default layout");
                return false;
            }
            try {
                return new JSONObject(readText(config)).optBoolean("second_screen_swap", false);
            } catch (IOException | org.json.JSONException e) {
                // A parse exception can quote file contents. Log only the
                // outcome, not the config or the exception message.
                Log.w(TAG, "Could not read screen layout; keeping the default layout");
                return false;
            }
        }
        return false;
    }

    private static String readText(File file) throws IOException {
        long size = file.length();
        if (size <= 0 || size > CONFIG_MAX_BYTES) {
            throw new IOException("Invalid config size");
        }
        byte[] raw = new byte[(int) size];
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            in.readFully(raw);
        }
        return new String(raw, StandardCharsets.UTF_8);
    }
}
