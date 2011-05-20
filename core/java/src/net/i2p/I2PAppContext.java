package net.i2p;

import java.io.File;
import java.util.HashSet;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import net.i2p.client.naming.NamingService;
import net.i2p.client.naming.PetNameDB;
import net.i2p.crypto.AESEngine;
import net.i2p.crypto.CryptixAESEngine;
import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.DummyDSAEngine;
import net.i2p.crypto.DummyElGamalEngine;
//import net.i2p.crypto.DummyPooledRandomSource;
import net.i2p.crypto.ElGamalAESEngine;
import net.i2p.crypto.ElGamalEngine;
import net.i2p.crypto.HMAC256Generator;
import net.i2p.crypto.HMACGenerator;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.SHA256Generator;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.crypto.TransientSessionKeyManager;
import net.i2p.data.Base64;
import net.i2p.data.RoutingKeyGenerator;
import net.i2p.internal.InternalClientManager;
import net.i2p.stat.StatManager;
import net.i2p.util.Clock;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.FileUtil;
import net.i2p.util.FortunaRandomSource;
import net.i2p.util.I2PProperties;
import net.i2p.util.KeyRing;
import net.i2p.util.LogManager;
//import net.i2p.util.PooledRandomSource;
import net.i2p.util.RandomSource;
import net.i2p.util.SecureDirectory;
import net.i2p.util.I2PProperties.I2PPropertyCallback;

/**
 * <p>Provide a base scope for accessing singletons that I2P exposes.  Rather than
 * using the traditional singleton, where any component can access the component
 * in question directly, all of those I2P related singletons are exposed through
 * a particular I2PAppContext.  This helps not only with understanding their use
 * and the components I2P exposes, but it also allows multiple isolated 
 * environments to operate concurrently within the same JVM - particularly useful
 * for stubbing out implementations of the rooted components and simulating the
 * software's interaction between multiple instances.</p>
 *
 * As a simplification, there is also a global context - if some component needs
 * access to one of the singletons but doesn't have its own context from which
 * to root itself, it binds to the I2PAppContext's globalAppContext(), which is
 * the first context that was created within the JVM, or a new one if no context
 * existed already.  This functionality is often used within the I2P core for 
 * logging - e.g. <pre>
 *     private static final Log _log = new Log(someClass.class);
 * </pre>
 * It is for this reason that applications that care about working with multiple
 * contexts should build their own context as soon as possible (within the main(..))
 * so that any referenced components will latch on to that context instead of 
 * instantiating a new one.  However, there are situations in which both can be
 * relevent.
 *
 */
public class I2PAppContext {
    /** the context that components without explicit root are bound */
    protected static volatile I2PAppContext _globalAppContext;
    
    protected I2PProperties _overrideProps;
    
    private StatManager _statManager;
    private SessionKeyManager _sessionKeyManager;
    private NamingService _namingService;
    private PetNameDB _petnameDb;
    private ElGamalEngine _elGamalEngine;
    private ElGamalAESEngine _elGamalAESEngine;
    private AESEngine _AESEngine;
    private LogManager _logManager;
    private HMACGenerator _hmac;
    private HMAC256Generator _hmac256;
    private SHA256Generator _sha;
    protected Clock _clock; // overridden in RouterContext
    private DSAEngine _dsa;
    private RoutingKeyGenerator _routingKeyGenerator;
    private RandomSource _random;
    private KeyGenerator _keyGenerator;
    protected KeyRing _keyRing; // overridden in RouterContext
    private volatile boolean _statManagerInitialized;
    private volatile boolean _sessionKeyManagerInitialized;
    private volatile boolean _namingServiceInitialized;
    private volatile boolean _petnameDbInitialized;
    private volatile boolean _elGamalEngineInitialized;
    private volatile boolean _elGamalAESEngineInitialized;
    private volatile boolean _AESEngineInitialized;
    private volatile boolean _logManagerInitialized;
    private volatile boolean _hmacInitialized;
    private volatile boolean _hmac256Initialized;
    private volatile boolean _shaInitialized;
    protected volatile boolean _clockInitialized; // used in RouterContext
    private volatile boolean _dsaInitialized;
    private volatile boolean _routingKeyGeneratorInitialized;
    private volatile boolean _randomInitialized;
    private volatile boolean _keyGeneratorInitialized;
    protected volatile boolean _keyRingInitialized; // used in RouterContext
    private Set<Runnable> _shutdownTasks;
    private File _baseDir;
    private File _configDir;
    private File _routerDir;
    private File _pidDir;
    private File _logDir;
    private File _appDir;
    private File _tmpDir;
    
    
    /**
     * Pull the default context, creating a new one if necessary, else using 
     * the first one created.
     *
     */
    public static I2PAppContext getGlobalContext() { 
        // skip the global lock - _gAC must be volatile
        // http://www.cs.umd.edu/~pugh/java/memoryModel/DoubleCheckedLocking.html
        I2PAppContext rv = _globalAppContext;
        if (rv != null)
            return rv;

        synchronized (I2PAppContext.class) {
            if (_globalAppContext == null) {
                _globalAppContext = new I2PAppContext(false, null);
            }
        }
        return _globalAppContext; 
    }
    
    /**
     * Pull the default context, WITHOUT creating a new one.
     * Use this in static methods used early in router initialization,
     * where creating a context messes things up.
     *
     * @return context or null
     * @since 0.8.2
     */
    public static I2PAppContext getCurrentContext() { 
        return _globalAppContext; 
    }
    
    /**
     * Lets root a brand new context
     *
     */
    public I2PAppContext() {
        this(true, null);
    }
    
    /**
     * Lets root a brand new context
     *
     */
    public I2PAppContext(Properties envProps) {
        this(true, envProps);
    }
    
    /**
     * @param doInit should this context be used as the global one (if necessary)?
     */
    private I2PAppContext(boolean doInit, Properties envProps) {
        if (doInit) {
            synchronized (I2PAppContext.class) { 
                if (_globalAppContext == null)
                    _globalAppContext = this;
            }
        }
        _overrideProps = new I2PProperties();
        if (envProps != null)
            _overrideProps.putAll(envProps);
        _statManager = null;
        _sessionKeyManager = null;
        _namingService = null;
        _petnameDb = null;
        _elGamalEngine = null;
        _elGamalAESEngine = null;
        _logManager = null;
        _keyRing = null;
        _statManagerInitialized = false;
        _sessionKeyManagerInitialized = false;
        _namingServiceInitialized = false;
        _elGamalEngineInitialized = false;
        _elGamalAESEngineInitialized = false;
        _logManagerInitialized = false;
        _keyRingInitialized = false;
        _shutdownTasks = new ConcurrentHashSet(0);
        initializeDirs();
    }
    
   /**
    *  Directories. These are all set at instantiation and will not be changed by
    *  subsequent property changes.
    *  All properties, if set, should be absolute paths.
    *
    *  Name	Property 	Method		Files
    *  -----	-------- 	-----		-----
    *  Base	i2p.dir.base	getBaseDir()	lib/, webapps/, docs/, geoip/, licenses/, ...
    *  Temp	i2p.dir.temp	getTempDir()	Temporary files
    *  Config	i2p.dir.config	getConfigDir()	*.config, hosts.txt, addressbook/, ...
    *  PID	i2p.dir.pid	getPIDDir()	router.ping
    *
    *  (the following all default to the same as Config)
    *
    *  Router	i2p.dir.router	getRouterDir()	netDb/, peerProfiles/, router.*, keyBackup/, ...
    *  Log	i2p.dir.log	getLogDir()	logs/
    *  App	i2p.dir.app	getAppDir()	eepsite/, ...
    *
    *  Note that we can't control where the wrapper puts its files.
    *
    *  The app dir is where all data files should be. Apps should always read and write files here,
    *  using a constructor such as:
    *
    *       String path = mypath;
    *       File f = new File(path);
    *       if (!f.isAbsolute())
    *           f = new File(_context.geAppDir(), path);
    *
    *  and never attempt to access files in the CWD using
    *
    *       File f = new File("foo");
    *
    *  An app should assume the CWD is not writable.
    *
    *  Here in I2PAppContext, all the dirs default to CWD.
    *  However these will be different in RouterContext, as Router.java will set
    *  the properties in the RouterContext constructor.
    *
    *  Apps should never need to access the base dir, which is the location of the base I2P install.
    *  However this is provided for the router's use, and for backward compatibility should an app
    *  need to look there as well.
    *
    *  All dirs except the base are created if they don't exist, but the creation will fail silently.
    *  @since 0.7.6
    */
    private void initializeDirs() {
        String s = getProperty("i2p.dir.base", System.getProperty("user.dir"));
        _baseDir = new File(s);
        // config defaults to base
        s = getProperty("i2p.dir.config");
        if (s != null) {
            _configDir = new SecureDirectory(s);
            if (!_configDir.exists())
                _configDir.mkdir();
        } else {
            _configDir = _baseDir;
        }
        // router defaults to config
        s = getProperty("i2p.dir.router");
        if (s != null) {
            _routerDir = new SecureDirectory(s);
            if (!_routerDir.exists())
                _routerDir.mkdir();
        } else {
            _routerDir = _configDir;
        }
        // pid defaults to system temp directory
        s = getProperty("i2p.dir.pid", System.getProperty("java.io.tmpdir"));
        _pidDir = new File(s);
        if (!_pidDir.exists())
            _pidDir.mkdir();
        // these all default to router
        s = getProperty("i2p.dir.log");
        if (s != null) {
            _logDir = new SecureDirectory(s);
            if (!_logDir.exists())
                _logDir.mkdir();
        } else {
            _logDir = _routerDir;
        }
        s = getProperty("i2p.dir.app");
        if (s != null) {
            _appDir = new SecureDirectory(s);
            if (!_appDir.exists())
                _appDir.mkdir();
        } else {
            _appDir = _routerDir;
        }
        /******
        (new Exception("Initialized by")).printStackTrace();
        System.err.println("Base directory:   " + _baseDir.getAbsolutePath());
        System.err.println("Config directory: " + _configDir.getAbsolutePath());
        System.err.println("Router directory: " + _routerDir.getAbsolutePath());
        System.err.println("App directory:    " + _appDir.getAbsolutePath());
        System.err.println("Log directory:    " + _logDir.getAbsolutePath());
        System.err.println("PID directory:    " + _pidDir.getAbsolutePath());
        System.err.println("Temp directory:   " + getTempDir().getAbsolutePath());
        ******/
    }

    /**
     *  This is the installation dir, often referred to as $I2P.
     *  Applilcations should consider this directory read-only and never
     *  attempt to write to it.
     *  It may actually be read-only on a multi-user installation.
     *  The config files in this directory are templates for user
     *  installations and should not be accessed by applications.
     *  The only thing that may be useful in here is the lib/ dir
     *  containing the .jars.
     *  @since 0.7.6
     *  @return dir constant for the life of the context
     */
    public File getBaseDir() { return _baseDir; }

    /**
     *  The base dir for config files.
     *  Applications may use this to access router configuration files if necessary.
     *  Usually ~/.i2p on Linux and %APPDIR%\I2P on Windows.
     *  In installations originally installed with 0.7.5 or earlier, and in
     *  "portable" installations, this will be the same as the base dir.
     *  @since 0.7.6
     *  @return dir constant for the life of the context
     */
    public File getConfigDir() { return _configDir; }

    /**
     *  Where the router keeps its files.
     *  Applications should not use this.
     *  The same as the config dir for now.
     *  @since 0.7.6
     *  @return dir constant for the life of the context
     */
    public File getRouterDir() { return _routerDir; }

    /**
     *  Where router.ping goes.
     *  Applications should not use this.
     *  The same as the system temp dir for now.
     *  Which is a problem for multi-user installations.
     *  @since 0.7.6
     *  @return dir constant for the life of the context
     */
    public File getPIDDir() { return _pidDir; }

    /**
     *  Where the router keeps its log directory.
     *  Applications should not use this.
     *  The same as the config dir for now.
     *  (i.e. ~/.i2p, NOT ~/.i2p/logs)
     *  @since 0.7.6
     *  @return dir constant for the life of the context
     */
    public File getLogDir() { return _logDir; }

    /**
     *  Where applications may store data.
     *  The same as the config dir for now, but may change in the future.
     *  Apps should be careful not to overwrite router files.
     *  @since 0.7.6
     *  @return dir constant for the life of the context
     */
    public File getAppDir() { return _appDir; }

    /**
     *  Where anybody may store temporary data.
     *  This is a directory created in the system temp dir on the
     *  first call in this context, and is deleted on JVM exit.
     *  Applications should create their own directory inside this directory
     *  to avoid collisions with other apps.
     *  @since 0.7.6
     *  @return dir constant for the life of the context
     */
    public File getTempDir() {
        // fixme don't synchronize every time
        synchronized (this) {
            if (_tmpDir == null) {
                String d = getProperty("i2p.dir.temp", System.getProperty("java.io.tmpdir"));
                // our random() probably isn't warmed up yet
                byte[] rand = new byte[6];
                (new Random()).nextBytes(rand);
                String f = "i2p-" + Base64.encode(rand) + ".tmp";
                _tmpDir = new SecureDirectory(d, f);
                if (_tmpDir.exists()) {
                    // good or bad ? loop and try again?
                } else if (_tmpDir.mkdir()) {
                    _tmpDir.deleteOnExit();
                } else {
                    System.err.println("Could not create temp dir " + _tmpDir.getAbsolutePath());
                    _tmpDir = new SecureDirectory(_routerDir, "tmp");
                    _tmpDir.mkdir();
                }
            }
        }
        return _tmpDir;
    }

    /** don't rely on deleteOnExit() */
    public void deleteTempDir() {
        synchronized (this) {
            if (_tmpDir != null) {
                FileUtil.rmdir(_tmpDir, false);
                _tmpDir = null;
            }
        }
    }

    /**
     * Access the configuration attributes of this context, using properties 
     * provided during the context construction, or falling back on 
     * System.getProperty if no properties were provided during construction
     * (or the specified prop wasn't included).
     *
     */
    public String getProperty(String propName) {
        if (_overrideProps != null) {
            if (_overrideProps.containsKey(propName))
                return _overrideProps.getProperty(propName);
        }
        return System.getProperty(propName);
    }

    /**
     * Access the configuration attributes of this context, using properties 
     * provided during the context construction, or falling back on 
     * System.getProperty if no properties were provided during construction
     * (or the specified prop wasn't included).
     *
     */
    public String getProperty(String propName, String defaultValue) {
        if (_overrideProps != null) {
            if (_overrideProps.containsKey(propName))
                return _overrideProps.getProperty(propName, defaultValue);
        }
        return System.getProperty(propName, defaultValue);
    }

    /**
     * Return an int with an int default
     */
    public int getProperty(String propName, int defaultVal) {
        String val = null;
        if (_overrideProps != null) {
            val = _overrideProps.getProperty(propName);
            if (val == null)
                val = System.getProperty(propName);
        }
        int ival = defaultVal;
        if (val != null) {
            try {
                ival = Integer.parseInt(val);
            } catch (NumberFormatException nfe) {}
        }
        return ival;
    }

    /**
     * Return a boolean with a boolean default
     * @since 0.7.12
     */
    public boolean getProperty(String propName, boolean defaultVal) {
        String val = getProperty(propName);
        if (val == null)
            return defaultVal;
        return Boolean.valueOf(val).booleanValue();
    }

    /**
     * Default false
     * @since 0.7.12
     */
    public boolean getBooleanProperty(String propName) {
        return Boolean.valueOf(getProperty(propName)).booleanValue();
    }

    /**
     * @since 0.7.12
     */
    public boolean getBooleanPropertyDefaultTrue(String propName) {
        return getProperty(propName, true);
    }

    /**
     * Access the configuration attributes of this context, listing the properties 
     * provided during the context construction, as well as the ones included in
     * System.getProperties.
     *
     * WARNING - not overridden in RouterContext, doesn't contain router config settings,
     * use getProperties() instead.
     *
     * @return set of Strings containing the names of defined system properties
     */
    public Set getPropertyNames() { 
        Set names = new HashSet(System.getProperties().keySet());
        if (_overrideProps != null)
            names.addAll(_overrideProps.keySet());
        return names;
    }
    
    /**
     * Access the configuration attributes of this context, listing the properties 
     * provided during the context construction, as well as the ones included in
     * System.getProperties.
     *
     * @return new Properties with system and context properties
     * @since 0.8.4
     */
    public Properties getProperties() { 
        Properties rv = new Properties();
        rv.putAll(System.getProperties());
        rv.putAll(_overrideProps);
        return rv;
    }
    
    /**
     * Add a callback, which will fire upon changes in the property
     * given in the specific callback.
     * Unimplemented in I2PAppContext: this only makes sense in a router context.
     * @param callback The implementation of the callback.
     */
    public void addPropertyCallback(I2PPropertyCallback callback) {}
    
    /**
     * The statistics component with which we can track various events
     * over time.
     */
    public StatManager statManager() { 
        if (!_statManagerInitialized)
            initializeStatManager();
        return _statManager;
    }

    private void initializeStatManager() {
        synchronized (this) {
            if (_statManager == null)
                _statManager = new StatManager(this);
            _statManagerInitialized = true;
        }
    }
    
    /**
     * The session key manager which coordinates the sessionKey / sessionTag
     * data.  This component allows transparent operation of the 
     * ElGamal/AES+SessionTag algorithm, and contains all of the session tags
     * for one particular application.
     *
     * This is deprecated for client use, it should be used only by the router
     * as its own key manager. Not that clients are doing end-to-end crypto anyway.
     *
     * For client crypto within the router,
     * use RouterContext.clientManager.getClientSessionKeyManager(dest)
     *
     */
    public SessionKeyManager sessionKeyManager() { 
        if (!_sessionKeyManagerInitialized)
            initializeSessionKeyManager();
        return _sessionKeyManager;
    }

    private void initializeSessionKeyManager() {
        synchronized (this) {
            if (_sessionKeyManager == null) 
                //_sessionKeyManager = new PersistentSessionKeyManager(this);
                _sessionKeyManager = new TransientSessionKeyManager(this);
            _sessionKeyManagerInitialized = true;
        }
    }
    
    /**
     * Pull up the naming service used in this context.  The naming service itself
     * works by querying the context's properties, so those props should be 
     * specified to customize the naming service exposed.
     */
    public NamingService namingService() { 
        if (!_namingServiceInitialized)
            initializeNamingService();
        return _namingService;
    }

    private void initializeNamingService() {
        synchronized (this) {
            if (_namingService == null) {
                _namingService = NamingService.createInstance(this);
            }
            _namingServiceInitialized = true;
        }
    }
    
    /** @deprecated unused */
    public PetNameDB petnameDb() {
        if (!_petnameDbInitialized)
            initializePetnameDb();
        return _petnameDb;
    }

    /** @deprecated unused */
    private void initializePetnameDb() {
        synchronized (this) {
            if (_petnameDb == null) {
                _petnameDb = new PetNameDB();
            }
            _petnameDbInitialized = true;
        }
    }
    
    /**
     * This is the ElGamal engine used within this context.  While it doesn't
     * really have anything substantial that is context specific (the algorithm
     * just does the algorithm), it does transparently use the context for logging
     * its performance and activity.  In addition, the engine can be swapped with
     * the context's properties (though only someone really crazy should mess with
     * it ;)
     */
    public ElGamalEngine elGamalEngine() {
        if (!_elGamalEngineInitialized)
            initializeElGamalEngine();
        return _elGamalEngine;
    }

    private void initializeElGamalEngine() {
        synchronized (this) {
            if (_elGamalEngine == null) {
                if ("off".equals(getProperty("i2p.encryption", "on")))
                    _elGamalEngine = new DummyElGamalEngine(this);
                else
                    _elGamalEngine = new ElGamalEngine(this);
            }
            _elGamalEngineInitialized = true;
        }
    }
    
    /**
     * Access the ElGamal/AES+SessionTag engine for this context.  The algorithm
     * makes use of the context's sessionKeyManager to coordinate transparent
     * access to the sessionKeys and sessionTags, as well as the context's elGamal
     * engine (which in turn keeps stats, etc).
     *
     */
    public ElGamalAESEngine elGamalAESEngine() {
        if (!_elGamalAESEngineInitialized)
            initializeElGamalAESEngine();
        return _elGamalAESEngine;
    }

    private void initializeElGamalAESEngine() {
        synchronized (this) {
            if (_elGamalAESEngine == null)
                _elGamalAESEngine = new ElGamalAESEngine(this);
            _elGamalAESEngineInitialized = true;
        }
    }
    
    /**
     * Ok, I'll admit it.  there is no good reason for having a context specific
     * AES engine.  We dont really keep stats on it, since its just too fast to
     * matter.  Though for the crazy people out there, we do expose a way to 
     * disable it.
     */
    public AESEngine aes() {
        if (!_AESEngineInitialized)
            initializeAESEngine();
        return _AESEngine;
    }

    private void initializeAESEngine() {
        synchronized (this) {
            if (_AESEngine == null) {
                if ("off".equals(getProperty("i2p.encryption", "on")))
                    _AESEngine = new AESEngine(this);
                else
                    _AESEngine = new CryptixAESEngine(this);
            }
            _AESEngineInitialized = true;
        }
    }
    
    /**
     * Query the log manager for this context, which may in turn have its own
     * set of configuration settings (loaded from the context's properties).  
     * Each context's logManager keeps its own isolated set of Log instances with
     * their own log levels, output locations, and rotation configuration.
     */
    public LogManager logManager() { 
        if (!_logManagerInitialized)
            initializeLogManager();
        return _logManager;
    }

    private void initializeLogManager() {
        synchronized (this) {
            if (_logManager == null)
                _logManager = new LogManager(this);
            _logManagerInitialized = true;
        }
    }

    /** 
     * There is absolutely no good reason to make this context specific, 
     * other than for consistency, and perhaps later we'll want to 
     * include some stats.
     */
    public HMACGenerator hmac() { 
        if (!_hmacInitialized)
            initializeHMAC();
        return _hmac;
    }

    private void initializeHMAC() {
        synchronized (this) {
            if (_hmac == null) {
                _hmac= new HMACGenerator(this);
            }
            _hmacInitialized = true;
        }
    }

    /** @deprecated unused */
    public HMAC256Generator hmac256() {
        if (!_hmac256Initialized)
            initializeHMAC256();
        return _hmac256;
    }

    /** @deprecated unused */
    private void initializeHMAC256() {
        synchronized (this) {
            if (_hmac256 == null) {
                _hmac256 = new HMAC256Generator(this);
            }
            _hmac256Initialized = true;
        }
    }
    
    /**
     * Our SHA256 instance (see the hmac discussion for why its context specific)
     *
     */
    public SHA256Generator sha() { 
        if (!_shaInitialized)
            initializeSHA();
        return _sha;
    }

    private void initializeSHA() {
        synchronized (this) {
            if (_sha == null)
                _sha= new SHA256Generator(this);
            _shaInitialized = true;
        }
    }
    
    /**
     * Our DSA engine (see HMAC and SHA above)
     *
     */
    public DSAEngine dsa() { 
        if (!_dsaInitialized)
            initializeDSA();
        return _dsa;
    }

    private void initializeDSA() {
        synchronized (this) {
            if (_dsa == null) {
                if ("off".equals(getProperty("i2p.encryption", "on")))
                    _dsa = new DummyDSAEngine(this);
                else
                    _dsa = new DSAEngine(this);
            }
            _dsaInitialized = true;
        }
    }
    
    /**
     * Component to generate ElGamal, DSA, and Session keys.  For why it is in
     * the appContext, see the DSA, HMAC, and SHA comments above.
     */
    public KeyGenerator keyGenerator() {
        if (!_keyGeneratorInitialized)
            initializeKeyGenerator();
        return _keyGenerator;
    }

    private void initializeKeyGenerator() {
        synchronized (this) {
            if (_keyGenerator == null)
                _keyGenerator = new KeyGenerator(this);
            _keyGeneratorInitialized = true;
        }
    }
    
    /**
     * The context's synchronized clock, which is kept context specific only to
     * enable simulators to play with clock skew among different instances.
     *
     */
    public Clock clock() {
        if (!_clockInitialized)
            initializeClock();
        return _clock;
    }

    protected void initializeClock() { // overridden in RouterContext
        synchronized (this) {
            if (_clock == null)
                _clock = new Clock(this);
            _clockInitialized = true;
        }
    }
    
    /**
     * Determine how much do we want to mess with the keys to turn them 
     * into something we can route.  This is context specific because we 
     * may want to test out how things react when peers don't agree on 
     * how to skew.
     *
     */
    public RoutingKeyGenerator routingKeyGenerator() {
        if (!_routingKeyGeneratorInitialized)
            initializeRoutingKeyGenerator();
        return _routingKeyGenerator;
    }

    private void initializeRoutingKeyGenerator() {
        synchronized (this) {
            if (_routingKeyGenerator == null)
                _routingKeyGenerator = new RoutingKeyGenerator(this);
            _routingKeyGeneratorInitialized = true;
        }
    }
    
    /**
     * Basic hash map
     */
    public KeyRing keyRing() {
        if (!_keyRingInitialized)
            initializeKeyRing();
        return _keyRing;
    }

    protected void initializeKeyRing() {
        synchronized (this) {
            if (_keyRing == null)
                _keyRing = new KeyRing();
            _keyRingInitialized = true;
        }
    }
    
    /**
     * [insert snarky comment here]
     *
     */
    public RandomSource random() {
        if (!_randomInitialized)
            initializeRandom();
        return _random;
    }

    private void initializeRandom() {
        synchronized (this) {
            if (_random == null) {
                //if (true)
                    _random = new FortunaRandomSource(this);
                //else if ("true".equals(getProperty("i2p.weakPRNG", "false")))
                //    _random = new DummyPooledRandomSource(this);
                //else
                //    _random = new PooledRandomSource(this);
            }
            _randomInitialized = true;
        }
    }

    public void addShutdownTask(Runnable task) {
        _shutdownTasks.add(task);
    }
    
    public Set<Runnable> getShutdownTasks() {
        return new HashSet(_shutdownTasks);
    }
    
    /**
     *  Use this instead of context instanceof RouterContext
     *  @since 0.7.9
     */
    public boolean isRouterContext() {
        return false;
    }

    /**
     *  Use this to connect to the router in the same JVM.
     *  @return always null in I2PAppContext, the client manager if in RouterContext
     *  @since 0.8.3
     */
    public InternalClientManager internalClientManager() {
        return null;
    }
}
