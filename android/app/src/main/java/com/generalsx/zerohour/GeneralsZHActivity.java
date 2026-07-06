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

// GeneralsX @build Android port 06/07/2026
// Thin shell over SDL3's SDLActivity. Responsibilities:
//  1. Name the native libraries to load (libmain.so = the game).
//  2. On launch, extract the small bundled runtime files (fonts/, dxvk.conf,
//     DefaultOptions.ini) from APK assets into the external files dir, which
//     SDL3Main.cpp makes the game's working directory. Game .big archives are
//     NOT bundled — the user copies their own (see docs/port/ANDROID_PORT.md).
//  3. Show a readable error instead of a native crash when game data is
//     missing, since that is the #1 first-run failure mode.

package com.generalsx.zerohour;

import android.app.AlertDialog;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.util.Log;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class GeneralsZHActivity extends SDLActivity {

    private static final String TAG = "GeneralsZH";

    // Marker: at least one retail archive must exist next to the extracted
    // config before the engine can boot into anything but a black screen.
    private static final String[] REQUIRED_GAME_FILES = {
        "INIZH.big", "INI.big"
    };

    @Override
    protected String[] getLibraries() {
        return new String[] {
            "SDL3",
            // libmain.so — the game itself (z_generals target, android-vulkan
            // preset). Its DT_NEEDED entries (SDL3_image, openal, c++_shared)
            // resolve from the same APK; the DXVK d3d8/d3d9 libraries are
            // dlopen()ed by the engine at D3D init.
            "main"
        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        extractBundledRuntime();
        warnIfGameDataMissing();
        super.onCreate(savedInstanceState);
    }

    /**
     * Copy the APK's bundled runtime files into the external files dir
     * (the game's working directory). Existing files are left alone so a
     * user-edited dxvk.conf or replaced font survives updates; delete the
     * file to get a fresh copy on next launch.
     */
    private void extractBundledRuntime() {
        File root = getExternalFilesDir(null);
        if (root == null) {
            Log.e(TAG, "external files dir unavailable; asset extraction skipped");
            return;
        }
        copyAssetTree("gamedata", root);
    }

    private void copyAssetTree(String assetPath, File destRoot) {
        AssetManager assets = getAssets();
        try {
            String[] children = assets.list(assetPath);
            if (children == null || children.length == 0) {
                // Leaf: a real file
                String rel = assetPath.substring("gamedata".length());
                if (rel.startsWith("/")) rel = rel.substring(1);
                if (rel.isEmpty()) return;
                File dest = new File(destRoot, rel);
                if (dest.exists()) return;
                File parent = dest.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    Log.e(TAG, "mkdirs failed for " + parent);
                    return;
                }
                try (InputStream in = assets.open(assetPath);
                     OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
                Log.i(TAG, "extracted " + rel);
            } else {
                for (String child : children) {
                    copyAssetTree(assetPath + "/" + child, destRoot);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "asset extraction failed for " + assetPath, e);
        }
    }

    /**
     * The engine dies inscrutably without its .big archives; catch the
     * missing-data case here with instructions instead. Non-blocking: the
     * dialog shows over the (black) game surface and the user can still
     * back out.
     */
    private void warnIfGameDataMissing() {
        File root = getExternalFilesDir(null);
        if (root == null) {
            return;
        }
        File gameData = new File(root, "GameData");
        File effectiveRoot = gameData.isDirectory() ? gameData : root;
        for (String name : REQUIRED_GAME_FILES) {
            if (new File(effectiveRoot, name).exists()) {
                return;
            }
        }
        final String path = effectiveRoot.getAbsolutePath();
        runOnUiThread(() -> new AlertDialog.Builder(this)
            .setTitle("Game data not found")
            .setMessage("Copy your Command & Conquer Generals Zero Hour game files "
                + "(the .big archives, Data/, ZH_Generals/ from your own install) to:\n\n"
                + path + "\n\nover USB or with: adb push <files> \""
                + path + "\"\n\nSee docs/port/ANDROID_PORT.md in the repository.")
            .setPositiveButton("OK", null)
            .show());
    }
}
