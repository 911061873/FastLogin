package com.github.games647.fastlogin.core.shared;

import com.github.games647.fastlogin.core.AuthStorage;
import com.github.games647.fastlogin.core.CompatibleCacheBuilder;
import com.github.games647.fastlogin.core.SharedConfig;
import com.github.games647.fastlogin.core.hooks.AuthPlugin;
import com.github.games647.fastlogin.core.hooks.DefaultPasswordGenerator;
import com.github.games647.fastlogin.core.hooks.PasswordGenerator;
import com.github.games647.fastlogin.core.importer.AutoInImporter;
import com.github.games647.fastlogin.core.importer.ImportPlugin;
import com.github.games647.fastlogin.core.importer.Importer;
import com.google.common.cache.CacheLoader;
import com.google.common.collect.Sets;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class FastLoginCore<T> {

    public static <K, V> ConcurrentMap<K, V> buildCache(int expireAfterWrite, int maxSize) {
        CompatibleCacheBuilder<Object, Object> builder = CompatibleCacheBuilder.newBuilder();

        if (expireAfterWrite > 0) {
            builder.expireAfterWrite(expireAfterWrite, TimeUnit.MINUTES);
        }

        if (maxSize > 0) {
            builder.maximumSize(maxSize);
        }

        return builder.build(new CacheLoader<K, V>() {
            @Override
            public V load(K key) throws Exception {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        });
    }

    public static UUID parseId(String withoutDashes) {
        if (withoutDashes == null) {
            return null;
        }

        return UUID.fromString(withoutDashes.substring(0, 8)
                + "-" + withoutDashes.substring(8, 12)
                + "-" + withoutDashes.substring(12, 16)
                + "-" + withoutDashes.substring(16, 20)
                + "-" + withoutDashes.substring(20, 32));
    }

    protected final Map<String, String> localeMessages = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Object> pendingLogins;
    private final Set<UUID> pendingConfirms = Sets.newHashSet();
    private final SharedConfig sharedConfig;

    private MojangApiConnector mojangApiConnector;
    private AuthStorage storage;
    private PasswordGenerator<T> passwordGenerator = new DefaultPasswordGenerator<>();
    private AuthPlugin<T> authPlugin;

    public FastLoginCore(Map<String, Object> config) {
        this.pendingLogins = FastLoginCore.buildCache(5, 0);
        this.sharedConfig = new SharedConfig(config);
    }

    public void setMojangApiConnector(MojangApiConnector mojangApiConnector) {
        this.mojangApiConnector = mojangApiConnector;
    }

    public MojangApiConnector getMojangApiConnector() {
        return mojangApiConnector;
    }

    public AuthStorage getStorage() {
        return storage;
    }

    public abstract File getDataFolder();

    public abstract Logger getLogger();

    public abstract ThreadFactory getThreadFactory();

    public String getMessage(String key) {
        return localeMessages.get(key);
    }

    public abstract void loadMessages();

    public abstract void loadConfig();

    public boolean setupDatabase() {
        String driver = sharedConfig.get("driver");
        String host = sharedConfig.get("host", "");
        int port = sharedConfig.get("port", 3306);
        String database = sharedConfig.get("database");

        String user = sharedConfig.get("username", "");
        String password = sharedConfig.get("password", "");

        storage = new AuthStorage(this, driver, host, port, database, user, password);
        try {
            storage.createTables();
            return true;
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Failed to setup database. Disabling plugin...", ex);
            return false;
        }
    }

    public boolean importDatabase(ImportPlugin plugin, boolean sqlite, AuthStorage storage, String host, String database
            , String username, String pass) {
        if (sqlite && (plugin == ImportPlugin.BPA || plugin == ImportPlugin.ELDZI)) {
            throw new IllegalArgumentException("These plugins doesn't support flat file databases");
        }

        Importer importer;
        try {
            importer = plugin.getImporter().newInstance();
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Couldn't not setup importer class", ex);
            return false;
        } 

        try {
            if (sqlite && plugin == ImportPlugin.AUTO_IN) {
                //load sqlite driver
                Class.forName("org.sqlite.JDBC");

                String jdbcUrl = "jdbc:sqlite:" + AutoInImporter.getSQLitePath();
                Connection con = DriverManager.getConnection(jdbcUrl);
                importer.importData(con, storage.getDataSource(), storage);
                return true;
            } else {
                Class.forName("com.mysql.jdbc.Driver");

                String jdbcUrl = "jdbc:mysql://" + host + "/" + database;
                Connection con = DriverManager.getConnection(jdbcUrl, username, pass);
                importer.importData(con, storage.getDataSource(), storage);
                return true;
            }
        } catch (ClassNotFoundException ex) {
            getLogger().log(Level.SEVERE, "Cannot find SQL driver. Do you removed it?", ex);
        } catch (SQLException ex) {
            getLogger().log(Level.SEVERE, "Couldn't import data. Aborting...", ex);
        }

        return false;
    }

    public SharedConfig getSharedConfig() {
        return sharedConfig;
    }

    public PasswordGenerator<T> getPasswordGenerator() {
        return passwordGenerator;
    }

    public void setPasswordGenerator(PasswordGenerator<T> passwordGenerator) {
        this.passwordGenerator = passwordGenerator;
    }

    public ConcurrentMap<String, Object> getPendingLogins() {
        return pendingLogins;
    }

    public Set<UUID> getPendingConfirms() {
        return pendingConfirms;
    }

    public AuthPlugin<T> getAuthPlugin() {
        return authPlugin;
    }

    public void setAuthPlugin(AuthPlugin<T> authPlugin) {
        this.authPlugin = authPlugin;
    }

    public void close() {
        if (storage != null) {
            storage.close();
        }
    }
}
