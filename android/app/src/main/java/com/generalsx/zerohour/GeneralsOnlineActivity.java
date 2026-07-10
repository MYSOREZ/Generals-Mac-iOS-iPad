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

// GeneralsX @feature Android port 10/07/2026
//
// Account login for GeneralsOnline (playgenerals.online / TheSuperHackers
// GeneralsOnlineServices), the actively-maintained GameSpy replacement for
// Zero Hour multiplayer -- see docs/port/... for how this was chosen over
// Revora/CnC-Online (which retired Generals/ZH support and redirects here).
//
// Auth is a device-code flow, NOT an embedded browser/OAuth redirect: the
// user opens playgenerals.online in ANY browser (their phone, another
// device, doesn't matter), logs in there via Steam/Discord/GameReplays, and
// types the short code that site shows them into this screen. We poll
// CheckLogin with that code until the site confirms it. This is verified
// against the live API (POST https://api.playgenerals.online/env/prod/
// contract/1/CheckLogin, body {"code":"...","client_id":"custom_third_party_client"})
// -- custom_third_party_client is a first-class client identity in their
// own KnownClients enum, no prior registration needed to authenticate.
//
// The exe-CRC version check (VersionCheckController) some of their code
// paths still ask about is a self-serve "your exe is out of date, get this
// patcher" nag for their OWN Windows binary -- nothing on the login or
// matchmaking path actually requires it to match, so we never call it here.

package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class GeneralsOnlineActivity extends Activity {

    private static final String API_BASE = "https://api.playgenerals.online/env/prod/contract/1/";
    private static final String WEBSITE_URL = "https://playgenerals.online/";
    private static final String CLIENT_ID = "custom_third_party_client";

    private static final String PREFS_NAME = "generalsonline_session";
    private static final String PREF_SESSION_TOKEN = "session_token";
    private static final String PREF_REFRESH_TOKEN = "refresh_token";
    private static final String PREF_USER_ID = "user_id";
    private static final String PREF_DISPLAY_NAME = "display_name";
    private static final String PREF_WS_URI = "ws_uri";

    // Native code (once the multiplayer client is wired up) reads this --
    // same plain-marker-file convention as gamedata_path.txt.
    private static final String SESSION_MARKER_NAME = "generalsonline_session.txt";

    private static final int POLL_INTERVAL_MS = 3000;
    private static final int POLL_MAX_ATTEMPTS = 40; // ~2 minutes

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView statusText;
    private EditText codeInput;
    private MaterialButton signInButton;
    private MaterialButton signOutButton;

    private int pollAttempt = 0;
    private boolean polling = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        super.onCreate(savedInstanceState);
        setTitle("GeneralsOnline Account");
        buildUi();
        refreshStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("GeneralsOnline Account");
        title.setTextSize(22);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(dp(4), dp(8), dp(4), dp(4));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Sign in for online multiplayer (playgenerals.online)");
        subtitle.setTextSize(14);
        subtitle.setAlpha(0.7f);
        subtitle.setPadding(dp(4), 0, dp(4), dp(16));
        root.addView(subtitle);

        LinearLayout statusCard = startCard(root, null);
        statusText = new TextView(this);
        statusText.setTextIsSelectable(true);
        statusCard.addView(statusText);
        signOutButton = addButton(statusCard, "Sign Out", this::onSignOut);

        LinearLayout stepsCard = startCard(root, "Sign in");
        TextView step1 = new TextView(this);
        step1.setText(
            "1. Open playgenerals.online in a browser -- on this phone or any other "
            + "device -- and log in with Steam, Discord, or GameReplays.\n"
            + "2. The site shows you a short login code.\n"
            + "3. Type that code below and tap \"Verify Code\"."
        );
        stepsCard.addView(step1);

        addButton(stepsCard, "Open playgenerals.online", this::onOpenWebsite);

        codeInput = new EditText(this);
        codeInput.setHint("Login code");
        codeInput.setSingleLine(true);
        codeInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        stepsCard.addView(codeInput);

        signInButton = addButton(stepsCard, "Verify Code", this::onVerifyCode);
    }

    private LinearLayout startCard(LinearLayout root, String header) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardLp);
        card.setRadius(dp(12));
        card.setCardElevation(dp(2));
        card.setCardBackgroundColor(getColor(R.color.gzh_surface));
        card.setContentPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        card.addView(content);
        root.addView(card);

        if (header != null) {
            TextView headerView = new TextView(this);
            headerView.setText(header);
            headerView.setTextSize(15);
            headerView.setTypeface(headerView.getTypeface(), android.graphics.Typeface.BOLD);
            headerView.setPadding(0, 0, 0, dp(8));
            content.addView(headerView);
        }
        return content;
    }

    private MaterialButton addButton(LinearLayout root, String label, Runnable action) {
        MaterialButton b = new MaterialButton(this);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        root.addView(b, lp);
        return b;
    }

    private void onOpenWebsite() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL)));
        } catch (Exception e) {
            Toast.makeText(this, "No browser available: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onVerifyCode() {
        String code = codeInput.getText().toString().trim();
        if (code.isEmpty()) {
            Toast.makeText(this, "Enter the code from playgenerals.online first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (polling) {
            return;
        }
        polling = true;
        pollAttempt = 0;
        signInButton.setEnabled(false);
        statusText.setText("Checking...");
        pollOnce(code);
    }

    private void pollOnce(String code) {
        new Thread(() -> {
            CheckLoginResult result = callCheckLogin(code);
            handler.post(() -> handlePollResult(code, result));
        }).start();
    }

    private void handlePollResult(String code, CheckLoginResult result) {
        if (result == null) {
            polling = false;
            signInButton.setEnabled(true);
            statusText.setText("Network error -- check your connection and try again.");
            return;
        }

        switch (result.state) {
            case 1: // LoginSuccess
                polling = false;
                signInButton.setEnabled(true);
                saveSession(result);
                refreshStatus();
                Toast.makeText(this, "Signed in as " + result.displayName, Toast.LENGTH_LONG).show();
                break;
            case 2: // LoginFailed
                polling = false;
                signInButton.setEnabled(true);
                statusText.setText("That code was rejected or expired -- get a new one from playgenerals.online.");
                break;
            case 0: // Waiting
                ++pollAttempt;
                if (pollAttempt >= POLL_MAX_ATTEMPTS) {
                    polling = false;
                    signInButton.setEnabled(true);
                    statusText.setText("Timed out waiting for confirmation -- try again.");
                } else {
                    statusText.setText("Waiting for you to confirm on playgenerals.online... ("
                        + pollAttempt + "/" + POLL_MAX_ATTEMPTS + ")");
                    handler.postDelayed(() -> pollOnce(code), POLL_INTERVAL_MS);
                }
                break;
            default: // None (bad/unknown code) or anything else
                polling = false;
                signInButton.setEnabled(true);
                statusText.setText("Unrecognized code -- double check it and try again.");
                break;
        }
    }

    private static class CheckLoginResult {
        int state = -1;
        String sessionToken = "";
        String refreshToken = "";
        long userId = -1;
        String displayName = "";
        String wsUri = "";
    }

    // Runs on a background thread.
    private CheckLoginResult callCheckLogin(String code) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(API_BASE + "CheckLogin");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);

            JSONObject body = new JSONObject();
            body.put("code", code);
            body.put("client_id", CLIENT_ID);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            java.io.InputStream in = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) {
                return null;
            }
            String responseBody = readAll(in);
            JSONObject json = new JSONObject(responseBody);

            CheckLoginResult result = new CheckLoginResult();
            result.state = json.optInt("result", -1);
            result.sessionToken = json.optString("session_token", "");
            result.refreshToken = json.optString("refresh_token", "");
            result.userId = json.optLong("user_id", -1);
            result.displayName = json.optString("display_name", "");
            result.wsUri = json.optString("ws_uri", "");
            return result;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toString("UTF-8");
    }

    private void saveSession(CheckLoginResult result) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_SESSION_TOKEN, result.sessionToken)
            .putString(PREF_REFRESH_TOKEN, result.refreshToken)
            .putLong(PREF_USER_ID, result.userId)
            .putString(PREF_DISPLAY_NAME, result.displayName)
            .putString(PREF_WS_URI, result.wsUri)
            .apply();

        // Plain marker file for native code, mirroring gamedata_path.txt --
        // one "key=value" per line, no secrets beyond what's already only
        // readable by this app's own uid (same sandboxing as every other
        // marker file this app writes).
        File marker = new File(getFilesDir(), SESSION_MARKER_NAME);
        try (FileWriter w = new FileWriter(marker, false)) {
            w.write("session_token=" + result.sessionToken + "\n");
            w.write("user_id=" + result.userId + "\n");
            w.write("display_name=" + result.displayName + "\n");
            w.write("ws_uri=" + result.wsUri + "\n");
        } catch (IOException e) {
            // Not fatal: native multiplayer code isn't wired up yet anyway.
        }
    }

    private void clearSession() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply();
        new File(getFilesDir(), SESSION_MARKER_NAME).delete();
    }

    private void onSignOut() {
        clearSession();
        refreshStatus();
        Toast.makeText(this, "Signed out.", Toast.LENGTH_SHORT).show();
    }

    private void refreshStatus() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String displayName = prefs.getString(PREF_DISPLAY_NAME, null);
        String sessionToken = prefs.getString(PREF_SESSION_TOKEN, null);

        if (displayName != null && sessionToken != null && !sessionToken.isEmpty()) {
            statusText.setText("Signed in as " + displayName + ".");
            signOutButton.setEnabled(true);
        } else {
            statusText.setText("Not signed in.");
            signOutButton.setEnabled(false);
        }
    }

    // Static helper so other screens (SetupActivity) can show a one-line
    // status without duplicating the SharedPreferences keys.
    static String getSignedInDisplayName(android.content.Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String displayName = prefs.getString(PREF_DISPLAY_NAME, null);
        String sessionToken = prefs.getString(PREF_SESSION_TOKEN, null);
        if (displayName != null && sessionToken != null && !sessionToken.isEmpty()) {
            return displayName;
        }
        return null;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
