package dev.picori.tmc;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Launcher activity. On first run no ROM has been imported yet, so show a
 * short welcome screen and let the user point at their own Minish Cap dump.
 * The picked file is validated by hash and copied into the app's files dir as
 * {@code baserom.gba} — the same place the native port probes and the in-app
 * picker installs to — then we hand off to the SDL {@link TMCActivity}. On
 * every later launch the ROM is already there and we go straight into the
 * game.
 *
 * <p>This is the Android-native counterpart of the desktop prelaunch
 * "Select ROM..." card (port/port_rom_picker.c). It exists so a fresh install
 * never depends on the user copying a file into
 * {@code Android/data/dev.picori.tmc/files/} by hand, and so the prompt is
 * reachable even if the in-engine UI fails to come up on a given device.
 */
public class TMCSetupActivity extends Activity {

    private static final String TAG = "TMCSetup";
    private static final int REQUEST_PICK_ROM = 1001;

    /**
     * Installed-ROM names, in the same order the native port probes them
     * (kRomCandidates in port/port_rom.c). A file under any of these names is
     * treated as "already imported".
     */
    private static final String[] INSTALLED_ROM_NAMES = {
        "baserom.gba",
        "baserom_eu.gba",
        "baserom_jp.gba",
        "tmc.gba",
        "tmc_eu.gba",
        "tmc_jp.gba",
        "synthetic_baserom.gba",
    };

    /** Where a newly picked ROM is installed. Always the USA default name;
     *  the port detects the real region from the ROM header. */
    private static final String INSTALL_NAME = "baserom.gba";

    /**
     * A picked dump is recognised as a TMC ROM when its SHA-1 matches; for
     * dumps with a known SHA-256 that must match too (SHA-1 alone is
     * collision-broken). Mirrors {@code ValidateRomBuffer} in
     * port/port_rom_picker.c — keep the two lists in sync.
     */
    private static final String[][] KNOWN_ROMS = {
        {
            "b4bd50e4131b027c334547b4524e2dbbd4227130",
            "bedc74df62755f705398273de8ed3bc59be610cf55760d0b9aa277f1f5035e73",
            "USA",
        },
        {
            "cff199b36ff173fb6faf152653d1bccf87c26fb7",
            "c84645731952b7677f514ae222683504066334ab2af904e64a8a84ffc1af46c6",
            "EU",
        },
        {
            "6c5404a1effb17f481f352181d0f1c61a2765c5d",
            "16ac2572ba17e9cb2a70093d41f97ef8cff66c56417e45ea98adacdc51bb4b38",
            "JP",
        },
        {"63fcad218f9047b6a9edbb68c98bd0dec322d7a1", "", "USA Demo"},
        {"9cdb56fa79bba13158b81925c1f3641251326412", "", "JP Demo"},
    };

    // Same size gate the native picker uses: 4 MB demos up to the 16 MB
    // retail ROM plus a little slop.
    private static final int MIN_ROM_BYTES = 0x00200000;
    private static final int MAX_ROM_BYTES = 0x01010000;

    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private Button selectButton;
    private ProgressBar spinner;
    private boolean working;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            if (findInstalledRom() != null) {
                launchGame();
                return;
            }
            buildUi();
        } catch (Throwable e) {
            // An exception here used to be an instant silent close. Show the
            // trace instead so it can end up in a bug report.
            Log.e(TAG, "Startup failed", e);
            showStartupError(e);
        }
    }

    /** The first installed ROM found in the app's data dir, or null. */
    private File findInstalledRom() {
        File dir = installDir();
        for (String name : INSTALLED_ROM_NAMES) {
            File file = new File(dir, name);
            if (file.isFile() && file.length() > 0) {
                return file;
            }
        }
        return null;
    }

    /**
     * The directory the native port will chdir to, and therefore where it
     * probes for a ROM: the app's external files dir when it is writable
     * (the port's own preference), else the private files dir. Checking the
     * same directory keeps "already imported" from disagreeing with the
     * native probe on a device where one of the two is unavailable.
     */
    private File installDir() {
        File external = getExternalFilesDir(null);
        if (external != null && external.canWrite()) {
            return external;
        }
        return getFilesDir();
    }

    private void launchGame() {
        startActivity(new Intent(this, TMCActivity.class));
        finish();
    }

    /** Bare-bones error screen for a startup failure; kept free of anything
     *  that could itself fail (no custom assets, no second display). */
    private void showStartupError(Throwable e) {
        java.io.StringWriter trace = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(trace));

        TextView text = new TextView(this);
        text.setText("The app failed to start. Please report this at "
                + "github.com/nexa-builds/tmc-android-ds with the trace below.\n\n" + trace);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        int pad = dp(24);
        text.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#101018"));
        scroll.addView(text);
        setContentView(scroll);
    }

    // ---- UI (built in code to avoid pulling in layout/androidx deps) ----

    private void buildUi() {
        int pad = dp(24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#101018"));
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Project Picori");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView blurb = new TextView(this);
        blurb.setText("This game needs a ROM from your own copy of "
                + "“The Legend of Zelda: The Minish Cap”.\n\n"
                + "Select your .gba file below. It's copied into the app's "
                + "folder once, so you won't be asked again.\n\n"
                + "We identify it by SHA-1, so the filename doesn't matter. "
                + "USA, EU, JP, and the USA/JP demos are supported.");
        blurb.setTextColor(Color.parseColor("#C8C8D0"));
        blurb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        blurb.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blurbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blurbLp.topMargin = dp(16);
        blurb.setLayoutParams(blurbLp);
        root.addView(blurb);

        selectButton = new Button(this);
        selectButton.setText("Select ROM");
        selectButton.setAllCaps(false);
        selectButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(28);
        selectButton.setLayoutParams(btnLp);
        selectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickRom();
            }
        });
        root.addView(selectButton);

        spinner = new ProgressBar(this);
        spinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        spinLp.topMargin = dp(20);
        spinner.setLayoutParams(spinLp);
        root.addView(spinner);

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#FF8A80"));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(20);
        statusView.setLayoutParams(statusLp);
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void pickRom() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_PICK_ROM);
        } catch (ActivityNotFoundException e) {
            setStatus("No file picker is available on this device.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_ROM || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        importInBackground(data.getData());
    }

    private void importInBackground(final Uri romUri) {
        if (working) {
            return;
        }
        working = true;
        setStatus("");
        spinner.setVisibility(View.VISIBLE);
        selectButton.setEnabled(false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    importRom(romUri);
                } catch (Exception e) {
                    Log.e(TAG, "ROM import failed", e);
                    error = e.getMessage() != null ? e.getMessage() : "Something went wrong.";
                }
                final String resultError = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        working = false;
                        spinner.setVisibility(View.GONE);
                        selectButton.setEnabled(true);
                        if (resultError != null) {
                            setStatus(resultError);
                        } else {
                            launchGame();
                        }
                    }
                });
            }
        }, "rom-import").start();
    }

    /** Read the picked file, validate it, and install it as baserom.gba. */
    private void importRom(Uri romUri) throws IOException {
        byte[] rom = readUri(romUri);
        if (rom.length < MIN_ROM_BYTES || rom.length > MAX_ROM_BYTES) {
            throw new IOException("That doesn't look like a Game Boy Advance ROM ("
                    + rom.length + " bytes).");
        }

        String region = validateRom(rom);
        if (region == null) {
            throw new IOException("That file's SHA-1 doesn't match any known Minish Cap "
                    + "ROM. Accepted dumps: USA, EU, JP, and the USA/JP demos. "
                    + "Fan translations and other romhacks are not supported.");
        }

        File dir = installDir();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Couldn't create the app's files folder.");
        }
        File out = new File(dir, INSTALL_NAME);
        // Write to a temp file first so a crash mid-write can't leave a
        // truncated baserom.gba that would look "ready" next launch.
        File tmp = new File(dir, INSTALL_NAME + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(rom);
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(out)) {
            // renameTo can fail if the target exists on some filesystems.
            out.delete();
            if (!tmp.renameTo(out)) {
                tmp.delete();
                throw new IOException("Couldn't save the ROM into the app folder.");
            }
        }
        Log.i(TAG, "Installed " + region + " ROM -> " + out);
    }

    /** Returns the region tag when {@code rom} is a known TMC dump, else null. */
    private static String validateRom(byte[] rom) {
        String sha1 = hex(digest("SHA-1", rom));
        for (String[] known : KNOWN_ROMS) {
            if (!known[0].equals(sha1)) {
                continue;
            }
            // SHA-1 is collision-broken, so when we have a SHA-256 for this
            // dump require it to match too.
            if (known[1] != null && !known[1].isEmpty()) {
                String sha256 = hex(digest("SHA-256", rom));
                if (!known[1].equals(sha256)) {
                    return null;
                }
            }
            return known[2];
        }
        return null;
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1/SHA-256 are mandatory on every Android release.
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private byte[] readUri(Uri uri) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("Couldn't open the selected file.");
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                // Stop before buffering something that clearly isn't a GBA
                // ROM, so a stray multi-GB file can't OOM the import thread.
                if (bos.size() + n > MAX_ROM_BYTES) {
                    throw new IOException("That file is too large to be a Minish Cap ROM.");
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private void setStatus(String text) {
        statusView.setTextColor(Color.parseColor("#FF8A80"));
        statusView.setText(text);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
