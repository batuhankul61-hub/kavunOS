package com.kavunos.tv;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.LocaleList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The launcher's own language, chosen in Settings. Some TVs show their menus in a language they never pass on to
 * apps, so the launcher can be told directly. Empty means the TV's language. Every activity and dream wraps its
 * base context with {@link #wrap}; the date and clock follow through the process default locale.
 */
final class Language {

    static final String KEY = "language";

    /** The TV's own locales, kept before {@link #wrap} changes the process default. */
    private static final LocaleList SYSTEM = Resources.getSystem().getConfiguration().getLocales();

    private Language() {
    }

    static String chosen(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString(KEY, "");
    }

    /** For a dream or service: its base context in the chosen language. */
    static Context wrap(Context base) {
        Configuration override = override(base);
        return override == null ? base : base.createConfigurationContext(override);
    }

    /** For an activity, from attachBaseContext: an override that also holds through its own configuration changes. */
    static void apply(Activity activity) {
        Configuration override = override(activity);
        if (override != null) activity.applyOverrideConfiguration(override);
    }

    /** Only the locale, so the rest still follows the TV; null when the TV's language is used. */
    private static Configuration override(Context context) {
        String tag = chosen(context);
        LocaleList locales = tag.isEmpty() ? SYSTEM : new LocaleList(Locale.forLanguageTag(tag));
        Locale.setDefault(locales.get(0));
        if (tag.isEmpty()) return null;
        Configuration config = new Configuration();
        config.setLocales(locales);
        return config;
    }

    /** The translations the launcher ships, from res/xml/locales_config.xml, as language tags. */
    static List<String> available(Context context) {
        List<String> tags = new ArrayList<>();
        try (XmlResourceParser xml = context.getResources().getXml(R.xml.locales_config)) {
            for (int e = xml.next(); e != XmlResourceParser.END_DOCUMENT; e = xml.next()) {
                if (e != XmlResourceParser.START_TAG || !"locale".equals(xml.getName())) continue;
                String tag = xml.getAttributeValue("http://schemas.android.com/apk/res/android", "name");
                if (tag != null) tags.add(tag);
            }
        } catch (Exception ignored) {
            // A broken list only leaves the choice shorter.
        }
        return tags;
    }

    /** A language's name in that language, e.g. "Türkçe", so it can be found whatever is on screen now. */
    static String name(String tag) {
        Locale locale = Locale.forLanguageTag(tag);
        String name = locale.getDisplayName(locale);
        return name.isEmpty() ? tag : name.substring(0, 1).toUpperCase(locale) + name.substring(1);
    }
}
