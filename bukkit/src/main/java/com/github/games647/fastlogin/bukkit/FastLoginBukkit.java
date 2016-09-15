package com.github.games647.fastlogin.bukkit;

import com.avaje.ebeaninternal.api.ClassUtil;
import com.comphenix.protocol.AsynchronousManager;
import com.comphenix.protocol.ProtocolLibrary;
import com.github.games647.fastlogin.bukkit.commands.CrackedCommand;
import com.github.games647.fastlogin.bukkit.commands.ImportCommand;
import com.github.games647.fastlogin.bukkit.commands.PremiumCommand;
import com.github.games647.fastlogin.bukkit.hooks.BukkitAuthPlugin;
import com.github.games647.fastlogin.bukkit.listener.BukkitJoinListener;
import com.github.games647.fastlogin.bukkit.listener.BungeeCordListener;
import com.github.games647.fastlogin.bukkit.listener.protocollib.EncryptionPacketListener;
import com.github.games647.fastlogin.bukkit.listener.protocollib.LoginSkinApplyListener;
import com.github.games647.fastlogin.bukkit.listener.protocollib.StartPacketListener;
import com.github.games647.fastlogin.bukkit.listener.protocolsupport.ProtocolSupportListener;
import com.github.games647.fastlogin.bukkit.tasks.DelayedAuthHook;
import com.github.games647.fastlogin.core.hooks.AuthPlugin;
import com.github.games647.fastlogin.core.shared.FastLoginCore;

import java.security.KeyPair;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * This plugin checks if a player has a paid account and if so tries to skip offline mode authentication.
 */
public class FastLoginBukkit extends JavaPlugin {

    private static final int WORKER_THREADS = 3;

    //provide a immutable key pair to be thread safe | used for encrypting and decrypting traffic
    private final KeyPair keyPair = EncryptionUtil.generateKeyPair();

    private boolean bungeeCord;
    private BukkitCore core;
    private boolean serverStarted;

    //1 minutes should be enough as a timeout for bad internet connection (Server, Client and Mojang)
    private final ConcurrentMap<String, BukkitLoginSession> session = FastLoginCore.buildCache(1, -1);
    
    @Override
    public void onEnable() {
        core = new BukkitCore(this);

        core.loadConfig();
        core.loadMessages();

        List<String> ipAddresses = getConfig().getStringList("ip-addresses");
        int requestLimit = getConfig().getInt("mojang-request-limit");
        MojangApiBukkit mojangApi = new MojangApiBukkit(getLogger(), ipAddresses, requestLimit);
        core.setMojangApiConnector(mojangApi);

        try {
            if (ClassUtil.isPresent("org.spigotmc.SpigotConfig")) {
                bungeeCord = Class.forName("org.spigotmc.SpigotConfig").getDeclaredField("bungee").getBoolean(null);
            }
        } catch (Exception | NoSuchMethodError ex) {
            getLogger().log(Level.WARNING, "Cannot check bungeecord support. You use a non-spigot build", ex);
        }

        if (getServer().getOnlineMode()) {
            //we need to require offline to prevent a session request for a offline player
            getLogger().severe("Server have to be in offline mode");
            setEnabled(false);
            return;
        }

        if (bungeeCord) {
            setServerStarted();
            
            //check for incoming messages from the bungeecord version of this plugin
            getServer().getMessenger().registerIncomingPluginChannel(this, getName(), new BungeeCordListener(this));
            getServer().getMessenger().registerOutgoingPluginChannel(this, getName());
            //register listeners on success
        } else {
            if (!core.setupDatabase()) {
                setEnabled(false);
                return;
            }

            if (getServer().getPluginManager().isPluginEnabled("ProtocolSupport")) {
                getServer().getPluginManager().registerEvents(new ProtocolSupportListener(this), this);
            } else if (getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
                //we are performing HTTP request on these so run it async (seperate from the Netty IO threads)
                AsynchronousManager asynchronousManager = ProtocolLibrary.getProtocolManager().getAsynchronousManager();

                StartPacketListener startPacketListener = new StartPacketListener(this);
                EncryptionPacketListener encryptionPacketListener = new EncryptionPacketListener(this);

                asynchronousManager.registerAsyncHandler(startPacketListener).start(WORKER_THREADS);
                asynchronousManager.registerAsyncHandler(encryptionPacketListener).start(WORKER_THREADS);
                getServer().getPluginManager().registerEvents(new LoginSkinApplyListener(this), this);
            } else {
                getLogger().warning("Either ProtocolLib or ProtocolSupport have to be installed "
                        + "if you don't use BungeeCord");
            }
        }

        //delay dependency setup because we load the plugin very early where plugins are initialized yet
        getServer().getScheduler().runTask(this, new DelayedAuthHook(this));

        getServer().getPluginManager().registerEvents(new BukkitJoinListener(this), this);

        //register commands using a unique name
        getCommand("premium").setExecutor(new PremiumCommand(this));
        getCommand("cracked").setExecutor(new CrackedCommand(this));
        getCommand("import-auth").setExecutor(new ImportCommand(this));
    }

    @Override
    public void onDisable() {
        session.clear();

        if (core != null) {
            core.close();
        }

        //remove old blacklists
        for (Player player : getServer().getOnlinePlayers()) {
            player.removeMetadata(getName(), this);
        }
    }

    public BukkitCore getCore() {
        return core;
    }

    @Deprecated
    public void setPasswordGenerator(PasswordGenerator passwordGenerator) {
        core.setPasswordGenerator(passwordGenerator);
    }

    /**
     * Gets a thread-safe map about players which are connecting to the server are being checked to be premium (paid
     * account)
     *
     * @return a thread-safe session map
     */
    public ConcurrentMap<String, BukkitLoginSession> getSessions() {
        return session;
    }

    /**
     * Gets the server KeyPair. This is used to encrypt or decrypt traffic between the client and server
     *
     * @return the server KeyPair
     */
    public KeyPair getServerKey() {
        return keyPair;
    }

    /**
     * Gets the auth plugin hook in order to interact with the plugins. This can be null if no supporting auth plugin
     * was found.
     *
     * @return interface to any supported auth plugin
     */
    public BukkitAuthPlugin getAuthPlugin() {
        AuthPlugin<Player> authPlugin = core.getAuthPlugin();
        if (authPlugin == null) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                getLogger().log(Level.SEVERE, null, ex);
            }
        }

        return (BukkitAuthPlugin) authPlugin;
    }

    public void setAuthPluginHook(BukkitAuthPlugin authPlugin) {
        core.setAuthPlugin(authPlugin);
    }

    public boolean isBungeeCord() {
        return bungeeCord;
    }

    /**
     * Wait before the server is fully started. This is workaround, because connections right on startup are not
     * injected by ProtocolLib
     *
     * @return
     */
    public boolean isServerFullyStarted() {
        return serverStarted;
    }

    public void setServerStarted() {
        if (!this.serverStarted) {
            this.serverStarted = true;
        }
    }
}
