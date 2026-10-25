package com.kavunos.tv;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Shown by Home after a plain APK install, while the remote's Home button still opens the TV's own home screen.
 * An app cannot make itself the default Home, and on many TVs the stock launcher wins without asking; the Home
 * button service (HomeKeyService) is the one switch the owner can turn on from the TV's own Accessibility
 * settings. This page says so in two steps and opens that page; once Home is ready it closes itself.
 */
public final class SetupActivity extends TvScreen {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        initScreen();
        root.setBackgroundColor(0xFF0B0B0D);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setPadding(dp(64), dp(48), dp(64), dp(48));
        root.addView(columns, match());

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER);
        columns.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.9f));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(36));
            }
        });
        logo.setClipToOutline(true);
        left.addView(logo, new LinearLayout.LayoutParams(dp(160), dp(160)));
        TextView title = text(getString(R.string.setup_title), TITLE_SP, Color.WHITE, R.font.inter_semibold);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(24), 0, 0);
        left.addView(title);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f);
        listParams.setMarginStart(dp(48));
        columns.addView(list, listParams);

        TextView body = text(getString(R.string.setup_body), BODY_SP, 0xCCFFFFFF, R.font.inter_regular);
        body.setLineSpacing(dp(4), 1f);
        body.setPadding(dp(18), 0, dp(18), dp(28));
        list.addView(body);
        View first = row(list, R.string.setup_open_accessibility, null, () -> {
            Intent accessibility = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            // Android TV 7.x lists Accessibility in Settings but has no screen for this intent.
            open(accessibility.resolveActivity(getPackageManager()) != null ? accessibility
                    : new Intent(Settings.ACTION_SETTINGS));
        });
        Intent homeSettings = new Intent(Settings.ACTION_HOME_SETTINGS);
        if (homeSettings.resolveActivity(getPackageManager()) != null) {
            row(list, R.string.setup_choose_home, null, () -> open(homeSettings));
        }
        row(list, R.string.setup_later, null, () -> {
            FirstRun.prefs(this).edit().putBoolean(FirstRun.SETUP_DISMISSED, true).apply();
            finish();
        });

        setContentView(root);
        first.post(first::requestFocus);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the TV's settings with the switch on: done.
        if (FirstRun.homeReady(this)) {
            Toast.makeText(this, R.string.setup_ready, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
