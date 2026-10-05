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
    // 配置文件是否成功解析：解析失败时不做合并，避免把默认值写进一个损坏的文件
    // Whether config.yml parsed cleanly — a broken file must never be merged into.
    private boolean configParsed;
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
        configParsed = false;
        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                Yaml yaml = new Yaml();
                config = yaml.load(in);
                if (config == null) config = new LinkedHashMap<>();
                configParsed = true;
                logger.info("Configuration loaded from config.yml");
            } catch (Exception e) {
                logger.warn("Failed to parse config.yml", e);
                config = new LinkedHashMap<>();
            }
        } else {
            config = new LinkedHashMap<>();
        }

        // 先把内置默认中新增的键合并进配置文件，再读取任何设置，
        // 这样语言、别名、消息与运行期行为都以合并后的配置为准
        // Merge the keys newer plugin versions added before anything reads the config,
        // so language, aliases, messages and runtime behaviour all see them.
        mergeMissingConfig();

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
     * Get the personal notification message when a player joins the proxy.
     * Placeholders: {player}=player name, {server}=server display name
     */
    public String getJoinProxyMessage() {
        if (config != null && config.containsKey("join-proxy-message")) {
            Object v = config.get("join-proxy-message");
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        return "§a欢迎来到服务器！你当前在 {server}";
    }

    /**
     * Get the personal notification message when a player joins a backend server.
     * Supports per-server override: join-server-messages.{serverId}
     * Placeholders: {player}=player name, {server}=server display name
     */
    public String getJoinServerMessage(String serverId) {
        // Check per-server override first
        if (serverId != null && config != null
                && config.get("join-server-messages") instanceof Map<?, ?> map) {
            Object v = map.get(serverId);
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        // Fall back to global default
        if (config != null && config.containsKey("join-server-message")) {
            Object v = config.get("join-server-message");
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        return "§a你已进入 §e{server}";
    }

/**
     * Get the channel chat message format.
     * Config key: {@code channel-format}.
     * Placeholders: {channel}=channel, {player}=sender, {title}=group title, {server}=server, {message}=message
     */
    public String getChannelFormat() {
        if (config != null && config.containsKey("channel-format")) {
            Object v = config.get("channel-format");
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        return messages.getOrDefault("qu_an.chat.message.channel",
                "§d[§6{channel}§d]§r[{title}{player}§r]§r{server} §7>>§f {message}");
    }

    /**
     * Get the broadcast message format.
     * Config key: {@code broadcast-format}.
     * Placeholders: {player}=sender, {title}=group title, {server}=server, {message}=message
     */
    public String getBroadcastFormat() {
        if (config != null && config.containsKey("broadcast-format")) {
            Object v = config.get("broadcast-format");
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        return messages.getOrDefault("qu_an.chat.message.broadcast",
                "§6[Broadcast] §r{title}{player}§f: §7[{server}]§f {message}");
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
     * Get the route-chat mode.
     * Values: "channel" (deny backend, only channel format),
     *         "both" (no deny, both formats — duplicate for same-server),
     *         "channel-log" (deny backend + log to proxy console)
     */
    public String getRouteChatMode() {
        if (config != null && config.containsKey("route-chat-mode")) {
            Object v = config.get("route-chat-mode");
            if (v != null) return v.toString();
        }
        return "both";
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
        // return "&7在线 &a{online} &r&7人";
        return "";
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
     * 内容归用户自己维护的节：插件只在这个节整体缺失时按默认生成一次，
     * 之后不会再把它里面被用户删掉的条目补回来（例如删掉的预设服务器别名）。
     * <p>
     * Sections whose entries belong to the user rather than to the plugin's options: the
     * merge introduces such a section when it is missing entirely, but never re-adds
     * entries the user has deleted from it.
     */
    private static final Set<String> USER_MAINTAINED_SECTIONS =
            Set.of("server-aliases", "join-server-messages", "channels", "groups", "messages");

    /**
     * Compare the user's config.yml against the built-in default and append the keys the
     * default has gained since the file was written, so an existing config keeps working —
     * and keeps growing — after a plugin upgrade.
     * <p>
     * Existing keys, values, comments and line endings are never changed: a missing
     * sub-key is inserted next to its siblings inside the section that already holds its
     * parent, and a whole missing section is written out with all of its keys. Sections
     * listed in {@link #USER_MAINTAINED_SECTIONS} keep whatever entries the user gave them.
     * The in-memory config is then refreshed from the merged text, so what the plugin runs
     * with is exactly what the file says.
     */
    @SuppressWarnings("unchecked")
    public void mergeMissingConfig() {
        Path configFile = dataDirectory.resolve("config.yml");
        if (!Files.exists(configFile) || config == null) {
            logger.debug("[ConfigMerge] skipped: file exists={}, config loaded={}",
                    Files.exists(configFile), config != null);
            return;
        }
        if (!configParsed) {
            // 解析失败时绝不动文件，否则只会把默认值追加到一个已损坏的配置里
            // Never touch a file that did not parse — appending defaults would only make it worse.
            logger.warn("[ConfigMerge] skipped: config.yml could not be parsed");
            return;
        }

        try {
            String defaultText = readBuiltinConfig();
            if (defaultText == null) return;

            Map<String, Object> defaultMap = new Yaml().load(normalizeLineEndings(defaultText));
            if (defaultMap == null) {
                logger.warn("[ConfigMerge] default config.yml parsed as null");
                return;
            }

            String userText = Files.readString(configFile, StandardCharsets.UTF_8);
            List<String> missing = new ArrayList<>();
            findMissingKeys(defaultMap, config, "", missing);
            if (missing.isEmpty()) {
                logger.debug("[ConfigMerge] config.yml is already up to date");
                return;
            }

            String merged = insertMissingBlocks(defaultText, userText, missing);
            if (merged.equals(userText)) return;

            Files.writeString(configFile, merged, StandardCharsets.UTF_8);
            logger.info("[ConfigMerge] merged {} missing config key(s) into config.yml: {}",
                    missing.size(), missing);

            // 重新解析合并后的文本，保证运行中的配置与磁盘上的文件一致
            // Re-parse the merged text so the running config matches the file on disk.
            Object reloaded = new Yaml().load(normalizeLineEndings(merged));
            if (reloaded instanceof Map<?, ?> map) {
                config = (Map<String, Object>) map;
            }
        } catch (Exception e) {
            logger.warn("[ConfigMerge] failed to auto-merge config.yml", e);
        }
    }

    /** Read the built-in default config.yml, or {@code null} when it is unavailable. */
    private String readBuiltinConfig() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            if (in == null) {
                logger.warn("[ConfigMerge] default config.yml not found in resources");
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("[ConfigMerge] could not read the default config.yml", e);
            return null;
        }
    }

    /**
     * Recursively find key paths present in {@code def} but missing from {@code user}.
     * <p>
     * A section the user declared without any sub-keys (a bare {@code tablist:}) is
     * descended into as if it were empty, so its keys end up underneath it instead of a
     * second, duplicate section key being appended to the file. Sections in
     * {@link #USER_MAINTAINED_SECTIONS} are never descended into: their entries are the
     * user's, so only the section itself can be a missing key.
     */
    @SuppressWarnings("unchecked")
    private void findMissingKeys(Map<String, Object> def, Map<String, Object> user,
                                 String prefix, List<String> out) {
        for (var entry : def.entrySet()) {
            String key = entry.getKey();
            if (key == null) continue;
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object defVal = entry.getValue();
            boolean declaredByUser = user.containsKey(key);
            Object userVal = user.get(key);
            boolean userMaintained = USER_MAINTAINED_SECTIONS.contains(path);

            if (defVal instanceof Map && declaredByUser && !userMaintained
                    && (userVal == null || userVal instanceof Map)) {
                Map<String, Object> userSection = userVal instanceof Map
                        ? (Map<String, Object>) userVal
                        : Collections.emptyMap();
                findMissingKeys((Map<String, Object>) defVal, userSection, path, out);
            } else if (!declaredByUser) {
                out.add(path);
            }
        }
    }

    /**
     * Copy the default block of every missing key into {@code userText}. Existing lines are
     * only ever pushed down by an insertion — never rewritten, reordered or removed.
     *
     * @return the merged config text, using the line endings the user's file already had
     */
    private String insertMissingBlocks(String defaultText, String userText, List<String> missing) {
        String defaultYaml = normalizeLineEndings(defaultText);
        String userYaml = normalizeLineEndings(userText);

        YamlTextTree defaultTree = YamlTextTree.parse(defaultYaml);
        YamlTextTree userTree = YamlTextTree.parse(userYaml);
        List<String> lines = new ArrayList<>(Arrays.asList(userYaml.split("\n", -1)));

        // 多个缺失键可能落在同一个插入点：按默认文件中的顺序分组保存
        // Several missing keys can share one insertion point — keep them in default order.
        Map<Integer, List<String>> insertions = new TreeMap<>();
        for (String path : missing) {
            YamlNode defaultNode = defaultTree.find(path);
            if (defaultNode == null) {
                logger.warn("[ConfigMerge] no default text block found for missing key '{}'", path);
                continue;
            }
            int at = insertionLine(path, defaultTree, userTree, lines.size());
            if (at < 0) {
                logger.warn("[ConfigMerge] could not place missing key '{}' safely, skipping it", path);
                continue;
            }
            insertions.computeIfAbsent(at, k -> new ArrayList<>())
                    .addAll(reindentForUser(path, defaultNode, userTree));
        }

        // 从文件末尾往前插入，这样前面算好的行号不会被后面的插入打乱
        // Insert bottom-up so the line numbers computed above stay valid.
        List<Integer> positions = new ArrayList<>(insertions.keySet());
        positions.sort(Comparator.reverseOrder());
        for (int at : positions) {
            insertBlock(lines, at, insertions.get(at));
        }

        String merged = String.join("\n", lines);
        if (userYaml.endsWith("\n") && !merged.endsWith("\n")) {
            merged = merged + "\n"; // keep the trailing newline the file already had
        }
        return userText.contains("\r\n") ? merged.replace("\n", "\r\n") : merged;
    }

    /**
     * The default block of {@code path}, ready to insert: without its leading blank lines and
     * indented the way the user's config already indents that section.
     */
    private static List<String> reindentForUser(String path, YamlNode defaultNode, YamlTextTree userTree) {
        List<String> block = withoutLeadingBlankLines(defaultNode.text);
        int lastDot = path.lastIndexOf('.');
        if (lastDot < 0) return block; // a top-level key is always at column zero

        YamlNode parentInUser = userTree.find(path.substring(0, lastDot));
        if (parentInUser == null) return block;
        int userIndent = parentInUser.firstChildIndent(defaultNode.indent);
        if (userIndent == defaultNode.indent) return block;

        // 用户可能把某个节缩进成 4 个空格：新增的子键必须跟随该节的缩进，否则文件无法解析
        // The user may indent a section differently — the added keys have to follow it, or YAML breaks.
        List<String> shifted = new ArrayList<>(block.size());
        for (String line : block) {
            if (line.isBlank()) {
                shifted.add(line);
                continue;
            }
            int strip = 0;
            while (strip < defaultNode.indent && strip < line.length() && line.charAt(strip) == ' ') {
                strip++;
            }
            shifted.add(" ".repeat(userIndent) + line.substring(strip));
        }
        return shifted;
    }

    /**
     * Drop the blank lines a default block starts with: the blank line above a section is a
     * separator, and {@link #insertBlock} decides where one is needed.
     */
    private static List<String> withoutLeadingBlankLines(List<String> block) {
        int start = 0;
        while (start < block.size() && block.get(start).isBlank()) {
            start++;
        }
        return block.subList(start, block.size());
    }

    /**
     * Insert one block of default text, keeping the file readable: a new top-level key is
     * separated from the line above it, and a blank line separates the block from a
     * following top-level key or comment.
     */
    private static void insertBlock(List<String> lines, int at, List<String> block) {
        List<String> addition = new ArrayList<>();
        if (startsTopLevelKey(block) && at > 0 && !lines.get(at - 1).isBlank()) {
            addition.add("");
        }
        addition.addAll(block);
        if (at < lines.size() && !lines.get(at).isBlank() && !isIndented(lines.get(at))) {
            addition.add("");
        }
        lines.addAll(at, addition);
    }

    /** Whether the first content line of {@code block} is an unindented (top-level) key. */
    private static boolean startsTopLevelKey(List<String> block) {
        for (String line : block) {
            String trimmed = line.stripLeading();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            return line.length() == trimmed.length();
        }
        return true;
    }

    private static boolean isIndented(String line) {
        return !line.isEmpty() && Character.isWhitespace(line.charAt(0));
    }

    /**
     * Line at which the default block for {@code path} belongs in the user's config: right
     * after the nearest sibling that precedes it in the default config and already exists
     * in the user's file, or — when none does — immediately before the nearest existing
     * sibling that follows it. That preserves the default config's own ordering.
     *
     * @return the line index, or -1 when the key cannot be placed without risking damage
     */
    private static int insertionLine(String path, YamlTextTree defaults, YamlTextTree user, int endOfFile) {
        int lastDot = path.lastIndexOf('.');
        String parentPath = lastDot < 0 ? "" : path.substring(0, lastDot);
        String key = lastDot < 0 ? path : path.substring(lastDot + 1);

        YamlNode parentInDefaults = defaults.find(parentPath);
        YamlNode parentInUser = user.find(parentPath);
        if (parentInDefaults == null || parentInUser == null) return -1;
        // 用户用 {…} 行内写法声明的节无法安全地在下面追加缩进子键，跳过它
        // A section the user wrote inline ({...}) cannot take indented children underneath it.
        if (parentInUser != user.root && hasInlineValue(parentInUser.keyLine)) return -1;

        List<String> siblings = new ArrayList<>(parentInDefaults.children.keySet());
        int index = siblings.indexOf(key);
        if (index < 0) return -1;

        for (int i = index - 1; i >= 0; i--) {
            YamlNode preceding = user.find(childPath(parentPath, siblings.get(i)));
            if (preceding != null) return preceding.lastLine + 1;
        }
        for (int i = index + 1; i < siblings.size(); i++) {
            YamlNode following = user.find(childPath(parentPath, siblings.get(i)));
            if (following != null) return following.firstLine;
        }
        // 没有同级键可以依附：顶级键追加到文件末尾，子键放到所属节的末尾
        // Nothing to anchor to: append top-level keys, extend a section at its end.
        if (parentInUser == user.root) return endOfFile;
        return parentInUser.lastLine + 1;
    }

    private static String childPath(String parentPath, String key) {
        return parentPath.isEmpty() ? key : parentPath + "." + key;
    }

    /** Whether a key line carries its value on the same line instead of starting a block. */
    private static boolean hasInlineValue(String keyLine) {
        int colon = keyLine.indexOf(':');
        if (colon < 0) return false;
        String value = keyLine.substring(colon + 1).trim();
        return !value.isEmpty() && !value.startsWith("#");
    }

    /** Strip CR so blocks can be handled uniformly; the original endings are restored later. */
    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * One key of a YAML document: the comments written above it plus every line of its
     * subtree, in document order, so the block can be copied into another file verbatim.
     */
    private static final class YamlNode {
        final String path;
        final int indent;
        final String keyLine;
        final List<String> text = new ArrayList<>();
        final Map<String, YamlNode> children = new LinkedHashMap<>();
        int firstLine = -1;
        int lastLine = -1;

        YamlNode(String path, int indent, String keyLine) {
            this.path = path;
            this.indent = indent;
            this.keyLine = keyLine;
        }

        /** The indentation this node's existing children use, or {@code fallback} if it has none. */
        int firstChildIndent(int fallback) {
            for (YamlNode child : children.values()) {
                return child.indent;
            }
            return fallback;
        }
    }

    /** Line-oriented view of a YAML document, used to lift default blocks into a user config. */
    private static final class YamlTextTree {
        final YamlNode root = new YamlNode("", -1, "");

        static YamlTextTree parse(String text) {
            YamlTextTree tree = new YamlTextTree();
            Deque<YamlNode> stack = new ArrayDeque<>();
            stack.push(tree.root);
            List<String> pending = new ArrayList<>();

            List<String> lines = Arrays.asList(text.split("\n", -1));
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.stripLeading();

                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    pending.add(line);
                    continue;
                }

                int indent = line.length() - trimmed.length();
                int colon = trimmed.indexOf(':');
                boolean sequenceItem = trimmed.startsWith("- ") || trimmed.equals("-");

                if (colon > 0 && !sequenceItem) {
                    while (stack.size() > 1 && stack.peek().indent >= indent) {
                        stack.pop();
                    }
                    YamlNode parent = stack.peek();
                    String key = trimmed.substring(0, colon).trim();
                    YamlNode node = new YamlNode(childPath(parent.path, key), indent, line);
                    node.firstLine = i - pending.size();
                    // 这一行（连同其上方的注释）既属于新键，也属于它所在的每一个节，
                    // 这样一个节的文本块总是完整包含它的所有子键
                    // The line and the comments above it belong to the new key *and* to every
                    // enclosing section, so a section's block always holds all of its keys.
                    appendToEnclosing(stack, tree.root, pending, line);

                    node.text.addAll(pending);
                    node.text.add(line);
                    parent.children.put(key, node);
                    stack.push(node);
                    mark(stack, i);
                } else {
                    // 值续行或列表项：属于当前块 / a value continuation or list item of the current block
                    if (stack.peek() != tree.root && indent > stack.peek().indent) {
                        appendToEnclosing(stack, tree.root, pending, line);
                        mark(stack, i);
                    }
                }
                pending.clear();
            }
            return tree;
        }

        private static void mark(Deque<YamlNode> stack, int line) {
            for (YamlNode node : stack) {
                if (node.lastLine < line) node.lastLine = line;
            }
        }

        /** Append a line (and the comments above it) to every enclosing section, root excluded. */
        private static void appendToEnclosing(Deque<YamlNode> stack, YamlNode root,
                                              List<String> pending, String line) {
            for (YamlNode enclosing : stack) {
                if (enclosing == root) continue;
                enclosing.text.addAll(pending);
                enclosing.text.add(line);
            }
        }

        /** The block for a dotted key path, or {@code null} when this document has no such key. */
        YamlNode find(String path) {
            return find(root, path);
        }

        private static YamlNode find(YamlNode node, String path) {
            if (node.path.equals(path)) return node;
            for (YamlNode child : node.children.values()) {
                YamlNode found = find(child, path);
                if (found != null) return found;
            }
            return null;
        }
    }
}
