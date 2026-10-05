package com.velocitychat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the automatic {@code config.yml} merge.
 * <p>
 * After a plugin update the keys added to the built-in default config must be appended to
 * the user's existing file — with their comments, without ever rewriting what the user
 * already wrote, and with the running configuration matching the file that was written.
 */
class ConfigManagerMergeTest {

    @TempDir
    Path dataDirectory;

    // ── fixture helpers ──────────────────────────────────────

    /** The built-in default config shipped inside the plugin jar. */
    private static String builtinDefault() throws Exception {
        try (InputStream in = ConfigManagerMergeTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertNotNull(in, "built-in config.yml must be on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Comment out the line declaring {@code key} so the file looks like a config written by
     * a plugin version that did not have that key yet.
     */
    private static String commentOutKey(String yaml, String key, int indent) {
        String prefix = " ".repeat(indent) + key + ":";
        List<String> all = new ArrayList<>(List.of(yaml.split("\n", -1)));
        if (!all.isEmpty() && all.get(all.size() - 1).isEmpty()) {
            all.remove(all.size() - 1); // drop the empty tail split() adds, keep the newline below
        }
        StringBuilder out = new StringBuilder();
        for (String line : all) {
            if (!line.stripLeading().startsWith("#") && line.startsWith(prefix)) {
                out.append(" ".repeat(indent)).append("# ").append(line.stripLeading());
            } else {
                out.append(line);
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Write {@code userConfig} as config.yml, load it through the plugin, return the manager. */
    private ConfigManager loadUserConfig(String userConfig) throws Exception {
        Files.writeString(dataDirectory.resolve("config.yml"), userConfig, StandardCharsets.UTF_8);
        ConfigManager manager = new ConfigManager(LoggerFactory.getLogger(getClass()), dataDirectory);
        assertTrue(manager.loadConfig(), "loadConfig() should succeed");
        return manager;
    }

    /** Load {@code userConfig} and return the merged config.yml text. */
    private String loadAndReadBack(String userConfig) throws Exception {
        loadUserConfig(userConfig);
        return Files.readString(dataDirectory.resolve("config.yml"), StandardCharsets.UTF_8);
    }

    // ── YAML helpers ─────────────────────────────────────────

    /** Parse YAML, rejecting duplicate keys — a merge must never write a key twice. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String yamlText) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object loaded = new Yaml(options).load(yamlText);
        return loaded == null ? new LinkedHashMap<>() : (Map<String, Object>) loaded;
    }

    /**
     * Reference implementation of "defaults, overridden by the user": every key of
     * {@code defaults} survives, the user's values win, and user-only keys are kept.
     * A section the user declared but left empty is filled from the defaults.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMerge(Map<String, Object> defaults, Map<String, Object> user) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            String key = entry.getKey();
            Object defValue = entry.getValue();
            if (!user.containsKey(key)) {
                merged.put(key, defValue);
                continue;
            }
            Object userValue = user.get(key);
            if (defValue instanceof Map && (userValue == null || userValue instanceof Map)) {
                Map<String, Object> child = userValue instanceof Map
                        ? (Map<String, Object>) userValue
                        : new LinkedHashMap<>();
                merged.put(key, deepMerge((Map<String, Object>) defValue, child));
            } else {
                merged.put(key, userValue);
            }
        }
        for (Map.Entry<String, Object> entry : user.entrySet()) {
            merged.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return merged;
    }

    private static List<String> lines(String text) {
        return List.of(text.split("\n", -1));
    }

    /** Index of the (uncommented) line declaring {@code key} at {@code indent}, or -1. */
    private static int keyLineIndex(String text, String key, int indent) {
        List<String> all = lines(text);
        String prefix = " ".repeat(indent) + key + ":";
        for (int i = 0; i < all.size(); i++) {
            String line = all.get(i);
            if (line.startsWith(prefix) && !line.stripLeading().startsWith("#")) {
                return i;
            }
        }
        return -1;
    }

    /** How many times {@code key} is declared (uncommented) at {@code indent}. */
    private static int countKeyLines(String text, String key, int indent) {
        int count = 0;
        for (String line : lines(text)) {
            if (line.startsWith(" ".repeat(indent) + key + ":") && !line.stripLeading().startsWith("#")) {
                count++;
            }
        }
        return count;
    }

    /**
     * Assert that the line declaring {@code childKey} really is nested inside the section
     * whose key is {@code parentKey}: walking upwards, the first shallower line must be the
     * parent's key line.
     */
    private static void assertNestedUnder(String text, String parentKey, String childKey) {
        assertNestedUnder(text, parentKey, childKey, 2);
    }

    private static void assertNestedUnder(String text, String parentKey, String childKey, int indent) {
        List<String> all = lines(text);
        int child = keyLineIndex(text, childKey, indent);
        assertTrue(child > 0, childKey + " should have been added at indentation " + indent);
        for (int i = child - 1; i >= 0; i--) {
            String line = all.get(i);
            String trimmed = line.stripLeading();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (line.length() > trimmed.length()) {
                continue; // a deeper sibling, keep walking up
            }
            assertTrue(trimmed.startsWith(parentKey + ":"),
                    childKey + " must stay inside the " + parentKey + " section but found: " + line);
            return;
        }
        throw new AssertionError(childKey + " is not nested under any section");
    }

    /** Assert every line of {@code original} is still present in {@code merged}, in order. */
    private static void assertLinesPreserved(String original, String merged) {
        List<String> expected = lines(original);
        List<String> actual = lines(merged);
        int cursor = 0;
        for (int n = 0; n < expected.size(); n++) {
            String line = expected.get(n);
            int found = -1;
            for (int i = cursor; i < actual.size(); i++) {
                if (actual.get(i).equals(line)) {
                    found = i;
                    break;
                }
            }
            assertTrue(found >= 0, "original line " + (n + 1) + "/" + expected.size()
                    + " disappeared or moved: [" + line + "]"
                    + " ; previous original line was [" + (n > 0 ? expected.get(n - 1) : "<start>") + "]"
                    + " ; following original lines: "
                    + expected.subList(n, Math.min(expected.size(), n + 3)));
            cursor = found + 1;
        }
    }

    // ── tests ────────────────────────────────────────────────

    @Test
    void addsKeysFromNewerPluginVersionsWithoutTouchingExistingContent() throws Exception {
        String defaults = builtinDefault();
        String legacy = commentOutKey(defaults, "route-chat-mode", 0);
        legacy = commentOutKey(legacy, "bot-format", 2);
        legacy = legacy.replace("language: zh_CN", "language: en_US");
        legacy = legacy.replace("broadcast-cooldown: 0", "broadcast-cooldown: 7");

        String mergedText = loadAndReadBack(legacy);
        Map<String, Object> merged = parse(mergedText); // duplicate keys would throw here

        assertEquals(deepMerge(parse(defaults), parse(legacy)), merged,
                "the merged file must hold every default key, with the user's values winning");
        assertEquals("en_US", merged.get("language"));
        assertEquals(7, merged.get("broadcast-cooldown"));
        assertEquals("channel-cross", merged.get("route-chat-mode"), "the new top-level key must come back");
        assertEquals(1, countKeyLines(mergedText, "bot-format", 2),
                "the new sub-key must come back exactly once");
        assertTrue(mergedText.contains("  # bot-format:"),
                "the user's own commented-out line must survive untouched");
    }

    @Test
    void addsACompletelyMissingSectionWithAllOfItsKeys() throws Exception {
        String legacy = "language: zh_CN\nbroadcast-cooldown: 3\n";

        String mergedText = loadAndReadBack(legacy);
        Map<String, Object> merged = parse(mergedText);

        assertEquals(deepMerge(parse(builtinDefault()), parse(legacy)), merged,
                "a section that is missing entirely must be added with all of its keys");
        assertEquals(1, countKeyLines(mergedText, "tablist", 0), "the section must not be added twice");
        assertTrue(merged.get("tablist") instanceof Map, "tablist should now be a section");
        assertEquals(parse(builtinDefault()).get("tablist"), merged.get("tablist"),
                "the whole default section must land in the file");
    }

    @Test
    void fillsASectionTheUserDeclaredButLeftEmpty() throws Exception {
        String defaults = builtinDefault();
        String legacy = "language: zh_CN\ntablist:\n";

        String mergedText = loadAndReadBack(legacy);
        Map<String, Object> merged = parse(mergedText);

        assertEquals(deepMerge(parse(defaults), parse(legacy)), merged,
                "declaring an empty section must not stop its keys from being merged");
        assertEquals(1, countKeyLines(mergedText, "tablist", 0), "the section must not be duplicated");
        assertEquals(parse(defaults).get("tablist"), merged.get("tablist"));
        assertNestedUnder(mergedText, "tablist", "entry-format");
    }

    @Test
    void placesAddedKeysNextToTheirSiblingsAndInsideTheirSection() throws Exception {
        String defaults = builtinDefault();
        String legacy = commentOutKey(defaults, "join-proxy-message", 0);
        legacy = commentOutKey(legacy, "bot-format", 2);

        String mergedText = loadAndReadBack(legacy);

        int notifyMode = keyLineIndex(mergedText, "notify-mode", 0);
        int joinProxyMessage = keyLineIndex(mergedText, "join-proxy-message", 0);
        int channelsEnabled = keyLineIndex(mergedText, "channels-enabled", 0);
        assertTrue(notifyMode >= 0 && joinProxyMessage >= 0 && channelsEnabled >= 0,
                "all three top-level keys should be present in the merged file");
        assertTrue(joinProxyMessage > notifyMode,
                "a new top-level key belongs after its preceding sibling, not before it");
        assertTrue(joinProxyMessage < channelsEnabled,
                "a new top-level key must not be pushed past its following sibling");

        assertNestedUnder(mergedText, "tablist", "bot-format");
    }

    @Test
    void runtimeConfigurationAgreesWithTheMergedFile() throws Exception {
        ConfigManager manager = loadUserConfig("language: zh_CN\n");
        Map<String, Object> merged = parse(Files.readString(dataDirectory.resolve("config.yml"),
                StandardCharsets.UTF_8));

        assertEquals(merged.get("route-chat-mode"), manager.getRouteChatMode());
        assertEquals(merged.get("channels-enabled"), manager.isChannelsEnabled());
        assertEquals(merged.get("forbidden-words-enabled"), manager.isForbiddenWordsEnabled());
        assertEquals(merged.get("broadcast-cooldown"), manager.getBroadcastCooldown());

        Map<?, ?> tablist = (Map<?, ?>) merged.get("tablist");
        assertEquals(tablist.get("enabled"), manager.isTabListEnabled());
        assertEquals(tablist.get("entry-format"), manager.getTabListEntryFormat());
        assertEquals(((Number) tablist.get("refresh-interval")).longValue(),
                manager.getTabListRefreshInterval());

        assertEquals("§6登录服", manager.getServerDisplayName("lobby"),
                "merged server aliases must be visible to the running plugin");
    }

    @Test
    void mergeIsIdempotentAndKeepsEveryUserLine() throws Exception {
        String legacy = commentOutKey(builtinDefault(), "route-chat-mode", 0);

        String afterFirstLoad = loadAndReadBack(legacy);
        String afterSecondLoad = loadAndReadBack(afterFirstLoad);

        assertLinesPreserved(legacy, afterFirstLoad);
        assertEquals(afterFirstLoad, afterSecondLoad, "a second start must not change the file again");
    }

    @Test
    void keepsUserCommentsAndCustomValues() throws Exception {
        String custom = """
                # 我的服务器配置 / my server config
                language: en_US
                broadcast-cooldown: 12

                # 我的服务器别名 / my own aliases
                server-aliases:
                  lobby: "&a我的大厅"
                  my-custom-server: "&d自定义服"
                """;

        String mergedText = loadAndReadBack(custom);
        Map<String, Object> merged = parse(mergedText);
        Map<?, ?> aliases = (Map<?, ?>) merged.get("server-aliases");

        assertTrue(mergedText.contains("# 我的服务器配置 / my server config"));
        assertTrue(mergedText.contains("# 我的服务器别名 / my own aliases"));
        assertEquals(12, merged.get("broadcast-cooldown"));
        assertEquals("&a我的大厅", aliases.get("lobby"));
        assertEquals("&d自定义服", aliases.get("my-custom-server"));
        assertTrue(aliases.containsKey("survival"), "missing default aliases should be merged in");
        assertEquals(1, countKeyLines(mergedText, "server-aliases", 0));
    }

    @Test
    void keepsTheUsersOwnIndentationInsideASection() throws Exception {
        String custom = """
                language: zh_CN
                tablist:
                    enabled: false
                    header: "&a我的头部"
                """;

        String mergedText = loadAndReadBack(custom);

        assertTrue(mergedText.contains("\n    header: \"&a我的头部\"\n"),
                "the user's own lines must be left exactly as they were");
        assertNestedUnder(mergedText, "tablist", "entry-format", 4);
        assertEquals(deepMerge(parse(builtinDefault()), parse(custom)), parse(mergedText),
                "keys added to a 4-space section must still produce the same configuration");
    }

    @Test
    void leavesInlineSectionsAloneInsteadOfBreakingThem() throws Exception {
        String custom = """
                language: zh_CN
                tablist: {enabled: true, header: "mine"}
                """;

        String mergedText = loadAndReadBack(custom);
        Map<String, Object> merged = parse(mergedText);

        assertEquals(Map.of("enabled", true, "header", "mine"), merged.get("tablist"),
                "a section written inline must not get indented keys appended underneath it");
        assertEquals("channel-cross", merged.get("route-chat-mode"),
                "top-level keys must still be merged even when a section was skipped");
        assertTrue(merged.containsKey("server-aliases"));
    }

    @Test
    void preservesWindowsLineEndings() throws Exception {
        String legacy = "language: zh_CN\r\nbroadcast-cooldown: 5\r\n";

        String mergedText = loadAndReadBack(legacy);

        assertTrue(mergedText.endsWith("\r\n"), "the file must still end with a newline");
        assertFalse(mergedText.replace("\r\n", "").contains("\r"),
                "added lines must not leave stray carriage returns behind");
        assertEquals(deepMerge(parse(builtinDefault()), parse(legacy)), parse(mergedText));
        assertLinesPreserved(legacy, mergedText);
    }
}
