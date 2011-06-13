package net.i2p.router.web;

import java.io.File;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.Set;

public class ConfigUIHelper extends HelperBase {

    public String getSettings() {
        StringBuilder buf = new StringBuilder(512);
        String current = _context.getProperty(CSSHelper.PROP_THEME_NAME, CSSHelper.DEFAULT_THEME);
        Set<String> themes = themeSet();
        for (String theme : themes) {
            buf.append("<input type=\"radio\" class=\"optbox\" name=\"theme\" ");
            if (theme.equals(current))
                buf.append("checked=\"true\" ");
            buf.append("value=\"").append(theme).append("\">").append(_(theme)).append("<br>\n");
        }
        return buf.toString();
    }

    static final String PROP_THEME_PFX = "routerconsole.theme.";

    /** @return standard and user-installed themes, sorted (untranslated) */
    private Set<String> themeSet() {
         Set<String> rv = new TreeSet();
         // add a failsafe even if we can't find any themes
         rv.add(CSSHelper.DEFAULT_THEME);
         File dir = new File(_context.getBaseDir(), "docs/themes/console");
         File[] files = dir.listFiles();
         if (files == null)
             return rv;
         for (int i = 0; i < files.length; i++) {
             String name = files[i].getName();
             if (files[i].isDirectory() && ! name.equals("images"))
                 rv.add(name);
         }
         // user themes
         Set props = _context.getPropertyNames();
         for (Iterator iter = props.iterator(); iter.hasNext(); ) {
              String prop = (String) iter.next();
              if (prop.startsWith(PROP_THEME_PFX) && prop.length() > PROP_THEME_PFX.length())
                  rv.add(prop.substring(PROP_THEME_PFX.length()));
         }
         return rv;
    }

    private static final String langs[] = {"ar", "de", "en", "es", "fi", "fr", "it", "nl", "pl", "pt", "ru",
                                           "sv", "vi", "zh"};
    private static final String flags[] = {"lang_ar", "de", "us", "es", "fi", "fr", "it", "nl", "pl", "pt", "ru",
                                           "se", "vn", "cn"};
    private static final String xlangs[] = {_x("Arabic"),
                                            _x("German"), _x("English"), _x("Spanish"),_x("Finnish"),
                                            _x("French"), _x("Italian"), _x("Dutch"), _x("Polish"),
                                            _x("Portuguese"), _x("Russian"), _x("Swedish"),
                                            _x("Vietnamese"), _x("Chinese")};

    /** todo sort by translated string */
    public String getLangSettings() {
        StringBuilder buf = new StringBuilder(512);
        String current = Messages.getLanguage(_context);
        for (int i = 0; i < langs.length; i++) {
            // we use "lang" so it is set automagically in CSSHelper
            buf.append("<input type=\"radio\" class=\"optbox\" name=\"lang\" ");
            if (langs[i].equals(current))
                buf.append("checked=\"true\" ");
            buf.append("value=\"").append(langs[i]).append("\">")
               .append("<img height=\"11\" width=\"16\" alt=\"\" src=\"/flags.jsp?c=").append(flags[i]).append("\"> ")
               .append(_(xlangs[i])).append("<br>\n");
        }
        return buf.toString();
    }
}
