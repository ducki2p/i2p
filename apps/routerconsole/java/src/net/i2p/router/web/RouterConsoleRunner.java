package net.i2p.router.web;

import java.util.ArrayList;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.security.KeyStore;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.apps.systray.SysTray;
import net.i2p.data.Base32;
import net.i2p.data.DataHelper;
import net.i2p.desktopgui.Main;
import net.i2p.router.RouterContext;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.ShellCommand;
import net.i2p.util.VersionComparator;

import org.mortbay.http.DigestAuthenticator;
import org.mortbay.http.HashUserRealm;
import org.mortbay.http.NCSARequestLog;
import org.mortbay.http.SecurityConstraint;
import org.mortbay.http.SocketListener;
import org.mortbay.http.SslListener;
import org.mortbay.http.handler.SecurityHandler;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.WebApplicationContext;
import org.mortbay.jetty.servlet.WebApplicationHandler;
import org.mortbay.util.InetAddrPort;

public class RouterConsoleRunner {
    private Server _server;
    private String _listenPort;
    private String _listenHost;
    private String _sslListenPort;
    private String _sslListenHost;
    private String _webAppsDir;
    private static final String PROP_WEBAPP_CONFIG_FILENAME = "router.webappsConfigFile";
    private static final String DEFAULT_WEBAPP_CONFIG_FILENAME = "webapps.config";
    private static final DigestAuthenticator authenticator = new DigestAuthenticator();
    public static final String ROUTERCONSOLE = "routerconsole";
    public static final String PREFIX = "webapps.";
    public static final String ENABLED = ".startOnLoad";
    private static final String PROP_KEYSTORE_PASSWORD = "routerconsole.keystorePassword";
    private static final String DEFAULT_KEYSTORE_PASSWORD = "changeit";
    private static final String PROP_KEY_PASSWORD = "routerconsole.keyPassword";
    private static final String DEFAULT_LISTEN_PORT = "7657";
    private static final String DEFAULT_LISTEN_HOST = "127.0.0.1";
    private static final String DEFAULT_WEBAPPS_DIR = "./webapps/";
    private static final String USAGE = "Bad RouterConsoleRunner arguments, check clientApp.0.args in your clients.config file! " +
                                        "Usage: [[port host[,host]] [-s sslPort [host[,host]]] [webAppsDir]]";
    
    static {
        System.setProperty("org.mortbay.http.Version.paranoid", "true");
    }
    
    /**
     *  <pre>
     *  non-SSL:
     *  RouterConsoleRunner
     *  RouterConsoleRunner 7657
     *  RouterConsoleRunner 7657 127.0.0.1
     *  RouterConsoleRunner 7657 127.0.0.1,::1
     *  RouterConsoleRunner 7657 127.0.0.1,::1 ./webapps/
     *
     *  SSL:
     *  RouterConsoleRunner -s 7657
     *  RouterConsoleRunner -s 7657 127.0.0.1
     *  RouterConsoleRunner -s 7657 127.0.0.1,::1
     *  RouterConsoleRunner -s 7657 127.0.0.1,::1 ./webapps/
     *
     *  If using both, non-SSL must be first:
     *  RouterConsoleRunner 7657 127.0.0.1 -s 7667
     *  RouterConsoleRunner 7657 127.0.0.1 -s 7667 127.0.0.1
     *  RouterConsoleRunner 7657 127.0.0.1,::1 -s 7667 127.0.0.1,::1
     *  RouterConsoleRunner 7657 127.0.0.1,::1 -s 7667 127.0.0.1,::1 ./webapps/
     *  </pre>
     *
     *  @param args second arg may be a comma-separated list of bind addresses,
     *              for example ::1,127.0.0.1
     *              On XP, the other order (127.0.0.1,::1) fails the IPV6 bind,
     *              because 127.0.0.1 will bind ::1 also. But even though it's bound
     *              to both, we can't connect to [::1]:7657 for some reason.
     *              So the wise choice is ::1,127.0.0.1
     */
    public RouterConsoleRunner(String args[]) {
        if (args.length == 0) {
            // _listenHost and _webAppsDir are defaulted below
            _listenPort = DEFAULT_LISTEN_PORT;
        } else {
            boolean ssl = false;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("-s"))
                    ssl = true;
                else if ((!ssl) && _listenPort == null)
                    _listenPort = args[i];
                else if ((!ssl) && _listenHost == null)
                    _listenHost = args[i];
                else if (ssl && _sslListenPort == null)
                    _sslListenPort = args[i];
                else if (ssl && _sslListenHost == null)
                    _sslListenHost = args[i];
                else if (_webAppsDir == null)
                    _webAppsDir = args[i];
                else {
                    System.err.println(USAGE);
                    throw new IllegalArgumentException(USAGE);
                }
            }
        }
        if (_listenHost == null)
           _listenHost = DEFAULT_LISTEN_HOST;
        if (_sslListenHost == null)
           _sslListenHost = _listenHost;
        if (_webAppsDir == null)
           _webAppsDir = DEFAULT_WEBAPPS_DIR;
        // _listenPort and _sslListenPort are not defaulted, if one or the other is null, do not enable
        if (_listenPort == null && _sslListenPort == null) {
            System.err.println(USAGE);
            throw new IllegalArgumentException(USAGE);
        }
    }
    
    public static void main(String args[]) {
        startTrayApp();
        RouterConsoleRunner runner = new RouterConsoleRunner(args);
        runner.startConsole();
    }
    
    private static void startTrayApp() {
        try {
            //TODO: move away from routerconsole into a separate application.
            //ApplicationManager?
            VersionComparator v = new VersionComparator();
            boolean recentJava = v.compare(System.getProperty("java.runtime.version"), "1.6") >= 0;
            // default false for now
            boolean desktopguiEnabled = I2PAppContext.getGlobalContext().getBooleanProperty("desktopgui.enabled");
            if (recentJava && desktopguiEnabled) {
                //Check if we are in a headless environment, set properties accordingly
          	System.setProperty("java.awt.headless", Boolean.toString(GraphicsEnvironment.isHeadless()));
                String[] args = new String[0];
                net.i2p.desktopgui.Main.beginStartup(args);    
            } else {
                // required true for jrobin to work
          	System.setProperty("java.awt.headless", "true");
                SysTray.getInstance();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public void startConsole() {
        File workDir = new SecureDirectory(I2PAppContext.getGlobalContext().getTempDir(), "jetty-work");
        boolean workDirRemoved = FileUtil.rmdir(workDir, false);
        if (!workDirRemoved)
            System.err.println("ERROR: Unable to remove Jetty temporary work directory");
        boolean workDirCreated = workDir.mkdirs();
        if (!workDirCreated)
            System.err.println("ERROR: Unable to create Jetty temporary work directory");
        
        // so Jetty can find WebAppConfiguration
        System.setProperty("jetty.class.path", I2PAppContext.getGlobalContext().getBaseDir() + "/lib/routerconsole.jar");
        _server = new Server();

        String log = I2PAppContext.getGlobalContext().getProperty("routerconsole.log");
        if (log != null) {
            File logFile = new File(log);
            if (!logFile.isAbsolute())
                logFile = new File(I2PAppContext.getGlobalContext().getLogDir(), "logs/" + log);
            try {
                _server.setRequestLog(new NCSARequestLog(logFile.getAbsolutePath()));
            } catch (IOException ioe) {
                System.err.println("ERROR: Unable to create Jetty log: " + ioe);
            }
        }
        boolean rewrite = false;
        Properties props = webAppProperties();
        if (props.isEmpty()) {
            props.setProperty(PREFIX + ROUTERCONSOLE + ENABLED, "true");
            rewrite = true;
        }

        // Get an absolute path with a trailing slash for the webapps dir
        // We assume relative to the base install dir for backward compatibility
        File app = new File(_webAppsDir);
        if (!app.isAbsolute()) {
            app = new File(I2PAppContext.getGlobalContext().getBaseDir(), _webAppsDir);
            try {
                _webAppsDir = app.getCanonicalPath();
            } catch (IOException ioe) {}
        }
        if (!_webAppsDir.endsWith("/"))
            _webAppsDir += '/';

        List<String> notStarted = new ArrayList();
        WebApplicationHandler baseHandler = null;
        try {
            int boundAddresses = 0;

            // add standard listeners
            if (_listenPort != null) {
                StringTokenizer tok = new StringTokenizer(_listenHost, " ,");
                while (tok.hasMoreTokens()) {
                    String host = tok.nextToken().trim();
                    try {
                        //if (host.indexOf(":") >= 0) // IPV6 - requires patched Jetty 5
                        //    _server.addListener('[' + host + "]:" + _listenPort);
                        //else
                        //    _server.addListener(host + ':' + _listenPort);
                        Integer lport = Integer.parseInt(_listenPort);
                        InetAddrPort iap = new InetAddrPort(host, lport);
                        SocketListener lsnr = new SocketListener(iap);
                        lsnr.setMinThreads(1);           // default 2
                        lsnr.setMaxThreads(24);          // default 256
                        lsnr.setMaxIdleTimeMs(90*1000);  // default 10 sec
                        lsnr.setName("ConsoleSocket");   // all with same name will use the same thread pool
                        _server.addListener(lsnr);
                        boundAddresses++;
                    } catch (NumberFormatException nfe) {
                        System.err.println("Unable to bind routerconsole to " + host + " port " + _listenPort + ' ' + nfe);
                    } catch (IOException ioe) { // this doesn't seem to work, exceptions don't happen until start() below
                        System.err.println("Unable to bind routerconsole to " + host + " port " + _listenPort + ' ' + ioe);
                    }
                }
            }

            // add SSL listeners
            int sslPort = 0;
            if (_sslListenPort != null) {
                try {
                    sslPort = Integer.parseInt(_sslListenPort);
                } catch (NumberFormatException nfe) {}
                if (sslPort <= 0)
                    System.err.println("Bad routerconsole SSL port " + _sslListenPort);
            }
            if (sslPort > 0) {
                I2PAppContext ctx = I2PAppContext.getGlobalContext();
                File keyStore = new File(ctx.getConfigDir(), "keystore/console.ks");
                if (verifyKeyStore(keyStore)) {
                    StringTokenizer tok = new StringTokenizer(_sslListenHost, " ,");
                    while (tok.hasMoreTokens()) {
                        String host = tok.nextToken().trim();
                        // doing it this way means we don't have to escape an IPv6 host with []
                        InetAddrPort iap = new InetAddrPort(host, sslPort);
                        try {
                            SslListener ssll = new SslListener(iap);
                            // the keystore path and password
                            ssll.setKeystore(keyStore.getAbsolutePath());
                            ssll.setPassword(ctx.getProperty(PROP_KEYSTORE_PASSWORD, DEFAULT_KEYSTORE_PASSWORD));
                            // the X.509 cert password (if not present, verifyKeyStore() returned false)
                            ssll.setKeyPassword(ctx.getProperty(PROP_KEY_PASSWORD, "thisWontWork"));
                            ssll.setMinThreads(1);           // default 2
                            ssll.setMaxThreads(24);          // default 256
                            ssll.setMaxIdleTimeMs(90*1000);  // default 10 sec
                            ssll.setName("ConsoleSocket");   // all with same name will use the same thread pool
                            _server.addListener(ssll);
                            boundAddresses++;
                        } catch (Exception e) {   // probably no exceptions at this point
                            System.err.println("Unable to bind routerconsole to " + host + " port " + sslPort + " for SSL: " + e);
                        }
                    }
                } else {
                    System.err.println("Unable to create or access keystore for SSL: " + keyStore.getAbsolutePath());
                }
            }

            if (boundAddresses <= 0) {
                System.err.println("Unable to bind routerconsole to any address on port " + _listenPort + (sslPort > 0 ? (" or SSL port " + sslPort) : ""));
                return;
            }
            _server.setRootWebApp(ROUTERCONSOLE);
            WebApplicationContext wac = _server.addWebApplication("/", _webAppsDir + ROUTERCONSOLE + ".war");
            File tmpdir = new SecureDirectory(workDir, ROUTERCONSOLE + "-" +
                                                       (_listenPort != null ? _listenPort : _sslListenPort));
            tmpdir.mkdir();
            wac.setTempDirectory(tmpdir);
            baseHandler = new LocaleWebAppHandler(I2PAppContext.getGlobalContext());
            wac.addHandler(0, baseHandler);
            initialize(wac);
            File dir = new File(_webAppsDir);
            String fileNames[] = dir.list(WarFilenameFilter.instance());
            if (fileNames != null) {
                for (int i = 0; i < fileNames.length; i++) {
                    try {
                        String appName = fileNames[i].substring(0, fileNames[i].lastIndexOf(".war"));
                        String enabled = props.getProperty(PREFIX + appName + ENABLED);
                        if (! "false".equals(enabled)) {
                            String path = new File(dir, fileNames[i]).getCanonicalPath();
                            tmpdir = new SecureDirectory(workDir, appName + "-" +
                                                                  (_listenPort != null ? _listenPort : _sslListenPort));
                            WebAppStarter.addWebApp(I2PAppContext.getGlobalContext(), _server, appName, path, tmpdir);

                            if (enabled == null) {
                                // do this so configclients.jsp knows about all apps from reading the config
                                props.setProperty(PREFIX + appName + ENABLED, "true");
                                rewrite = true;
                            }
                        } else {
                            notStarted.add(appName);
                        }
                    } catch (IOException ioe) {
                        System.err.println("Error resolving '" + fileNames[i] + "' in '" + dir);
                    }
                }
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        if (rewrite)
            storeWebAppProperties(props);
        try {
            _server.start();
        } catch (Throwable me) {
            // NoClassFoundDefError from a webapp is a throwable, not an exception
            System.err.println("WARNING: Error starting one or more listeners of the Router Console server.\n" +
                               "If your console is still accessible at http://127.0.0.1:7657/,\n" +
                               "this may be a problem only with binding to the IPV6 address ::1.\n" +
                               "If so, you may ignore this error, or remove the\n" +
                               "\"::1,\" in the \"clientApp.0.args\" line of the clients.config file.\n" +
                               "Exception: " + me);
            me.printStackTrace();
        }

        if (baseHandler != null) {
            // map each not-started webapp to the error page
            for (int i = 0; i < notStarted.size(); i++) {
                try {
                     baseHandler.mapPathToServlet('/' + notStarted.get(i) + "/*",
                                                  "net.i2p.router.web.jsp.nowebapp_jsp");
                } catch (Throwable me) {
                     System.err.println(me);
                }
            }
        }

        NewsFetcher fetcher = NewsFetcher.getInstance(I2PAppContext.getGlobalContext());
        Thread t = new I2PAppThread(fetcher, "NewsFetcher", true);
        t.start();
        
        t = new I2PAppThread(new StatSummarizer(), "StatSummarizer", true);
        t.start();
        
        List<RouterContext> contexts = RouterContext.listContexts();
        if (contexts != null) {
            RouterContext ctx = contexts.get(0);
            if (PluginStarter.pluginsEnabled(ctx)) {
                t = new I2PAppThread(new PluginStarter(ctx), "PluginStarter", true);
                t.start();
                ctx.addShutdownTask(new PluginStopper(ctx));
            }
        }
    }
    
    /**
     * @return success if it exists and we have a password, or it was created successfully.
     * @since 0.8.3
     */
    private static boolean verifyKeyStore(File ks) {
        if (ks.exists()) {
            I2PAppContext ctx = I2PAppContext.getGlobalContext();
            boolean rv = ctx.getProperty(PROP_KEY_PASSWORD) != null;
            if (!rv)
                System.err.println("Console SSL error, must set " + PROP_KEY_PASSWORD + " in " + (new File(ctx.getConfigDir(), "router.config")).getAbsolutePath());
            return rv;
        }
        File dir = ks.getParentFile();
        if (!dir.exists()) {
            File sdir = new SecureDirectory(dir.getAbsolutePath());
            if (!sdir.mkdir())
                return false;
        }
        return createKeyStore(ks);
    }


    /**
     * Call out to keytool to create a new keystore with a keypair in it.
     * Trying to do this programatically is a nightmare, requiring either BouncyCastle
     * libs or using proprietary Sun libs, and it's a huge mess.
     *
     * @return success
     * @since 0.8.3
     */
    private static boolean createKeyStore(File ks) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        // make a random 48 character password (30 * 8 / 5)
        byte[] rand = new byte[30];
        ctx.random().nextBytes(rand);
        String keyPassword = Base32.encode(rand);
        // and one for the cname
        ctx.random().nextBytes(rand);
        String cname = Base32.encode(rand) + ".console.i2p.net";

        String keytool = (new File(System.getProperty("java.home"), "bin/keytool")).getAbsolutePath();
        String[] args = new String[] {
                   keytool,
                   "-genkey",            // -genkeypair preferred in newer keytools, but this works with more
                   "-storetype", KeyStore.getDefaultType(),
                   "-keystore", ks.getAbsolutePath(),
                   "-storepass", DEFAULT_KEYSTORE_PASSWORD,
                   "-alias", "console",
                   "-dname", "CN=" + cname + ",OU=Console,O=I2P Anonymous Network,L=XX,ST=XX,C=XX",
                   "-validity", "3652",  // 10 years
                   "-keyalg", "DSA",
                   "-keysize", "1024",
                   "-keypass", keyPassword};
        boolean success = (new ShellCommand()).executeSilentAndWaitTimed(args, 30);  // 30 secs
        if (success) {
            success = ks.exists();
            if (success) {
                SecureFileOutputStream.setPerms(ks);
                try {
                    RouterContext rctx = (RouterContext) ctx;
                    rctx.router().setConfigSetting(PROP_KEYSTORE_PASSWORD, DEFAULT_KEYSTORE_PASSWORD);
                    rctx.router().setConfigSetting(PROP_KEY_PASSWORD, keyPassword);
                    rctx.router().saveConfig();
                } catch (Exception e) {}  // class cast exception
            }
        }
        if (success) {
            System.err.println("Created self-signed certificate for " + cname + " in keystore: " + ks.getAbsolutePath() + "\n" +
                               "The certificate name was generated randomly, and is not associated with your " +
                               "IP address, host name, router identity, or destination keys.");
        } else {
            System.err.println("Failed to create console SSL keystore using command line:");
            StringBuilder buf = new StringBuilder(256);
            for (int i = 0;  i < args.length; i++) {
                buf.append('"').append(args[i]).append("\" ");
            }
            System.err.println(buf.toString());
            System.err.println("This is for the Sun/Oracle keytool, others may be incompatible.\n" +
                               "If you create the keystore manually, you must add " + PROP_KEYSTORE_PASSWORD + " and " + PROP_KEY_PASSWORD +
                               " to " + (new File(ctx.getConfigDir(), "router.config")).getAbsolutePath());
        }
        return success;
    }

    static void initialize(WebApplicationContext context) {
        String password = getPassword();
        if (password != null) {
            HashUserRealm realm = new HashUserRealm("i2prouter");
            realm.put("admin", password);
            realm.addUserToRole("admin", "routerAdmin");
            context.setRealm(realm);
            context.setAuthenticator(authenticator);
            context.addHandler(0, new SecurityHandler());
            SecurityConstraint constraint = new SecurityConstraint("admin", "routerAdmin");
            constraint.setAuthenticate(true);
            context.addSecurityConstraint("/", constraint);
        }

        // This forces a '403 Forbidden' response for TRACE and OPTIONS unless the
        // WAC handler handles it.
        // (LocaleWebAppHandler returns a '405 Method Not Allowed')
        // TRACE and OPTIONS aren't really security issues...
        // TRACE doesn't echo stuff unless you call setTrace(true)
        // But it might bug some people
        // The other strange methods - PUT, DELETE, MOVE - are disabled by default
        // See also:
        // http://old.nabble.com/Disable-HTTP-TRACE-in-Jetty-5.x-td12412607.html
        SecurityConstraint sc = new SecurityConstraint();
        sc.setName("No trace or options");
        sc.addMethod("TRACE");
        sc.addMethod("OPTIONS");
        sc.setAuthenticate(true);
        context.addSecurityConstraint("/*", sc) ;
    }
    
    static String getPassword() {
        List<RouterContext> contexts = RouterContext.listContexts();
        if (contexts != null) {
            for (int i = 0; i < contexts.size(); i++) {
                RouterContext ctx = contexts.get(i);
                String password = ctx.getProperty("consolePassword");
                if (password != null) {
                    password = password.trim();
                    if (password.length() > 0) {
                        return password;
                    }
                }
            }
            // no password in any context
            return null;
        } else {
            // no contexts?!
            return null;
        }
    }
    
/*******
    public void stopConsole() {
        try {
            _server.stop();
        } catch (InterruptedException ie) {
            ie.printStackTrace();
        }
    }
********/
    
    public static Properties webAppProperties() {
        return webAppProperties(I2PAppContext.getGlobalContext().getConfigDir().getAbsolutePath());
    }

    public static Properties webAppProperties(String dir) {
        Properties rv = new Properties();
        // String webappConfigFile = ctx.getProperty(PROP_WEBAPP_CONFIG_FILENAME, DEFAULT_WEBAPP_CONFIG_FILENAME);
        String webappConfigFile = DEFAULT_WEBAPP_CONFIG_FILENAME;
        File cfgFile = new File(dir, webappConfigFile);
        
        try {
            DataHelper.loadProps(rv, cfgFile);
        } catch (IOException ioe) {
            // _log.warn("Error loading the client app properties from " + cfgFile.getName(), ioe);
        }
        
        return rv;
    }

    public static void storeWebAppProperties(Properties props) {
        // String webappConfigFile = ctx.getProperty(PROP_WEBAPP_CONFIG_FILENAME, DEFAULT_WEBAPP_CONFIG_FILENAME);
        String webappConfigFile = DEFAULT_WEBAPP_CONFIG_FILENAME;
        File cfgFile = new File(I2PAppContext.getGlobalContext().getConfigDir(), webappConfigFile);
        
        try {
            DataHelper.storeProps(props, cfgFile);
        } catch (IOException ioe) {
            // _log.warn("Error loading the client app properties from " + cfgFile.getName(), ioe);
        }
    }

    static class WarFilenameFilter implements FilenameFilter {
        private static final WarFilenameFilter _filter = new WarFilenameFilter();
        public static WarFilenameFilter instance() { return _filter; }
        public boolean accept(File dir, String name) {
            return (name != null) && (name.endsWith(".war") && !name.equals(ROUTERCONSOLE + ".war"));
        }
    }

}
