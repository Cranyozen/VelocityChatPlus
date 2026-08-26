package com.velocitychat;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Manages plugin configuration and internationalization (i18n) messages.
 * <p>
 * Loads config.yml and language files (lang/*.yml) from the plugin's data directory.
 * Supports runtime reload and per-key overrides via config.yml's {@code messages:} section.
 */
public class ConfigManager {

    private final Logger logger;
    private final Path dataDirectory;
    private String currentLanguage = "zh_CN";

    // Raw config values
    private Map<String, Object> config;
    private final Map<String, String> messages = new LinkedHashMap<>();
    // 服务器别名: 小写服务器ID -> 原始显示名称 (直接写在 config.yml 的 server-aliases 中)
    private final Map<String, String> serverAliases = new HashMap<>();

    public ConfigManager(Logger logger, Path dataDirectory) {
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    /**
     * Load config.yml from the data directory.
     * If it doesn't exist, copy the built-in default.
     *
     * @return true if config was loaded successfully
     */
    @SuppressWarnings("unchecked")
    public boolean loadConfig() {
        Path configFile = dataDirectory.resolve("config.yml");

        // Write default config if not present
        if (!Files.exists(configFile)) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (in != null) {
                    Files.createDirectories(dataDirectory);
                    Files.copy(in, configFile);
                    logger.info("Generated default config.yml");
                }
            } catch (IOException e) {
                logger.warn("Could not write default config.yml", e);
            }
        }

        // Load config
        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                Yaml yaml = new Yaml();
                config = yaml.load(in);
                logger.info("Configuration loaded from config.yml");
            } catch (Exception e) {
                logger.warn("Failed to parse config.yml", e);
                config = new LinkedHashMap<>();
            }
        } else {
            config = new LinkedHashMap<>();
        }

        // Read language setting
        if (config.containsKey("language")) {
            currentLanguage = config.get("language").toString();
        }

        // Read server aliases map: 服务器ID -> 显示名称
        serverAliases.clear();
        if (config.get("server-aliases") instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (entry.getKey() instanceof String id && entry.getValue() != null) {
                    serverAliases.put(id.toLowerCase(), entry.getValue().toString());
                }
            }
        }

        // Load language file
        loadLanguage(currentLanguage);

        // Apply message overrides from config.yml
        if (config.get("messages") instanceof Map<?, ?> msgs) {
            for (var entry : msgs.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() instanceof String value) {
                    messages.put(key, value);
                }
            }
        }

        // Append any keys from the built-in default that are missing in the user's config
        mergeMissingConfig();

        return true;
    }

    /**
     * Load the specified language file.
     * <p>
     * Resolution order:
     * <ol>
     *   <li>Plugin data directory: {@code lang/&lt;language&gt;.yml} (user-customizable)</li>
     *   <li>Plugin jar resources: {@code lang/&lt;language&gt;.yml} (built-in)</li>
     * </ol>
     *
     * @param language Language code, e.g. "zh_CN", "en_US"
     */
    private void loadLanguage(String language) {
        this.currentLanguage = (language != null && !language.isBlank()) ? language : "zh_CN";

        Path langDir = dataDirectory.resolve("lang");
        try {
            Files.createDirectories(langDir);
        } catch (IOException e) {
            logger.warn("Failed to create lang/ directory", e);
        }

        // If language file doesn't exist in data directory, copy from resources
        Path langFile = langDir.resolve(this.currentLanguage + ".yml");
        if (!Files.exists(langFile)) {
            String resourcePath = "lang/" + this.currentLanguage + ".yml";
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (in != null) {
                    Files.copy(in, langFile);
                    logger.info("Generated language file: lang/{}.yml", this.currentLanguage);
                }
            } catch (Exception e) {
                logger.warn("Failed to copy built-in lang/{}.yml to data directory", this.currentLanguage, e);
            }
        }

        // Load from data directory first
        boolean loaded = false;
        if (Files.exists(langFile)) {
            try (InputStream in = Files.newInputStream(langFile)) {
                loaded = loadFromStream(in);
                if (loaded) {
                    logger.info("Loaded language file: lang/{}.yml", this.currentLanguage);
                }
            } catch (Exception e) {
                logger.warn("Failed to load lang/{}.yml from data directory", this.currentLanguage, e);
            }
        }

        // 兼容升级：旧版语言文件缺少新版本新增的键时，从内置资源补齐缺失键，
        // 用户自定义的键不会被覆盖 / Merge missing keys from the built-in resource so
        // older language files gain newly-added keys without overwriting customizations.
        if (loaded) {
            String resourcePath = "lang/" + this.currentLanguage + ".yml";
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (in != null) {
                    int merged = mergeMissingKeys(in);
                    if (merged > 0) {
                        logger.info("Merged {} missing key(s) from built-in {} into existing language file",
                                merged, resourcePath);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to merge built-in language keys", e);
            }
        }

        // Fall back to built-in resources
        if (!loaded) {
            String resourcePath = "lang/" + this.currentLanguage + ".yml";
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (in != null && loadFromStream(in)) {
                    logger.info("Loaded built-in language: {}", resourcePath);
                } else {
                    logger.error("Language file '{}' not found in plugin resources", resourcePath);
                }
            } catch (Exception e) {
                logger.error("Failed to load language file '{}' from resources", resourcePath, e);
            }
        }
    }

    /**
     * Reload config and language from disk.
     */
    public void reload() {
        messages.clear();
        loadConfig();
        logger.info("Configuration and language reloaded successfully");
    }

    // ── Message API ──────────────────────────────────────────

    /**
     * Get a localized message by key, with placeholders replaced.
     *
     * @param key        Message identifier (e.g. "qu_an.chat.message.connected")
     * @param placeholders Values to replace {0}, {1}, {2}, ... in the message
     * @return The formatted message string, or a visible error placeholder if missing
     */
    public String getMessage(String key, String... placeholders) {
        String msg = messages.get(key);
        if (msg == null) {
            logger.warn("Missing message key '{}' in language '{}'", key, currentLanguage);
            return "§cMissing message: " + key;
        }
        for (int i = 0; i < placeholders.length; i++) {
            if (placeholders[i] != null) {
                msg = msg.replace("{" + i + "}", placeholders[i]);
            }
        }
        return ColorUtils.translate(msg);
    }

    /**
     * Get the display name for a server.
     * <p>
     * Resolution order:
     * <ol>
     *   <li>config.yml {@code server-aliases} map (recommended — single-file config)</li>
     *   <li>language file alias (backward compatibility)</li>
     *   <li>raw server ID</li>
     * </ol>
     *
     * @param serverId The raw server ID (e.g. "lobby", "survival")
     * @return The display name with color codes translated
     */
    public String getServerDisplayName(String serverId) {
        if (serverId == null) return "§7unknown";

        // 优先从 config.yml 的 server-aliases map 读取显示名称
        String alias = serverAliases.get(serverId.toLowerCase());
        if (alias != null && !alias.isBlank()) {
            return ColorUtils.translate(alias);
        }

        // 兼容旧版本: 语言文件中定义的别名
        String aliasKey = "qu_an.chat.server.name." + serverId.toLowerCase();
        if (messages.containsKey(aliasKey)) {
            return ColorUtils.translate(messages.get(aliasKey));
        }

        // Return raw server ID with a default color
        return "§7" + serverId;
    }

    /**
     * Get the proxy display name.
     */
    public String getProxyName() {
        return getMessage("qu_an.chat.proxy.name");
    }

/**
     * Get the channel chat message format.
     * Config key: {@code channel-format}. Placeholders: {0}=channel, {1}=player, {2}=server, {3}=message
     */
    public String getChannelFormat() {
        if (config != null && config.containsKey("channel-format")) {
            Object v = config.get("channel-format");
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        return messages.getOrDefault("qu_an.chat.message.channel",
                "§d[§6{0}§d]§r[{1}§r]§r{2} §7>>§f {3}");
    }

    /**
     * Get the broadcast message format.
     * Config key: {@code broadcast-format}. Placeholders: {0}=sender, {1}=server, {2}=message
     */
    public String getBroadcastFormat() {
        if (config != null && config.containsKey("broadcast-format")) {
            Object v = config.get("broadcast-format");
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        return messages.getOrDefault("qu_an.chat.message.broadcast",
                "§6[Broadcast] §r{0}§f: {1}");
    }

    /**
     * Get broadcast command aliases from config.
     */
    @SuppressWarnings("unchecked")
    public List<String> getBroadcastAliases() {
        if (config != null && config.get("broadcast-aliases") instanceof List<?> list) {
            List<String> aliases = new ArrayList<>();
            for (Object o : list) {
                if (o != null) aliases.add(o.toString());
            }
            return aliases;
        }
        return Arrays.asList("br", "broadcast");
    }

    /**
     * Check if a server has a custom alias in the server-aliases map.
     */
    public boolean hasServerAlias(String serverId) {
        return serverId != null && serverAliases.containsKey(serverId.toLowerCase());
    }

    /**
     * Get the notify mode for join/switch/leave messages.
     *
     * @return "all" (everyone), "admin" (admins only), or "none" (disabled)
     */
    public String getNotifyMode() {
        if (config != null && config.containsKey("notify-mode")) {
            String mode = config.get("notify-mode").toString().toLowerCase();
            if (mode.equals("all") || mode.equals("admin") || mode.equals("none")) {
                return mode;
            }
        }
        return "all";
    }

    /**
     * Get the broadcast cooldown in seconds.
     * 0 means no cooldown.
     */
    public int getBroadcastCooldown() {
        if (config != null && config.containsKey("broadcast-cooldown")) {
            Object val = config.get("broadcast-cooldown");
            if (val instanceof Number n) return n.intValue();
        }
        return 0;
    }

    /**
     * Check if broadcast cooldown bypass is enabled for velocitychat.admin.
     */
    public boolean isCooldownBypassEnabled() {
        if (config != null && config.containsKey("broadcast-cooldown-bypass")) {
            Object val = config.get("broadcast-cooldown-bypass");
            if (val instanceof Boolean b) return b;
            if (val instanceof String s) return s.equalsIgnoreCase("true");
        }
        return false;
    }

    /**
     * Get the server-invite cooldown in seconds.
     * 0 means no cooldown.
     */
    public int getInviteCooldown() {
        if (config != null && config.containsKey("invite-cooldown")) {
            Object val = config.get("invite-cooldown");
            if (val instanceof Number n) return n.intValue();
        }
        return 0;
    }

    /**
     * Check if invite cooldown bypass is enabled.
     * When enabled, players with the configured bypass permission ignore the cooldown.
     */
    public boolean isInviteCooldownBypassEnabled() {
        if (config != null && config.containsKey("invite-cooldown-bypass")) {
            Object val = config.get("invite-cooldown-bypass");
            if (val instanceof Boolean b) return b;
            if (val instanceof String s) return s.equalsIgnoreCase("true");
        }
        return false;
    }

    /**
     * Get the permission node that bypasses the invite cooldown.
     */
    public String getInviteBypassPermission() {
        if (config != null && config.containsKey("invite-bypass-permission")) {
            Object val = config.get("invite-bypass-permission");
            if (val != null && !val.toString().isBlank()) return val.toString();
        }
        return "velocitychat.admin";
    }

    /**
     * Check if forbidden-word (违禁词) filtering is enabled.
     * Defaults to false when the key is absent (keeps older configs safe).
     */
    public boolean isForbiddenWordsEnabled() {
        if (config != null && config.containsKey("forbidden-words-enabled")) {
            Object val = config.get("forbidden-words-enabled");
            if (val instanceof Boolean b) return b;
            if (val instanceof String s) return s.equalsIgnoreCase("true");
        }
        return false;
    }

    /**
     * Get the permission that bypasses the forbidden-word filter.
     */
    public String getForbiddenWordsBypassPermission() {
        if (config != null && config.containsKey("forbidden-words-bypass-permission")) {
            Object val = config.get("forbidden-words-bypass-permission");
            if (val != null && !val.toString().isBlank()) return val.toString();
        }
        return "velocitychat.admin";
    }

    // ── 分区聊天 / Channels ──────────────────────────────────

    /**
     * Whether the channel (分区聊天) feature is enabled at all.
     * Defaults to false when absent (keeps older configs safe).
     */
    public boolean isChannelsEnabled() {
        return getBoolean("channels-enabled", false);
    }

    /**
     * Whether normal player chat is routed through the proxy into the
     * player's active channel. When disabled, only {@code /ch <message>}
     * sends to a channel and normal chat goes straight to the backend.
     */
    public boolean isRouteChatEnabled() {
        return getBoolean("route-chat", true);
    }

    /**
     * Get the {@code channels:} preset map from config.yml (used to seed
     * channels.yml on first run). Returns null if not configured.
     */
    public Map<?, ?> getChannelsPresets() {
        if (config != null && config.get("channels") instanceof Map<?, ?> map) {
            return map;
        }
        return null;
    }

    /**
     * Get the channel chat message format.
     * <p>
     * Placeholders: {0}=channel, {1}=player, {2}=server, {3}=message
     */
    public String getChannelFormat() {
        return messages.getOrDefault("qu_an.chat.message.channel",
                "§d[§6{0}§d]§r[{1}§r]§r{2} §7>>§f {3}");
    }

    // ── 自定义 TabList ───────────────────────────────────────

    /**
     * Whether the custom TabList feature is enabled.
     */
    public boolean isTabListEnabled() {
        if (config != null && config.get("tablist") instanceof Map<?, ?> tab) {
            Object en = tab.get("enabled");
            if (en instanceof Boolean b) return b;
            if (en instanceof String s) return s.equalsIgnoreCase("true");
            return true; // tablist section present without explicit flag = enabled
        }
        return false;
    }

    /**
     * Get the TabList header text (with & color codes and placeholders).
     */
    public String getTabListHeader() {
        if (config != null && config.get("tablist") instanceof Map<?, ?> tab) {
            Object h = tab.get("header");
            if (h != null && !h.toString().isBlank()) return h.toString();
        }
        return "&6VelocityChat";
    }

    /**
     * Get the TabList footer text (with & color codes and placeholders).
     */
    public String getTabListFooter() {
        if (config != null && config.get("tablist") instanceof Map<?, ?> tab) {
            Object f = tab.get("footer");
            if (f != null && !f.toString().isBlank()) return f.toString();
        }
        return "&7在线 &a{online} &r&7人";
    }

    /**
     * Get the per-entry display format for the TabList.
     * <p>
     * Placeholders:
     * <ul>
     *   <li>{server} - the server's display name (from server-aliases)</li>
     *   <li>{player} - the player's username (or the bot's name for fake players)</li>
     *   <li>{title}  - the player's group title, empty if they have none</li>
     *   <li>{group}  - the player's group name, empty if they have none</li>
     * </ul>
     */
    public String getTabListEntryFormat() {
        if (config != null && config.get("tablist") instanceof Map<?, ?> tab) {
            Object f = tab.get("entry-format");
            if (f != null && !f.toString().isBlank()) return f.toString();
        }
        return "{server}§8▏ &r{title}{player}";
    }

    /**
     * Get the per-entry display format for backend-injected fake entries such as
     * Carpet bots. Defaults to {@link #getTabListEntryFormat()} so it can be left
     * empty to share the same template as real players.
     */
    public String getTabListBotFormat() {
        if (config != null && config.get("tablist") instanceof Map<?, ?> tab) {
            Object f = tab.get("bot-format");
            if (f != null && !f.toString().isBlank()) return f.toString();
        }
        return getTabListEntryFormat();
    }

    /**
     * Whether the plugin should own the tab list header/footer region.
     * <p>
     * Set to {@code false} to hand the header/footer over to the backend — e.g. when
     * a mod like Carpet displays its live {@code /log} output in the tab footer. The
     * plugin will then only refresh player entries and never touch header/footer,
     * avoiding the flicker caused by the proxy racing the backend for that region.
     */
    public boolean isTabListManageHeaderFooter() {
        if (config != null && config.get("tablist") instanceof Map<?, ?> tab) {
            Object v = tab.get("manage-header-footer");
            if (v instanceof Boolean b) return b;
            if (v instanceof String s) return s.equalsIgnoreCase("true");
        }
        return true;
    }

    /**
     * Get the tab list refresh interval in seconds.
     * Minimum 1 second to avoid excessive network traffic.
     */
    public long getTabListRefreshInterval() {
        if (config != null && config.get("tablist") instanceof Map<?, ?> tab) {
            Object v = tab.get("refresh-interval");
            if (v instanceof Number n) return Math.max(1, n.longValue());
            if (v instanceof String s) {
                try { return Math.max(1, Long.parseLong(s.trim())); }
                catch (NumberFormatException ignored) {}
            }
        }
        return 3L;
    }

    // ── Internal Helpers ─────────────────────────────────────

    private boolean getBoolean(String key, boolean def) {
        if (config != null && config.containsKey(key)) {
            Object val = config.get(key);
            if (val instanceof Boolean b) return b;
            if (val instanceof String s) return s.equalsIgnoreCase("true");
        }
        return def;
    }

    /**
     * Merge keys from a built-in language resource that are missing from the current
     * messages map. Existing keys (including user customizations) are never overwritten.
     *
     * @param in InputStream of a built-in language resource
     * @return the number of newly merged keys
     */
    @SuppressWarnings("unchecked")
    private int mergeMissingKeys(InputStream in) {
        int merged = 0;
        Yaml yaml = new Yaml();
        Object loaded = yaml.load(in);
        if (loaded instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() != null
                        && !messages.containsKey(key)) {
                    messages.put(key, entry.getValue().toString());
                    merged++;
                }
            }
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private boolean loadFromStream(InputStream in) {
        Yaml yaml = new Yaml();
        Object loaded = yaml.load(in);
        if (loaded instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() instanceof String value) {
                    messages.put(key, value);
                } else if (entry.getKey() instanceof String key && entry.getValue() != null) {
                    messages.put(key, entry.getValue().toString());
                }
            }
            return !messages.isEmpty();
        }
        return false;
    }

    // ── Auto-merge missing config keys ────────────────────────

    /**
     * Compare the user's config.yml against the built-in default and append any missing
     * keys so the file stays up-to-date after a plugin upgrade.  Existing keys and
     * comments are never modified — only truly new keys are added at the end of their
     * parent section (or at the file end for new top-level sections).
     */
    @SuppressWarnings("unchecked")
    public void mergeMissingConfig() {
        Path configFile = dataDirectory.resolve("config.yml");
        if (!Files.exists(configFile) || config == null) {
            logger.info("[ConfigMerge] skipped: file exists={}, config loaded={}", Files.exists(configFile), config != null);
            return;
        }

        try {
            // Load the built-in default config as text and as a YAML map
            String defaultText;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (in == null) {
                    logger.warn("[ConfigMerge] default config.yml not found in resources");
                    return;
                }
                defaultText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Yaml yaml = new Yaml();
            Map<String, Object> defaultMap = yaml.load(defaultText);
            if (defaultMap == null) {
                logger.warn("[ConfigMerge] default config.yml parsed as null");
                return;
            }

            // Find all missing key paths
            List<String> missing = new ArrayList<>();
            findMissingKeys(defaultMap, config, "", missing);
            logger.info("[ConfigMerge] found {} missing key(s): {}", missing.size(), missing);
            if (missing.isEmpty()) return;

            // Load the user's config file as lines
            List<String> userLines = new ArrayList<>(Files.readAllLines(configFile));

            // Extract text blocks from the default config
            Map<String, String> defaultBlocks = extractDefaultBlocks(defaultText);
            logger.info("[ConfigMerge] extracted {} default block(s): {}", defaultBlocks.size(), defaultBlocks.keySet());

            // For each missing key, find the correct insertion point by scanning the
            // user's config lines.  We locate the nearest preceding top-level key and
            // insert right after its section ends (last content line before the next
            // blank line or top-level key).
            TreeMap<Integer, List<String>> insertions = new TreeMap<>(Collections.reverseOrder());
            int inserted = 0;
            for (String path : missing) {
                String textBlock = defaultBlocks.get(path);
                if (textBlock == null) {
                    logger.warn("[ConfigMerge] no text block found for missing key '{}'", path);
                    continue;
                }

                String sectionKey = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
                int insertAt = findInsertionPoint(userLines, sectionKey);
                insertions.computeIfAbsent(insertAt, k -> new ArrayList<>()).add(textBlock);
                inserted++;
                logger.info("[ConfigMerge] queued '{}' for insertion at line {}", path, insertAt);
            }

            // Perform insertions
            for (var entry : insertions.entrySet()) {
                int idx = entry.getKey();
                List<String> blocks = entry.getValue();
                if (idx < userLines.size() && !userLines.get(idx - 1).isBlank()) {
                    userLines.add(idx, "");
                    idx++;
                }
                int offset = 0;
                for (String block : blocks) {
                    for (String bl : block.split("\n", -1)) {
                        userLines.add(idx + offset, bl);
                        offset++;
                    }
                }
            }

            Files.writeString(configFile, String.join("\n", userLines));
            logger.info("[ConfigMerge] wrote {} merged key(s) to config.yml", inserted);
        } catch (Exception e) {
            logger.warn("[ConfigMerge] failed to auto-merge config.yml", e);
        }
    }

    /**
     * Recursively find key paths present in {@code def} but missing from {@code user}.
     */
    @SuppressWarnings("unchecked")
    private void findMissingKeys(Map<String, Object> def, Map<String, Object> user,
                                 String prefix, List<String> out) {
        for (var entry : def.entrySet()) {
            String key = entry.getKey();
            if (key == null) continue;
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object defVal = entry.getValue();
            Object userVal = user.get(key);

            if (userVal == null) {
                out.add(path);
            } else if (defVal instanceof Map && userVal instanceof Map) {
                findMissingKeys((Map<String, Object>) defVal, (Map<String, Object>) userVal, path, out);
            }
        }
    }

    /**
     * Parse the default config text into text blocks keyed by full YAML path.
     * Each block includes any preceding comment lines and the key line itself,
     * preserving the original indentation so it can be directly inserted into
     * the user's config file.
     */
    private static Map<String, String> extractDefaultBlocks(String text) {
        Map<String, String> blocks = new LinkedHashMap<>();
        String[] lines = text.split("\n", -1);

        String section = null;
        List<String> currentComments = new ArrayList<>();  // comments for the current block
        List<String> incomingComments = new ArrayList<>();  // comments accumulated for the next block
        StringBuilder currentBlock = new StringBuilder();
        String currentPath = null;

        for (String line : lines) {
            String trimmed = line.stripLeading();

            if (trimmed.isEmpty()) {
                // Blank line: finalize current block with ALL accumulated comments
                if (currentPath != null && currentBlock.length() > 0) {
                    currentComments.addAll(incomingComments);
                    incomingComments.clear();
                    finalizeBlock(blocks, currentPath, currentComments, currentBlock);
                } else {
                    incomingComments.clear();
                }
                currentPath = null;
                currentBlock.setLength(0);
                currentComments.clear();
                continue;
            }

            if (trimmed.startsWith("#")) {
                incomingComments.add(line);
                continue;
            }

            int indent = line.length() - trimmed.length();
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                incomingComments.clear();
                continue;
            }

            String key = trimmed.substring(0, colon).trim();

            if (indent == 0) {
                String valuePart = trimmed.substring(colon + 1).trim();
                if (!valuePart.isEmpty()) {
                    // Top-level key with inline value.
                    // Finalize previous block with its own comments (not incoming).
                    if (currentPath != null && currentBlock.length() > 0) {
                        finalizeBlock(blocks, currentPath, currentComments, currentBlock);
                    }
                    // The incoming comments belong to THIS key — promote them.
                    currentComments.clear();
                    currentComments.addAll(incomingComments);
                    incomingComments.clear();
                    currentPath = key;
                    currentBlock = new StringBuilder();
                    currentBlock.append(line).append("\n");
                } else {
                    // Section key (e.g. "tablist:")
                    if (currentPath != null && currentBlock.length() > 0) {
                        currentComments.addAll(incomingComments);
                        incomingComments.clear();
                        finalizeBlock(blocks, currentPath, currentComments, currentBlock);
                    } else {
                        incomingComments.clear();
                    }
                    section = key;
                    currentPath = null;
                    currentBlock.setLength(0);
                    currentComments.clear();
                }
            } else if (section != null) {
                // Sub-key within a section — finalize previous sub-key with its
                // OWN comments only (incoming belong to THIS key, not the previous).
                if (currentPath != null && currentBlock.length() > 0) {
                    finalizeBlock(blocks, currentPath, currentComments, currentBlock);
                }
                currentComments.clear();
                currentComments.addAll(incomingComments);
                incomingComments.clear();
                currentPath = section + "." + key;
                currentBlock = new StringBuilder();
                currentBlock.append(line).append("\n");
            }
        }

        // Finalize the last block
        if (currentPath != null && currentBlock.length() > 0) {
            currentComments.addAll(incomingComments);
            incomingComments.clear();
            finalizeBlock(blocks, currentPath, currentComments, currentBlock);
        }
        return blocks;
    }

    private static void finalizeBlock(Map<String, String> blocks, String path,
                                      List<String> comments, StringBuilder block) {
        StringBuilder full = new StringBuilder();
        for (String c : comments) full.append(c).append("\n");
        full.append(block);
        blocks.put(path, full.toString());
        block.setLength(0);
        comments.clear();
    }

    /**
     * Find the line index after which a new key should be inserted in the user's
     * config.  Scans backward from the end to find the nearest preceding top-level
     * key whose section contains the given key, then returns the line after that
     * section's last content line.
     */
    private static int findInsertionPoint(List<String> lines, String key) {
        // Scan backward to find the nearest preceding top-level key
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            String trimmed = line.stripLeading();
            boolean isTopKey = !trimmed.isEmpty() && !trimmed.startsWith("#")
                    && !trimmed.startsWith("-") && line.length() == trimmed.length()
                    && trimmed.contains(":");
            if (!isTopKey) continue;

            String topKey = trimmed.substring(0, trimmed.indexOf(':')).trim();
            // Found a preceding top-level key — find the end of its section
            if (topKey.compareTo(key) <= 0) {
                int lastContent = i;
                for (int j = i + 1; j < lines.size(); j++) {
                    String l = lines.get(j).stripLeading();
                    if (l.isEmpty()) break; // blank line = section boundary
                    // Another top-level key = new section
                    if (!l.startsWith("#") && !l.startsWith(" ") && !l.startsWith("\t")
                            && lines.get(j).length() == l.length() && l.contains(":")) break;
                    lastContent = j;
                }
                return lastContent + 1;
            }
        }
        return lines.size(); // no preceding key found — append at end
    }
}
