package net.i2p.router.web;

/**
 * Copied and modded from I2PTunnel IndexBean (GPL)
 * @author zzz
 */
public class CSSHelper extends HelperBase {
    public CSSHelper() {}
    
    public static final String PROP_THEME_NAME = "routerconsole.theme";
    public static final String DEFAULT_THEME = "light";
    public static final String BASE_THEME_PATH = "/themes/console/";
    private static final String FORCE = "classic";
    public static final String PROP_REFRESH = "routerconsole.summaryRefresh";
    public static final String DEFAULT_REFRESH = "60";

    public String getTheme(String userAgent) {
        String url = BASE_THEME_PATH;
        if (userAgent != null && userAgent.contains("MSIE")) {
            url += FORCE + "/";
        } else {
            // This is the first thing to use _context on most pages
            if (_context == null)
                throw new IllegalStateException("No contexts. This is usually because the router is either starting up or shutting down.");
            String theme = _context.getProperty(PROP_THEME_NAME, DEFAULT_THEME);
            url += theme + "/";
        }
        return url;
    }

    /** change default language for the router AND save it */
    public void setLang(String lang) {
        // Protected with nonce in css.jsi
        if (lang != null && lang.length() == 2 && !lang.equals(_context.getProperty(Messages.PROP_LANG))) {
            _context.router().setConfigSetting(Messages.PROP_LANG, lang);
            _context.router().saveConfig();
        }
    }

    /** needed for conditional css loads for zh */
    public String getLang() {
        return Messages.getLanguage(_context);
    }

    /** change refresh and save it */
    public void setRefresh(String r) {
        _context.router().setConfigSetting(PROP_REFRESH, r);
        _context.router().saveConfig();
    }

    /** @return refresh time in seconds, as a string */
    public String getRefresh() {
        return _context.getProperty(PROP_REFRESH, DEFAULT_REFRESH);
    }

    /** translate the title and display consistently */
    public String title(String s) {
         StringBuilder buf = new StringBuilder(128);
         buf.append("<title>")
            .append(_("I2P Router Console"))
            .append(" - ")
            .append(_(s))
            .append("</title>");
         return buf.toString();
    }

    /**
     *  Should we allow a refreshing IFrame?
     *  @since 0.8.5
     */
    public boolean allowIFrame(String ua) {
        return ua == null ||
                               // text
                             !(ua.startsWith("Lynx") || ua.startsWith("w3m") ||
                               ua.startsWith("ELinks") || ua.startsWith("Links") ||
                               ua.startsWith("Dillo") ||
                               // mobile
                               // http://www.zytrax.com/tech/web/mobile_ids.html
                               ua.contains("Android") || ua.contains("iPhone") ||
                               ua.contains("iPod") || ua.contains("iPad") ||
                               ua.contains("Kindle") || ua.contains("Mobile") ||
                               ua.contains("Nintendo Wii") || ua.contains("Opera Mini") ||
                               ua.contains("Palm") ||
                               ua.contains("PLAYSTATION") || ua.contains("Playstation") ||
                               ua.contains("Profile/MIDP-") || ua.contains("SymbianOS") ||
                               ua.contains("Windows CE") || ua.contains("Windows Phone") ||
                               ua.startsWith("BlackBerry") || ua.startsWith("DoCoMo") ||
                               ua.startsWith("Nokia") || ua.startsWith("OPWV-SDK") ||
                               ua.startsWith("MOT-") || ua.startsWith("SAMSUNG-") ||
                               ua.startsWith("nook") || ua.startsWith("SCH-") ||
                               ua.startsWith("SEC-") || ua.startsWith("SonyEricsson") ||
                               ua.startsWith("Vodafone"));
    }
}
