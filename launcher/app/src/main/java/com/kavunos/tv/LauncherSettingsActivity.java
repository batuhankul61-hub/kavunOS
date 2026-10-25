package com.kavunos.tv;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Outline;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings, tvOS style: the Settings artwork large on the left, the choices grouped on the right. Home's
 * Settings tile and the remote's Settings key open it; the TV's own settings are one row away.
 */
public final class LauncherSettingsActivity extends TvScreen {

    private static final int[] COLUMNS = {4, 5, 6, 7};

    private SharedPreferences choices;
    private Config config;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        config = Config.load(this);
        initScreen();
        root.setBackgroundColor(0xFF0B0B0D);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setPadding(dp(64), dp(48), dp(64), 0);
        root.addView(columns, match());

        // Left: the artwork and the page title, centred in the half.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER);
        columns.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        ImageView art = new ImageView(this);
        art.setImageResource(R.drawable.banner_settings);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(20));
            }
        });
        art.setClipToOutline(true);
        left.addView(art, new LinearLayout.LayoutParams(dp(300), dp(169)));
        TextView title = text(getString(R.string.home_settings_tile), TITLE_SP, Color.WHITE, R.font.inter_semibold);
        title.setPadding(0, dp(22), 0, dp(48));
        left.addView(title);

        // Right: the settings, scrolling on their own.
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(28));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(24), 0, dp(48));
        scroll.addView(list);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f);
        scrollParams.setMarginStart(dp(40));
        columns.addView(scroll, scrollParams);

        section(list, getString(R.string.settings_section_home));
        View first = row(list, R.string.wallpaper_manage_title, null,
                () -> startActivity(new Intent(this, WallpaperActivity.class)));
        row(list, R.string.top_shelf, () -> getString(choices.getBoolean(MainActivity.TOP_SHELF_KEY, true)
                ? R.string.on : R.string.off), () -> sheet(getString(R.string.top_shelf), labels(R.string.on, R.string.off),
                choices.getBoolean(MainActivity.TOP_SHELF_KEY, true) ? 0 : 1, i -> {
                    choices.edit().putBoolean(MainActivity.TOP_SHELF_KEY, i == 0).apply();
                    changed();
                }));
        row(list, R.string.columns_label, () -> String.valueOf(config.columns), () -> {
            List<String> labels = new ArrayList<>();
            for (int c : COLUMNS) labels.add(String.valueOf(c));
            sheet(getString(R.string.columns_label), labels, indexOf(COLUMNS, config.columns), i -> {
                choices.edit().putInt("columns", COLUMNS[i]).apply();
                changed();
            });
        });
        row(list, R.string.hidden_apps, () -> String.valueOf(HomeApps.hiddenByUser(this).size()), this::hiddenApps);
        section(list, getString(R.string.settings_section_system));
        row(list, R.string.language, () -> {
            String chosen = Language.chosen(this);
            return chosen.isEmpty() ? getString(R.string.language_system) : Language.name(chosen);
        }, this::language);
        row(list, R.string.home_settings, null, () -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        row(list, R.string.tv_settings, null, () -> open(new Intent(Settings.ACTION_SETTINGS)));
        row(list, R.string.follow_theme, null, () -> {
            Config.clearOnTvChoices(choices.edit()).commit();
            changed();
        });

        setContentView(root);
        first.post(first::requestFocus);
    }

    /** A choice changed: Home rebuilds when it comes back. */
    private void changed() {
        config = Config.load(this);
        Config.wallpaperChanged();
        setResult(RESULT_OK);
        refreshRows();
    }

    @Override
    protected void onResume() {
        super.onResume();
        config = Config.load(this);
        refreshRows();
    }

    /** The apps hidden with Home's tile menu; choosing one shows it on Home again. */
    private void hiddenApps() {
        List<String> hidden = HomeApps.hiddenByUser(this);
        if (hidden.isEmpty()) {
            Toast.makeText(this, R.string.hidden_apps_none, Toast.LENGTH_SHORT).show();
            return;
        }
        PackageManager pm = getPackageManager();
        List<String> labels = new ArrayList<>();
        for (String pkg : hidden) {
            String label;
            try {
                label = HomeApps.cleanLabel(String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))), pkg);
            } catch (PackageManager.NameNotFoundException e) {
                label = pkg;
            }
            labels.add(label);
        }
        sheet(getString(R.string.hidden_apps_pick), labels, -1, i -> {
            HomeApps.setHidden(this, hidden.get(i), false);
            changed();
        });
    }

    /** The launcher's own language, for TVs that never tell apps theirs; the first choice follows the TV. */
    private void language() {
        List<String> tags = Language.available(this);
        tags.add(0, "");
        List<String> labels = new ArrayList<>();
        for (String tag : tags) labels.add(tag.isEmpty() ? getString(R.string.language_system) : Language.name(tag));
        sheet(getString(R.string.language), labels, Math.max(0, tags.indexOf(Language.chosen(this))), i -> {
            if (tags.get(i).equals(Language.chosen(this))) return;
            choices.edit().putString(Language.KEY, tags.get(i)).apply();
            // Home's own tiles carry their names; Home itself rebuilds when it sees the new language.
            HomeApps.invalidate();
            recreate();
        });
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
