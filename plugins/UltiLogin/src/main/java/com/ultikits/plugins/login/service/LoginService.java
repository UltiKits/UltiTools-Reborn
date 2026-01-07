package com.ultikits.plugins.login.service;

import com.ultikits.plugins.login.UltiLogin;
import com.ultikits.plugins.login.config.LoginConfig;
import com.ultikits.plugins.login.entity.AccountData;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.data.WhereCondition;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing player login and registration.
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Service
public class LoginService {
    
    @Autowired
    private LoginConfig config;
    
    private DataOperator<AccountData> dataOperator;
    
    // Track logged in players
    private final Map<UUID, Boolean> loggedInPlayers = new ConcurrentHashMap<>();
    
    // Track player join times for timeout
    private final Map<UUID, Long> joinTimes = new ConcurrentHashMap<>();
    
    // Track player original locations
    private final Map<UUID, Location> originalLocations = new ConcurrentHashMap<>();
    
    // Sessions (IP -> last login time)
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    
    // Timeout check task
    private BukkitTask timeoutTask;
    
    /**
     * Initialize the login service.
     */
    public void init() {
        dataOperator = UltiLogin.getInstance().getDataOperator(AccountData.class);
        
        // Start timeout check task
        timeoutTask = Bukkit.getScheduler().runTaskTimer(
            UltiLogin.getInstance().getPluginInstance(),
            this::checkTimeouts,
            20L, 20L // Every second
        );
    }
    
    /**
     * Shutdown the service.
     */
    public void shutdown() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
        }
        loggedInPlayers.clear();
        joinTimes.clear();
        originalLocations.clear();
    }
    
    /**
     * Check if player is registered.
     */
    public boolean isRegistered(UUID playerUuid) {
        List<AccountData> accounts = dataOperator.getAll(
            WhereCondition.builder()
                .column("player_uuid")
                .value(playerUuid.toString())
                .build()
        );
        return !accounts.isEmpty();
    }
    
    /**
     * Check if player is logged in.
     */
    public boolean isLoggedIn(UUID playerUuid) {
        return loggedInPlayers.getOrDefault(playerUuid, false);
    }
    
    /**
     * Register a new player.
     * 
     * @param player Player to register
     * @param password Password
     * @return true if success
     */
    public boolean register(Player player, String password) {
        if (isRegistered(player.getUniqueId())) {
            return false;
        }
        
        // Check IP registration limit
        String ip = getPlayerIp(player);
        if (config.getMaxRegisterPerIp() > 0) {
            int count = countRegistrationsByIp(ip);
            if (count >= config.getMaxRegisterPerIp()) {
                player.sendMessage(ChatColor.RED + "该IP已达到最大注册数量！");
                return false;
            }
        }
        
        // Generate salt and hash password
        String salt = generateSalt();
        String hash = hashPassword(password, salt);
        
        // Create account
        AccountData account = new AccountData();
        account.setPlayerUuid(player.getUniqueId().toString());
        account.setPlayerName(player.getName());
        account.setPasswordHash(hash);
        account.setSalt(salt);
        account.setRegisterIp(ip);
        account.setLastIp(ip);
        account.setLastLogin(System.currentTimeMillis());
        
        dataOperator.insert(account);
        
        // Auto login after register
        completeLogin(player);
        
        return true;
    }
    
    /**
     * Login a player.
     * 
     * @param player Player
     * @param password Password
     * @return true if success
     */
    public boolean login(Player player, String password) {
        AccountData account = getAccount(player.getUniqueId());
        if (account == null) {
            return false;
        }
        
        // Verify password
        String hash = hashPassword(password, account.getSalt());
        if (!hash.equals(account.getPasswordHash())) {
            return false;
        }
        
        // Update last login
        String ip = getPlayerIp(player);
        account.setLastIp(ip);
        account.setLastLogin(System.currentTimeMillis());
        account.setLoginCount(account.getLoginCount() + 1);
        dataOperator.update(account);
        
        // Create session
        if (config.isSessionEnabled()) {
            sessions.put(ip + ":" + player.getUniqueId(), System.currentTimeMillis());
        }
        
        completeLogin(player);
        return true;
    }
    
    /**
     * Check if player has valid session.
     */
    public boolean hasValidSession(Player player) {
        if (!config.isSessionEnabled()) {
            return false;
        }
        
        String ip = getPlayerIp(player);
        String key = ip + ":" + player.getUniqueId();
        Long lastLogin = sessions.get(key);
        
        if (lastLogin == null) {
            return false;
        }
        
        long sessionTimeout = config.getSessionTimeout() * 60 * 1000L;
        return System.currentTimeMillis() - lastLogin < sessionTimeout;
    }
    
    /**
     * Handle player join.
     */
    public void onPlayerJoin(Player player) {
        UUID uuid = player.getUniqueId();
        loggedInPlayers.put(uuid, false);
        joinTimes.put(uuid, System.currentTimeMillis());
        
        // Store original location
        originalLocations.put(uuid, player.getLocation().clone());
        
        // Check session
        if (hasValidSession(player)) {
            completeLogin(player);
            player.sendMessage(ChatColor.GREEN + "会话有效，自动登录成功！");
            return;
        }
        
        // Apply blind effect
        if (config.isBlindEffect()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0, false, false));
        }
        
        // Teleport to spawn if enabled
        if (config.isSpawnLocationEnabled()) {
            World world = Bukkit.getWorld(config.getSpawnWorld());
            if (world != null) {
                Location spawn = new Location(world, config.getSpawnX(), config.getSpawnY(), config.getSpawnZ());
                player.teleport(spawn);
            }
        }
        
        // Send prompt
        if (isRegistered(uuid)) {
            String message = config.getLoginPrompt();
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        } else {
            String message = config.getRegisterPrompt();
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }
    }
    
    /**
     * Handle player quit.
     */
    public void onPlayerQuit(Player player) {
        UUID uuid = player.getUniqueId();
        loggedInPlayers.remove(uuid);
        joinTimes.remove(uuid);
        originalLocations.remove(uuid);
    }
    
    /**
     * Complete login process.
     */
    private void completeLogin(Player player) {
        UUID uuid = player.getUniqueId();
        loggedInPlayers.put(uuid, true);
        joinTimes.remove(uuid);
        
        // Remove blind effect
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        
        // Restore original location if we teleported to spawn
        if (config.isSpawnLocationEnabled()) {
            Location original = originalLocations.get(uuid);
            if (original != null) {
                player.teleport(original);
            }
        }
        originalLocations.remove(uuid);
    }
    
    /**
     * Check for login timeouts.
     */
    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        long timeout = config.getLoginTimeout() * 1000L;
        
        for (Map.Entry<UUID, Long> entry : joinTimes.entrySet()) {
            if (now - entry.getValue() > timeout) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    String message = config.getTimeoutKick();
                    player.kickPlayer(ChatColor.translateAlternateColorCodes('&', message));
                }
            }
        }
    }
    
    /**
     * Get account by UUID.
     */
    public AccountData getAccount(UUID playerUuid) {
        List<AccountData> accounts = dataOperator.getAll(
            WhereCondition.builder()
                .column("player_uuid")
                .value(playerUuid.toString())
                .build()
        );
        return accounts.isEmpty() ? null : accounts.get(0);
    }
    
    /**
     * Change password.
     */
    public boolean changePassword(UUID playerUuid, String oldPassword, String newPassword) {
        AccountData account = getAccount(playerUuid);
        if (account == null) {
            return false;
        }
        
        // Verify old password
        String oldHash = hashPassword(oldPassword, account.getSalt());
        if (!oldHash.equals(account.getPasswordHash())) {
            return false;
        }
        
        // Generate new salt and hash
        String newSalt = generateSalt();
        String newHash = hashPassword(newPassword, newSalt);
        
        account.setSalt(newSalt);
        account.setPasswordHash(newHash);
        dataOperator.update(account);
        
        return true;
    }
    
    /**
     * Count registrations by IP.
     */
    private int countRegistrationsByIp(String ip) {
        List<AccountData> accounts = dataOperator.getAll(
            WhereCondition.builder()
                .column("register_ip")
                .value(ip)
                .build()
        );
        return accounts.size();
    }
    
    /**
     * Get player IP.
     */
    private String getPlayerIp(Player player) {
        if (player.getAddress() != null) {
            return player.getAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }
    
    /**
     * Generate random salt.
     */
    private String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }
    
    /**
     * Hash password with salt using SHA-256.
     */
    private String hashPassword(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = password + salt;
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
    
    /**
     * Check if command is allowed before login.
     */
    public boolean isCommandAllowed(String command) {
        String cmd = command.toLowerCase().split(" ")[0].replaceFirst("/", "");
        return config.getAllowedCommands().contains(cmd);
    }
    
    /**
     * Get config.
     */
    public LoginConfig getConfig() {
        return config;
    }
}
