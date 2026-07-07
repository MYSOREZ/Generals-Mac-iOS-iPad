/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @build Android port 07/07/2026
//
// A standalone launcher icon ("GeneralsZH Setup"), separate from the game
// itself, so configuring the game folder or reading a crash log never
// depends on the game having launched successfully first — and never
// requires adb. This is the practical answer to "there's no launcher": a
// full mod-manager-style launcher (à la GenLauncher) is future scope, but
// picking where the game lives and seeing why it crashed are needed on
// every single install, so they live here now.

package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class SetupActivity extends Activity {

    static final String PREFS_NAME = "generalszh_setup";
    static final String PREF_GAME_PATH = "game_path";

    // TheSuperHackers @bugfix Android port 07/07/2026 SharedPreferences and
    // getFilesDir() both live under /data/data/<pkg>/ and are wiped the
    // moment the app is uninstalled -- which is exactly what a sideloaded
    // APK update often requires if the installer treats it as a fresh
    // install rather than an in-place update. Mirror the chosen path into a
    // small marker file on shared external storage (survives uninstall,
    // since it's outside the app's private/package-scoped directories) so a
    // fresh install can recover it automatically instead of re-prompting.
    private static final String EXTERNAL_MARKER_NAME = ".generalszh_gamepath.txt";

    // Marker files SDL3Main.cpp / GeneralsZHActivity check for on launch —
    // must match GameEngine/CMake's GeneralsMD/Code/Main/SDL3Main.cpp exactly.
    private static final String[] REQUIRED_GAME_FILES = { "INIZH.big", "INI.big" };

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // TheSuperHackers @bugfix Android port 07/07/2026 See the matching
        // comment in GeneralsZHActivity.onCreate(): reinforce the manifest's
        // screenOrientation="landscape" in code so Setup -> Launch never
        // starts a rotation the game's window-size probe could still catch
        // mid-flight.
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        super.onCreate(savedInstanceState);
        setTitle("GeneralsZH Settings");
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("Command & Conquer Generals: Zero Hour — Android Setup");
        title.setTextSize(20);
        title.setPadding(0, 0, 0, dp(16));
        root.addView(title);

        statusText = new TextView(this);
        statusText.setTextIsSelectable(true);
        statusText.setPadding(0, 0, 0, dp(24));
        root.addView(statusText);

        addButton(root, "Select Game Folder", this::onSelectGameFolder);
        addButton(root, "View Logs", this::onViewLogs);
        addButton(root, "Launch Game", this::onLaunchGame);
        addButton(root, "Clear Game Folder Setting", this::onClearGameFolder);

        buildUiScaleSection(root);

        TextView help = new TextView(this);
        help.setPadding(0, dp(24), 0, 0);
        help.setText(
            "How this works:\n\n"
            + "1. \"Select Game Folder\" opens a folder browser on your phone's "
            + "storage. Navigate to wherever you copied your own Command & Conquer "
            + "Generals Zero Hour install (the folder containing INIZH.big, Data\\, "
            + "ZH_Generals\\, etc.) and tap \"Use This Folder\".\n\n"
            + "2. If browsing looks empty, Android is asking for the \"All files "
            + "access\" permission first — grant it in the screen that opens (this "
            + "is a normal Android permission, no root involved).\n\n"
            + "3. \"View Logs\" shows the engine log and, if the game crashed, a "
            + "native crash log — no computer or adb needed. Use the Share button "
            + "there to send the log to yourself.\n\n"
            + "4. \"Launch Game\" starts the game with the folder you picked."
        );
        root.addView(help);
    }

    // TheSuperHackers @feature Android port 07/07/2026 Menu text size scaling
    // (GlobalLanguage::adjustFontSize()) has no in-game slider yet -- the
    // Options screen lives in the user's own game data (.wnd layout), not in
    // this engine source tree, so a real in-game control can't be added from
    // here. Expose the same "ResolutionFontAdjustment" percentage here
    // instead, writing straight into the Options.ini this Android build
    // actually reads (see SDL3Main.cpp: HOME=<internal storage>, so the file
    // is <filesDir>/.local/share/GeneralsX/GeneralsZH/Options.ini) -- no need
    // to wait for the game to visit its own Options menu first.
    private android.widget.SeekBar uiScaleSeekBar;
    private TextView uiScaleLabel;

    private void buildUiScaleSection(LinearLayout root) {
        TextView header = new TextView(this);
        header.setText("Menu Text Size");
        header.setTextSize(16);
        header.setPadding(0, dp(8), 0, dp(4));
        root.addView(header);

        uiScaleLabel = new TextView(this);
        root.addView(uiScaleLabel);

        uiScaleSeekBar = new android.widget.SeekBar(this);
        uiScaleSeekBar.setMax(150);
        uiScaleSeekBar.setProgress(readUiScalePercent());
        updateUiScaleLabel(uiScaleSeekBar.getProgress());
        uiScaleSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                updateUiScaleLabel(progress);
            }
            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) { }
            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) { }
        });
        root.addView(uiScaleSeekBar);

        addButton(root, "Apply Menu Text Size", () -> {
            writeUiScalePercent(uiScaleSeekBar.getProgress());
            Toast.makeText(this, "Saved. Takes effect next time you launch the game.", Toast.LENGTH_LONG).show();
        });

        TextView uiScaleHelp = new TextView(this);
        uiScaleHelp.setPadding(0, 0, 0, dp(8));
        uiScaleHelp.setText(
            "Scales most menu button/label text for the screen resolution. 70 is "
            + "the game's own default; higher values make menu text bigger. Takes "
            + "effect the next time you launch the game, not live."
        );
        root.addView(uiScaleHelp);
    }

    private void updateUiScaleLabel(int percent) {
        uiScaleLabel.setText("Scale: " + percent + "%");
    }

    private File optionsIniFile() {
        return new File(getFilesDir(), ".local/share/GeneralsX/GeneralsZH/Options.ini");
    }

    private File defaultOptionsIniFile() {
        String gamePath = getSavedGamePath();
        return gamePath != null ? new File(gamePath, "DefaultOptions.ini") : null;
    }

    private int readUiScalePercent() {
        java.util.Map<String, String> prefs = readKeyValueFile(optionsIniFile());
        String val = prefs.get("ResolutionFontAdjustment");
        if (val != null) {
            try {
                return Math.max(0, Math.min(150, Integer.parseInt(val.trim())));
            } catch (NumberFormatException ignored) {
                // Fall through to the engine's own default below.
            }
        }
        return 70;
    }

    private void writeUiScalePercent(int percent) {
        File file = optionsIniFile();

        // TheSuperHackers @bugfix Android port 07/07/2026 SDL3Main.cpp only
        // seeds Options.ini from the game folder's DefaultOptions.ini (full
        // GPU-detail defaults) the FIRST time it doesn't already exist. If we
        // create a bare Options.ini containing only ResolutionFontAdjustment
        // before the user ever launches the game once, that seeding is
        // permanently skipped and they silently lose those defaults. Seed
        // from DefaultOptions.ini ourselves first when the file is new.
        java.util.LinkedHashMap<String, String> prefs;
        if (!file.isFile()) {
            prefs = new java.util.LinkedHashMap<>(readKeyValueFile(defaultOptionsIniFile()));
        } else {
            prefs = new java.util.LinkedHashMap<>(readKeyValueFile(file));
        }
        prefs.put("ResolutionFontAdjustment", String.valueOf(percent));

        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Toast.makeText(this, "Could not create Options.ini folder", Toast.LENGTH_LONG).show();
            return;
        }
        try (java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(file, false))) {
            for (java.util.Map.Entry<String, String> e : prefs.entrySet()) {
                w.print(e.getKey());
                w.print(" = ");
                w.print(e.getValue());
                w.print('\n');
            }
        } catch (java.io.IOException e) {
            Toast.makeText(this, "Could not save Options.ini: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // Matches UserPreferences::load()'s own format exactly ("key = value"
    // lines) so a file the engine already wrote round-trips untouched.
    private static java.util.Map<String, String> readKeyValueFile(File file) {
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        if (file == null || !file.isFile()) {
            return result;
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                if (key.isEmpty() || val.isEmpty()) continue;
                result.put(key, val);
            }
        } catch (java.io.IOException ignored) {
            // Treat as empty; caller falls back to defaults.
        }
        return result;
    }

    private void addButton(LinearLayout root, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        root.addView(b, lp);
    }

    private void refreshStatus() {
        String path = getSavedGamePath();
        StringBuilder sb = new StringBuilder();
        if (path == null) {
            sb.append("Game folder: not set.\n");
        } else {
            boolean valid = isValidGameFolder(new File(path));
            sb.append("Game folder: ").append(path).append('\n');
            sb.append(valid ? "Status: looks valid (found game archive files).\n"
                             : "Status: this folder does NOT contain the expected "
                               + "INIZH.big / INI.big — double check you picked the right one.\n");
        }
        sb.append('\n');
        sb.append("Crash/engine logs live in this app's private storage and are only "
            + "reachable through \"View Logs\" above — Android hides them from normal "
            + "file managers by design (this is standard Android sandboxing, not "
            + "specific to this app).");
        statusText.setText(sb.toString());
    }

    private String getSavedGamePath() {
        return getSavedGamePath(this);
    }

    // Public + static so GeneralsZHActivity uses the exact same recovery
    // logic instead of its own copy that only ever checked SharedPreferences.
    static String getSavedGamePath(android.content.Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String path = prefs.getString(PREF_GAME_PATH, null);
        if (path != null) {
            return path;
        }

        // Not in this install's private prefs (fresh install after an
        // uninstall, most likely) -- check the external marker left by a
        // previous install and self-heal by restoring it into prefs.
        String recovered = readExternalMarker();
        if (recovered != null && isValidGameFolder(new File(recovered))) {
            prefs.edit().putString(PREF_GAME_PATH, recovered).apply();
            File nativeMarker = new File(ctx.getFilesDir(), "gamedata_path.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(nativeMarker, false)) {
                w.write(recovered);
                w.write("\n");
            } catch (java.io.IOException e) {
                // Not fatal: native code just won't see the recovered path
                // until the user re-saves it once via Setup.
            }
            return recovered;
        }
        return null;
    }

    private static File externalMarkerFile() {
        File root = Environment.getExternalStorageDirectory();
        return root != null ? new File(root, EXTERNAL_MARKER_NAME) : null;
    }

    private static String readExternalMarker() {
        File marker = externalMarkerFile();
        if (marker == null || !marker.isFile()) {
            return null;
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(marker))) {
            String line = r.readLine();
            return (line != null && !line.isEmpty()) ? line.trim() : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    static boolean isValidGameFolder(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        for (String name : REQUIRED_GAME_FILES) {
            if (new File(dir, name).exists()) {
                return true;
            }
        }
        return false;
    }

    private void onSelectGameFolder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Toast.makeText(this,
                "Grant \"Allow access to manage all files\" on the next screen, then come back and tap Select Game Folder again.",
                Toast.LENGTH_LONG).show();
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
            return;
        }
        startActivityForResult(new Intent(this, FolderPickerActivity.class), 1001);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK && data != null) {
            String path = data.getStringExtra(FolderPickerActivity.EXTRA_SELECTED_PATH);
            if (path != null) {
                saveGamePath(path);
                refreshStatus();
                boolean valid = isValidGameFolder(new File(path));
                Toast.makeText(this,
                    valid ? "Game folder saved." : "Saved, but no INIZH.big/INI.big found there — check the folder.",
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    private void saveGamePath(String path) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_GAME_PATH, path)
            .apply();
        // GeneralsZHActivity/SDL3Main.cpp read this plain-text marker on the
        // NEXT launch (native code has no Android SharedPreferences access).
        File marker = new File(getFilesDir(), "gamedata_path.txt");
        try (java.io.FileWriter w = new java.io.FileWriter(marker, false)) {
            w.write(path);
            w.write("\n");
        } catch (java.io.IOException e) {
            Toast.makeText(this, "Could not save folder marker: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        File externalMarker = externalMarkerFile();
        if (externalMarker != null) {
            try (java.io.FileWriter w = new java.io.FileWriter(externalMarker, false)) {
                w.write(path);
                w.write("\n");
            } catch (java.io.IOException e) {
                // Not fatal: uninstall-survival just won't work for this
                // install; the private-prefs/marker-file path above still
                // covers normal in-place updates.
            }
        }
        File bundledRoot = getExternalFilesDir(null);
        if (bundledRoot != null) {
            copyBundledRuntimeIfMissing(bundledRoot, path);
        }
    }

    // GeneralsX @bugfix Android port 07/07/2026 dxvk.conf, DefaultOptions.ini,
    // AND the entire fonts/ directory are all read by the engine relative to
    // its CWD (see SDL3Main.cpp and render2dsentence.cpp's
    // Locate_Font_FontConfig, which does access("fonts/<name>.ttf", R_OK) with
    // no absolute-path fallback on Android/iOS). CWD is now whatever folder
    // the user picked — not the external-files-dir path package-android-zh.sh
    // originally extracted the APK's bundled copies into. Missing fonts/
    // specifically means EVERY W3DFont load fails ("load miss" for every
    // single font in the log) and every button in the UI renders with no
    // text at all — copy all three, not just dxvk.conf. Static + takes
    // bundledRoot explicitly so GeneralsZHActivity can also call this on
    // every launch (an already-configured install needs the fix applied
    // retroactively, not just at folder-selection time).
    static void copyBundledRuntimeIfMissing(File bundledRoot, String gameFolderPath) {
        copyFileIfMissing(new File(bundledRoot, "dxvk.conf"), new File(gameFolderPath, "dxvk.conf"));
        copyFileIfMissing(new File(bundledRoot, "DefaultOptions.ini"), new File(gameFolderPath, "DefaultOptions.ini"));
        copyDirIfMissing(new File(bundledRoot, "fonts"), new File(gameFolderPath, "fonts"));
    }

    private static void copyFileIfMissing(File bundled, File dest) {
        if (dest.exists() || !bundled.exists()) {
            return;
        }
        try (java.io.InputStream in = new java.io.FileInputStream(bundled);
             java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (java.io.IOException e) {
            // Not fatal: caller falls back to its own defaults without this file.
        }
    }

    private static void copyDirIfMissing(File bundledDir, File destDir) {
        if (destDir.exists() || !bundledDir.isDirectory()) {
            return;
        }
        if (!destDir.mkdirs()) {
            return;
        }
        File[] children = bundledDir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            copyFileIfMissing(child, new File(destDir, child.getName()));
        }
    }

    private void onClearGameFolder() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(PREF_GAME_PATH).apply();
        new File(getFilesDir(), "gamedata_path.txt").delete();
        File externalMarker = externalMarkerFile();
        if (externalMarker != null) {
            externalMarker.delete();
        }
        refreshStatus();
        Toast.makeText(this, "Cleared. The game will fall back to its default folder convention.", Toast.LENGTH_SHORT).show();
    }

    private void onViewLogs() {
        startActivity(new Intent(this, LogViewerActivity.class));
    }

    private void onLaunchGame() {
        startActivity(new Intent(this, GeneralsZHActivity.class));
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
