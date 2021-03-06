package org.itxtech.daedalus.util;

import org.itxtech.daedalus.Daedalus;

import java.util.Locale;

public class LanguageHelper {

    private static final String[] ids = {"fa", "en"};
    private static final String[] names = {"فارسی", "English"};

    public static String[] getIds() {
        return ids;
    }

    public static String[] getNames() {
        return names;
    }

    public static String getDescription(String key) {
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i];
            if (id.equals(key))
                return names[i];
        }
        return "";
    }

    public static String getLanguage() {
        String systemLanguage = Locale.getDefault().getLanguage();
        String defaultLanguage;
        if (systemLanguage.equals("fa"))
            defaultLanguage = "fa";
        else
            defaultLanguage = "en";
        return Daedalus.getPrefs().getString("settings_language", defaultLanguage);
    }
}
