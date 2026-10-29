package cr.zone.modinstaller;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;
import java.util.Locale;

public final class ModApplication extends Application {
    private static final String PREFERENCES = "interface";
    private static final String LANGUAGE = "language";

    public static String language(Context context) {
        String value = context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(LANGUAGE, "en");
        return value.equals("es") || value.equals("ru") ? value : "en";
    }

    public static Context localized(Context context) {
        Locale locale = Locale.forLanguageTag(language(context));
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocales(new LocaleList(locale));
        return context.createConfigurationContext(config);
    }

    private static void bind(Context context) {
        // Retain only an application-derived context, never an Activity.
        Context translated = localized(context.getApplicationContext());
        Text.setProvider(key -> {
            return translated.getString(MessageResources.id(key));
        });
    }

    public static void changeLanguage(Context context, String language) {
        if (!language.equals("en") && !language.equals("es") && !language.equals("ru"))
            throw new IllegalArgumentException("Unsupported language");
        context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(LANGUAGE, language).apply();
        bind(context);
        WorkService.status = Text.get("message_175");
        WorkService.error = "";
    }

    @Override public void onCreate() {
        super.onCreate();
        bind(this);
    }
}
