package com.ultikits.plugins.login.service;

import com.ultikits.plugins.login.UltiLogin;
import com.ultikits.plugins.login.config.LoginConfig;
import com.ultikits.plugins.login.entity.AccountData;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.entities.WhereCondition;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.utils.CommonUtils;
import com.ultikits.ultitools.utils.SimpleHttpClient;

/**
 * Service for managing player login and registration.
 *
 * @author wisdomme
 * @version 1.1.0
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
    
    // Sessions (IP:UUID -> last login time)
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    
    // Failed login attempts tracking (IP -> count)
    private final Map<String, Integer> failedAttempts = new ConcurrentHashMap<>();
    
    // Locked IPs (IP -> unlock time)
    private final Map<String, Long> lockedIps = new ConcurrentHashMap<>();
    
    // Locked UUIDs (UUID -> unlock time)
    private final Map<UUID, Long> lockedUuids = new ConcurrentHashMap<>();
    
    // Timeout check task
    private BukkitTask timeoutTask;

    // Random generator for password generation
    private final SecureRandom random = new SecureRandom();

    // Pending panel login requests (requestId -> player UUID)
    private final Map<String, UUID> pendingPanelRequests = new ConcurrentHashMap<>();

    // Pending panel request timestamps (requestId -> creation time)
    private final Map<String, Long> pendingPanelTimestamps = new ConcurrentHashMap<>();

    // Active polling tasks per player (playerUUID -> task)
    private final Map<UUID, BukkitTask> pollingTasks = new ConcurrentHashMap<>();

    private final Gson gson = new Gson();
    
    /**
     * Initialize the login service.
     */
    public void init() {
        dataOperator = UltiLogin.getInstance().getDataOperator(AccountData.class);
        
        // Start timeout check task
        timeoutTask = Bukkit.getScheduler().runTaskTimer(
            UltiTools.getInstance(),
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
        for (BukkitTask task : pollingTasks.values()) {
            task.cancel();
        }
        pollingTasks.clear();
        loggedInPlayers.clear();
        joinTimes.clear();
        originalLocations.clear();
        failedAttempts.clear();
        lockedIps.clear();
        lockedUuids.clear();
        pendingPanelRequests.clear();
        pendingPanelTimestamps.clear();
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
     * Check if player is registered by name.
     */
    public boolean isRegisteredByName(String playerName) {
        List<AccountData> accounts = dataOperator.getAll(
            WhereCondition.builder()
                .column("player_name")
                .value(playerName)
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
     * Check if IP/UUID is locked due to failed attempts.
     */
    public boolean isLocked(Player player) {
        String ip = getPlayerIp(player);
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        
        String lockoutType = config.getLockoutType().toUpperCase();
        
        // Check IP lock
        if (("IP".equals(lockoutType) || "BOTH".equals(lockoutType)) && lockedIps.containsKey(ip)) {
            if (now < lockedIps.get(ip)) {
                return true;
            } else {
                lockedIps.remove(ip);
                failedAttempts.remove(ip);
            }
        }
        
        // Check UUID lock
        if (("UUID".equals(lockoutType) || "BOTH".equals(lockoutType)) && lockedUuids.containsKey(uuid)) {
            if (now < lockedUuids.get(uuid)) {
                return true;
            } else {
                lockedUuids.remove(uuid);
            }
        }
        
        return false;
    }
    
    /**
     * Get remaining lockout time in seconds.
     */
    public long getRemainingLockoutTime(Player player) {
        String ip = getPlayerIp(player);
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long remaining = 0;
        
        if (lockedIps.containsKey(ip)) {
            remaining = Math.max(remaining, (lockedIps.get(ip) - now) / 1000);
        }
        if (lockedUuids.containsKey(uuid)) {
            remaining = Math.max(remaining, (lockedUuids.get(uuid) - now) / 1000);
        }
        
        return remaining;
    }
    
    /**
     * Record a failed login attempt.
     */
    private void recordFailedAttempt(Player player) {
        if (config.getMaxLoginAttempts() <= 0) {
            return;
        }
        
        String ip = getPlayerIp(player);
        int attempts = failedAttempts.getOrDefault(ip, 0) + 1;
        failedAttempts.put(ip, attempts);
        
        if (attempts >= config.getMaxLoginAttempts()) {
            long unlockTime = System.currentTimeMillis() + (config.getLockoutDuration() * 1000L);
            String lockoutType = config.getLockoutType().toUpperCase();
            
            if ("IP".equals(lockoutType) || "BOTH".equals(lockoutType)) {
                lockedIps.put(ip, unlockTime);
            }
            if ("UUID".equals(lockoutType) || "BOTH".equals(lockoutType)) {
                lockedUuids.put(player.getUniqueId(), unlockTime);
            }
        }
    }
    
    /**
     * Get remaining login attempts for a player.
     */
    public int getRemainingAttempts(Player player) {
        if (config.getMaxLoginAttempts() <= 0) {
            return -1; // Unlimited
        }
        String ip = getPlayerIp(player);
        int attempts = failedAttempts.getOrDefault(ip, 0);
        return config.getMaxLoginAttempts() - attempts;
    }
    
    /**
     * Clear failed attempts for a player (on successful login).
     */
    private void clearFailedAttempts(Player player) {
        String ip = getPlayerIp(player);
        failedAttempts.remove(ip);
    }
    
    /**
     * Validate password based on current mode (GUI/Command).
     */
    public boolean isPasswordValid(String password) {
        if (config.isGuiModeEnabled()) {
            // GUI mode: must be exact length and digits only
            return password.length() == config.getGuiPasswordLength() 
                && password.matches("\\d+");
        } else {
            // Command mode: check length
            return password.length() >= config.getMinPasswordLength() 
                && password.length() <= config.getMaxPasswordLength();
        }
    }
    
    /**
     * Get password validation error message.
     */
    public String getPasswordValidationError(String password) {
        if (config.isGuiModeEnabled()) {
            return config.getGuiPasswordInvalid()
                .replace("{LENGTH}", String.valueOf(config.getGuiPasswordLength()));
        } else {
            if (password.length() < config.getMinPasswordLength()) {
                return config.getPasswordTooShort()
                    .replace("{MIN}", String.valueOf(config.getMinPasswordLength()));
            }
            if (password.length() > config.getMaxPasswordLength()) {
                return config.getPasswordTooLong()
                    .replace("{MAX}", String.valueOf(config.getMaxPasswordLength()));
            }
        }
        return "";
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
     * @return LoginResult with status and message
     */
    public LoginResult login(Player player, String password) {
        // Check if locked
        if (isLocked(player)) {
            long remaining = getRemainingLockoutTime(player);
            return new LoginResult(false, config.getAccountLocked()
                .replace("{TIME}", String.valueOf(remaining)));
        }
        
        AccountData account = getAccount(player.getUniqueId());
        if (account == null) {
            return new LoginResult(false, config.getNotRegistered());
        }
        
        // Verify password
        String hash = hashPassword(password, account.getSalt());
        if (!hash.equals(account.getPasswordHash())) {
            recordFailedAttempt(player);
            int remaining = getRemainingAttempts(player);
            if (remaining > 0) {
                return new LoginResult(false, config.getAttemptsRemaining()
                    .replace("{COUNT}", String.valueOf(remaining)));
            } else {
                return new LoginResult(false, config.getAccountLocked()
                    .replace("{TIME}", String.valueOf(config.getLockoutDuration())));
            }
        }
        
        // Clear failed attempts on success
        clearFailedAttempts(player);
        
        // Update last login
        String ip = getPlayerIp(player);
        account.setLastIp(ip);
        account.setLastLogin(System.currentTimeMillis());
        account.setLoginCount(account.getLoginCount() + 1);
        account.setFailedAttempts(0);
        try {
            dataOperator.update(account);
        } catch (IllegalAccessException e) {
            UltiLogin.getInstance().getLogger().error("Failed to update account", e);
        }
        
        // Create session
        if (config.isSessionEnabled()) {
            sessions.put(ip + ":" + player.getUniqueId(), System.currentTimeMillis());
        }
        
        completeLogin(player);
        return new LoginResult(true, config.getLoginSuccess());
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
        
        // Send prompt based on mode
        if (isRegistered(uuid)) {
            String message = config.isGuiModeEnabled() 
                ? config.getLoginPromptGui() 
                : config.getLoginPrompt();
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        } else {
            String message = config.isGuiModeEnabled() 
                ? config.getRegisterPromptGui() 
                : config.getRegisterPrompt();
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
        BukkitTask pollingTask = pollingTasks.remove(uuid);
        if (pollingTask != null) {
            pollingTask.cancel();
        }
    }
    
    /**
     * Complete login process.
     */
    public void completeLogin(Player player) {
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
     * Force login a player (admin command).
     */
    public boolean forceLogin(Player player) {
        if (!isRegistered(player.getUniqueId())) {
            return false;
        }
        if (isLoggedIn(player.getUniqueId())) {
            return false;
        }
        completeLogin(player);
        return true;
    }
    
    /**
     * Reset player password (admin command).
     * @return the new random password, or null if failed
     */
    public String resetPassword(UUID playerUuid) {
        AccountData account = getAccount(playerUuid);
        if (account == null) {
            return null;
        }
        
        // Generate random password
        String newPassword = generateRandomPassword();
        String newSalt = generateSalt();
        String newHash = hashPassword(newPassword, newSalt);
        
        account.setSalt(newSalt);
        account.setPasswordHash(newHash);
        try {
            dataOperator.update(account);
        } catch (IllegalAccessException e) {
            UltiLogin.getInstance().getLogger().error("Failed to reset password", e);
            return null;
        }
        
        return newPassword;
    }
    
    /**
     * Reset player password with specific password (admin command).
     */
    public boolean resetPassword(UUID playerUuid, String newPassword) {
        AccountData account = getAccount(playerUuid);
        if (account == null) {
            return false;
        }
        
        String newSalt = generateSalt();
        String newHash = hashPassword(newPassword, newSalt);
        
        account.setSalt(newSalt);
        account.setPasswordHash(newHash);
        try {
            dataOperator.update(account);
        } catch (IllegalAccessException e) {
            UltiLogin.getInstance().getLogger().error("Failed to reset password", e);
            return false;
        }
        
        return true;
    }
    
    /**
     * Unregister a player (admin command).
     */
    public boolean unregister(UUID playerUuid) {
        AccountData account = getAccount(playerUuid);
        if (account == null) {
            return false;
        }
        
        dataOperator.delById(account.getId());
        
        // Force logout if online
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null && player.isOnline()) {
            loggedInPlayers.put(playerUuid, false);
            onPlayerJoin(player);
        }
        
        return true;
    }
    
    /**
     * Get account by player name.
     */
    public AccountData getAccountByName(String playerName) {
        List<AccountData> accounts = dataOperator.getAll(
            WhereCondition.builder()
                .column("player_name")
                .value(playerName)
                .build()
        );
        return accounts.isEmpty() ? null : accounts.get(0);
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

        // Clean up expired panel requests
        cleanupExpiredPanelRequests();
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
        try {
            dataOperator.update(account);
        } catch (IllegalAccessException e) {
            UltiLogin.getInstance().getLogger().error("Failed to update account", e);
            return false;
        }
        
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
    public String getPlayerIp(Player player) {
        if (player.getAddress() != null) {
            return player.getAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }
    
    /**
     * Generate random salt.
     */
    private String generateSalt() {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }
    
    /**
     * Generate random password (for admin reset).
     */
    private String generateRandomPassword() {
        if (config.isGuiModeEnabled()) {
            // GUI mode: generate numeric password
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < config.getGuiPasswordLength(); i++) {
                sb.append(random.nextInt(9) + 1); // 1-9
            }
            return sb.toString();
        } else {
            // Command mode: generate alphanumeric password
            String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(chars.charAt(random.nextInt(chars.length())));
            }
            return sb.toString();
        }
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
    
    // ==================== Panel Magic Link Methods ====================

    /**
     * Check if UltiCloud panel integration is enabled.
     */
    public boolean isPanelEnabled() {
        return config.isUlticloudEnabled();
    }

    /**
     * Generate a 6-digit verification code.
     */
    public String generateVerificationCode() {
        int code = 100000 + random.nextInt(900000);
        return String.valueOf(code);
    }

    /**
     * Request a panel magic link for a player.
     * Calls the API Worker to create a magic link and returns the URL.
     *
     * @param player the player requesting the link
     * @return PanelLinkResult with the URL or error message
     */
    public PanelLinkResult requestPanelLink(Player player) {
        if (!isPanelEnabled()) {
            return new PanelLinkResult(false, null, "Panel integration not enabled");
        }

        String requestId = UUID.randomUUID().toString();
        String code = generateVerificationCode();
        String playerUuid = player.getUniqueId().toString();
        String playerName = player.getName();

        // Track the pending request
        pendingPanelRequests.put(requestId, player.getUniqueId());
        pendingPanelTimestamps.put(requestId, System.currentTimeMillis());

        // Build API request
        String apiUrl;
        try {
            apiUrl = UltiTools.getEnv().getString("api-url");
        } catch (Exception e) {
            cleanupPanelRequest(requestId);
            return new PanelLinkResult(false, null, "API URL not configured");
        }

        String serverUuid;
        try {
            serverUuid = CommonUtils.getUltiToolsUUID();
        } catch (Exception e) {
            cleanupPanelRequest(requestId);
            return new PanelLinkResult(false, null, "Server UUID not available");
        }

        JsonObject body = new JsonObject();
        body.addProperty("requestId", requestId);
        body.addProperty("code", code);
        body.addProperty("playerUuid", playerUuid);
        body.addProperty("playerName", playerName);
        body.addProperty("serverUuid", serverUuid);

        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");

            SimpleHttpClient.Response response = SimpleHttpClient.post(
                apiUrl + "/auth/magic-link",
                headers,
                gson.toJson(body)
            );

            if (response.isOk()) {
                JsonObject responseBody = JsonParser.parseString(response.getBody()).getAsJsonObject();
                // API returns wrapped response: {"code":"200","data":{"url":"..."}}
                String url = null;
                if (responseBody.has("data") && responseBody.get("data").isJsonObject()) {
                    JsonObject data = responseBody.getAsJsonObject("data");
                    url = data.has("url") ? data.get("url").getAsString() : null;
                } else if (responseBody.has("url")) {
                    url = responseBody.get("url").getAsString();
                }
                if (url != null) {
                    return new PanelLinkResult(true, url, null);
                }
                cleanupPanelRequest(requestId);
                return new PanelLinkResult(false, null, "Invalid response from API");
            } else {
                cleanupPanelRequest(requestId);
                return new PanelLinkResult(false, null, "API returned status " + response.getStatus());
            }
        } catch (Exception e) {
            cleanupPanelRequest(requestId);
            UltiLogin.getInstance().getLogger().warn("Failed to request panel link: " + e.getMessage());
            return new PanelLinkResult(false, null, "Request failed: " + e.getMessage());
        }
    }

    /**
     * Start polling the Worker API for auth completion.
     * Polls every 3 seconds for up to 5 minutes, then auto-cancels.
     *
     * @param playerUuid the player's UUID string
     * @param player the online player
     */
    public void startAuthPolling(String playerUuid, Player player) {
        UUID uuid = player.getUniqueId();

        // Cancel any existing polling task for this player
        BukkitTask existing = pollingTasks.remove(uuid);
        if (existing != null) {
            existing.cancel();
        }

        String apiUrl;
        try {
            apiUrl = UltiTools.getEnv().getString("api-url");
        } catch (Exception e) {
            UltiLogin.getInstance().getLogger().warn("Cannot start auth polling: API URL not configured");
            return;
        }

        String pollUrl = apiUrl + "/auth/magic-link/poll?playerUuid=" + playerUuid;
        final int maxPolls = 100; // 100 * 3s = 5 minutes
        final int[] pollCount = {0};

        BukkitTask task = Bukkit.getScheduler().runTaskTimerAsynchronously(UltiTools.getInstance(), () -> {
            pollCount[0]++;

            if (!player.isOnline() || pollCount[0] > maxPolls) {
                BukkitTask self = pollingTasks.remove(uuid);
                if (self != null) {
                    self.cancel();
                }
                return;
            }

            try {
                SimpleHttpClient.Response response = SimpleHttpClient.get(pollUrl);
                if (!response.isOk()) {
                    return;
                }

                JsonObject body = JsonParser.parseString(response.getBody()).getAsJsonObject();
                JsonObject data = body.has("data") && body.get("data").isJsonObject()
                    ? body.getAsJsonObject("data") : null;
                if (data == null) {
                    return;
                }

                String status = data.has("status") ? data.get("status").getAsString() : null;
                if (!"completed".equals(status)) {
                    return;
                }

                // Auth completed — cancel polling
                BukkitTask self = pollingTasks.remove(uuid);
                if (self != null) {
                    self.cancel();
                }

                boolean isServerOwner = data.has("is_server_owner")
                    && !data.get("is_server_owner").isJsonNull()
                    && data.get("is_server_owner").getAsBoolean();

                // Find the requestId for this player so completePanelLogin can clean up
                String requestId = null;
                for (Map.Entry<String, UUID> entry : pendingPanelRequests.entrySet()) {
                    if (entry.getValue().equals(uuid)) {
                        requestId = entry.getKey();
                        break;
                    }
                }

                final String foundRequestId = requestId;
                final boolean finalIsServerOwner = isServerOwner;

                // Complete login on main thread
                Bukkit.getScheduler().runTask(UltiTools.getInstance(), () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (foundRequestId != null) {
                        completePanelLogin(foundRequestId, finalIsServerOwner);
                    } else {
                        // No pending request found, just complete login directly
                        completeLogin(player);
                        String messageKey = finalIsServerOwner ? "panel_auth_success_owner" : "panel_auth_success_player";
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            UltiLogin.getInstance().i18n(messageKey)));
                    }
                });
            } catch (Exception e) {
                UltiLogin.getInstance().getLogger().debug("Auth poll error: " + e.getMessage());
            }
        }, 60L, 60L); // 60 ticks = 3 seconds

        pollingTasks.put(uuid, task);
    }

    /**
     * Handle a panel login completion (called when API Worker confirms auth via WebSocket).
     *
     * @param requestId the request ID
     * @return true if the player was successfully logged in
     */
    public boolean completePanelLogin(String requestId) {
        return completePanelLogin(requestId, false);
    }

    /**
     * Handle a panel login completion with role information.
     *
     * @param requestId the request ID
     * @param isServerOwner whether the user is a server owner
     * @return true if the player was successfully logged in
     */
    public boolean completePanelLogin(String requestId, boolean isServerOwner) {
        UUID playerUuid = pendingPanelRequests.get(requestId);
        if (playerUuid == null) {
            return false;
        }

        cleanupPanelRequest(requestId);

        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) {
            return false;
        }

        // Complete the login
        completeLogin(player);

        // Send role-specific message
        String messageKey = isServerOwner ? "panel_auth_success_owner" : "panel_auth_success_player";
        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
            UltiLogin.getInstance().i18n(messageKey)));

        // Update account last login
        AccountData account = getAccount(playerUuid);
        if (account != null) {
            account.setLastIp(getPlayerIp(player));
            account.setLastLogin(System.currentTimeMillis());
            account.setLoginCount(account.getLoginCount() + 1);
            try {
                dataOperator.update(account);
            } catch (IllegalAccessException e) {
                UltiLogin.getInstance().getLogger().error("Failed to update account after panel login", e);
            }
        }

        // Create session
        if (config.isSessionEnabled()) {
            String ip = getPlayerIp(player);
            sessions.put(ip + ":" + playerUuid, System.currentTimeMillis());
        }

        return true;
    }

    /**
     * Check if a player has a pending panel login request.
     */
    public boolean hasPendingPanelRequest(UUID playerUuid) {
        return pendingPanelRequests.containsValue(playerUuid);
    }

    /**
     * Clean up a panel request.
     */
    private void cleanupPanelRequest(String requestId) {
        pendingPanelRequests.remove(requestId);
        pendingPanelTimestamps.remove(requestId);
    }

    /**
     * Clean up expired panel requests (older than 5 minutes).
     */
    public void cleanupExpiredPanelRequests() {
        long now = System.currentTimeMillis();
        long expiry = 5 * 60 * 1000L; // 5 minutes
        pendingPanelTimestamps.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > expiry) {
                pendingPanelRequests.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * Result of a panel link request.
     */
    public static class PanelLinkResult {
        private final boolean success;
        private final String url;
        private final String error;

        public PanelLinkResult(boolean success, String url, String error) {
            this.success = success;
            this.url = url;
            this.error = error;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getUrl() {
            return url;
        }

        public String getError() {
            return error;
        }
    }

    /**
     * Result of login attempt.
     */
    public static class LoginResult {
        private final boolean success;
        private final String message;
        
        public LoginResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public String getMessage() {
            return message;
        }
    }
}
