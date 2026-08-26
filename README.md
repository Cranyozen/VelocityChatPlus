<div align="center">

# VelocityChat

**A cross-server proxy chat plugin for Velocity**

[![简体中文](https://img.shields.io/badge/简体中文-Read_in_Chinese-blue?style=for-the-badge&logo=github)](README_zh_CN.md)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](../../LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)]()
[![Velocity](https://img.shields.io/badge/Velocity-3.x-blueviolet.svg)]()

</div>

Chat together across your entire server network. Install **only on the Velocity proxy** — no backend server plugins required.

## Features

- **Cross-server broadcast** — `/br <message>` sends a chat message to every player on every backend server
- **Server invites** — `/yq [message]` broadcasts a server invite into the cross-server chat; other players click it to join the inviter's server (configurable cooldown & permission bypass)
- **Forbidden-word filter** — block `/br` and `/yq` messages containing banned words (configurable, words stored in `banned_words.txt` separated by `;`, admin bypass)
- **Timed announcements** — schedule announcements by clock time with daily / weekly / monthly / one-shot recurrence, or loop on an interval like `30min` (configured in `auto_broadcast.yml`)
- **Color codes** — full `&` color code support (`&c`, `&6`, `&l`, `&r`, …)
- **Join / switch / leave announcements** — automatic messages when players join, switch servers, or disconnect
- **Personal join notifications** — send a welcome message only to the joining player when they enter the proxy or a backend server (`join-proxy-message`, `join-server-message`, per-server overrides)
- **Server aliases** — show friendly names like "登录服" instead of raw server IDs, configured directly in `config.yml` (`serverId: "&aDisplayName"`)
- **Group titles** — create groups and assign title prefixes shown before player names in chat
- **Chat channels** — partitioned (分区) cross-server chat: players join a channel with `/ch` and only members of the same channel see each other's messages; channels can be permission-gated and one is the default
- **Flexible chat routing** — configurable `route-chat-mode`: show only channel format, only backend format, or both; compatible with MCDR and backend logging
- **Custom TabList** — override header, footer and player entries across the network (configured in the `tablist:` section); entries are updated in place so backend-injected entries like Carpet fake players (bots) stay visible; each entry's display text is customizable via `entry-format` with `{player}`/`{server}`/`{title}`/`{group}`/`{ping}` placeholders, and Carpet fake players get their own `bot-format` template; set `manage-header-footer: false` to hand header/footer to Carpet `/log` and avoid flicker
- **Customizable message formats** — all chat formats (`broadcast-format`, `channel-format`) and TabList entries support semantic placeholders: `{player}`, `{title}`, `{server}`, `{message}`, `{channel}`, `{ping}`, `{online}`, `{group}`
- **Granular permissions** — `velocitychat.admin.*` sub-nodes, works with LuckPerms (optional dependency)
- **Broadcast cooldown** — optional anti-spam cooldown, admins can bypass
- **Message visibility** — join/switch/leave alerts can be shown to everyone, admins only, or disabled
- **Bilingual** — built-in `zh_CN` (Simplified Chinese) and `en_US` (English) language files, switchable at runtime
- **Auto-merge config** — new config keys from plugin updates are automatically appended to your existing `config.yml`

## Requirements

| Requirement | Version |
|---|---|
| Java | 17+ |
| Proxy | Velocity 3.x / 4.x (all modern versions) |
| Optional | A permissions plugin (e.g. [LuckPerms](https://luckperms.net)) for granular admin permissions |

## Installation

1. Download the plugin JAR (build it yourself or grab a release)
2. Drop it into the `plugins/` folder of your Velocity proxy
3. Restart the proxy
4. A `config.yml` is generated on first run — review it and restart if you change anything

> **No backend plugins needed** — this runs entirely on the proxy.

## Commands

### Player commands

| Command | Description | Permission |
|---|---|---|
| `/br <message>` (alias `/broadcast`, configurable) | Send a cross-server chat message to all players | everyone |
| `/yq [message]` | Broadcast a server invite into the cross-server chat; others click to join your server | everyone |
| `/ch <message>` (alias `/channel`) | Send a message to your current channel | everyone |
| `/ch join <id>` | Join a channel (only channel members see each other's chat) | per-channel permission |
| `/ch leave` | Leave your channel back to the default | everyone |
| `/ch list` | List all channels | everyone |

### Admin commands (`/velocitychat`, alias `/vchat`)

| Command | Description | Permission |
|---|---|---|
| `/vchat create group <name> [title]` | Create a new group with an optional title | `velocitychat.admin.create` |
| `/vchat group <name> join <player>` | Add a player to a group | `velocitychat.admin.group.join` |
| `/vchat group <name> remove <player>` | Remove a player from a group | `velocitychat.admin.group.remove` |
| `/vchat group <name> settitle <title>` | Change the group's title | `velocitychat.admin.group.settitle` |
| `/vchat group <name> delete` | Delete a group | `velocitychat.admin.group.delete` |
| `/vchat group list` | List all groups and their members | `velocitychat.admin.group.list` |
| `/vchat channel add <id> [name] [--perm node] [--default]` | Create a channel (optional permission & default flag) | `velocitychat.admin.channel.*` |
| `/vchat channel remove <id>` | Delete a channel | `velocitychat.admin.channel.*` |
| `/vchat channel setdefault <id>` | Set the default channel | `velocitychat.admin.channel.*` |
| `/vchat channel setname <id> <name>` | Change a channel's display name | `velocitychat.admin.channel.*` |
| `/vchat channel list` | List all channels and member counts | `velocitychat.admin.channel.*` |
| `/vchat reload` | Hot-reload config, language files, group & channel data, forbidden words & timed announcements | `velocitychat.admin.reload` |

Titles support `&` color codes, e.g. `/vchat create group admin &c&l[Admin]`.

## Permissions

| Permission | Description |
|---|---|
| `velocitychat.admin` | Top-level admin permission — grants all sub-nodes and bypasses the broadcast cooldown |
| `velocitychat.admin.create` | Create groups |
| `velocitychat.admin.group.*` | Wildcard for all group-management permissions |
| `velocitychat.admin.group.join` | Add players to groups |
| `velocitychat.admin.group.remove` | Remove players from groups |
| `velocitychat.admin.group.settitle` | Change group titles |
| `velocitychat.admin.group.delete` | Delete groups |
| `velocitychat.admin.group.list` | List groups and members |
| `velocitychat.admin.channel.*` | Wildcard for all channel-management permissions |
| `velocitychat.admin.reload` | Reload configuration |

> **Tip:** LuckPerms example — `/lpv user <player> permission set velocitychat.admin true`

## Placeholder Reference

All formats support these semantic placeholders:

| Placeholder | Description | Available in |
|---|---|---|
| `{player}` | Player name | broadcast, channel, tablist |
| `{title}` | Group title (empty if player has no group) | broadcast, channel, tablist |
| `{server}` | Server display name (from `server-aliases`) | broadcast, channel, tablist, header/footer |
| `{message}` | Chat message content | broadcast, channel |
| `{channel}` | Channel display name | channel |
| `{ping}` | Player ping in ms | broadcast, channel, tablist, header/footer |
| `{online}` | Online player count | header/footer |
| `{group}` | Group name | tablist |

## Configuration (`config.yml`)

Key settings (all commented in the generated file):

| Setting | Default | Description |
|---|---|---|
| `language` | `zh_CN` | Language file: `zh_CN` or `en_US` |
| `broadcast-aliases` | `br`, `broadcast` | Aliases for the cross-server broadcast command |
| `broadcast-cooldown` | `0` | Seconds between `/br` uses (`0` = no cooldown) |
| `broadcast-cooldown-bypass` | `false` | When enabled, `velocitychat.admin` players ignore the cooldown |
| `broadcast-format` | `§6[Broadcast] §r{title}{player}§f: §7[{server}]§f {message}` | Cross-server broadcast message format |
| `channel-format` | `§d[§6{channel}§d]§r[{title}{player}§r]§r{server} §7>>§f {message}` | Channel chat message format |
| `invite-cooldown` | `30` | Seconds between `/yq` invites (`0` = no cooldown) |
| `invite-cooldown-bypass` | `false` | When enabled, players with `invite-bypass-permission` ignore the invite cooldown |
| `invite-bypass-permission` | `velocitychat.admin` | Permission that bypasses the invite cooldown |
| `forbidden-words-enabled` | `true` | Enable the forbidden-word filter on `/br` and `/yq` messages |
| `forbidden-words-bypass-permission` | `velocitychat.admin` | Permission that bypasses the forbidden-word filter |
| `server-aliases` | map presets | Servers that show a display name: `serverId: "&aDisplayName"` (supports `&` colors) |
| `notify-mode` | `all` | Join/switch/leave visibility: `all`, `admin` (admins only), or `none` (disabled) |
| `join-proxy-message` | `§a欢迎来到服务器！...` | Personal notification when player joins the proxy |
| `join-server-message` | `§a你已进入 §e{server}` | Personal notification when player joins a backend server (supports per-server overrides via `join-server-messages`) |
| `channels-enabled` | `true` | Enable the channel (分区) chat feature |
| `route-chat` | `true` | Intercept normal chat and route it to the player's active channel |
| `route-chat-mode` | `channel-cross` | Chat routing mode: `channel` (deny backend, channel only), `channel-log` (deny + proxy log), `channel-cross` (same-server backend, cross-server channel, no duplicate), `both` (no deny, duplicate) |
| `channels` | — | Preset channels seeded into `channels.yml` on first startup |
| `tablist` | disabled | Custom TabList — see below |
| `groups` | — | Preset groups generated on first startup |
| `messages` | — | Override individual language-file messages (takes priority) |

### TabList Configuration

| Setting | Default | Description |
|---|---|---|
| `tablist.enabled` | `true` | Enable the custom TabList |
| `tablist.manage-header-footer` | `true` | Set `false` to hand header/footer to Carpet `/log` and avoid flicker |
| `tablist.refresh-interval` | `3` | Tab refresh interval in seconds (min `1`) |
| `tablist.header` | `&6&lVelocityChat...` | Tab header (supports `{online}`, `{server}`, `{ping}`) |
| `tablist.footer` | `&7Footer` | Tab footer (supports `{online}`, `{server}`, `{ping}`) |
| `tablist.entry-format` | `{server}§8 \| &r{title}{player}` | Real player entry format |
| `tablist.bot-format` | `{server}§8 \| &7&o[假人]&r{title}{player}` | Carpet fake player entry format |

Group data is stored in a `groups.yml` file, channel data in `channels.yml`. Banned words live in `banned_words.txt`, and timed announcements are configured in `auto_broadcast.yml` (all generated on first startup).

## Building from source

JDK 17+ and Maven 3.8+ are required.

```bash
git clone https://github.com/LiquidTeamYHC/Velocity_Plugin.git
cd Velocity_Plugin/VelocityChat
mvn package
```

The built plugin is at `target/VelocityChat-2.2.0.jar`.

## Changelog

See [更新日志-CHANGELOG.md](更新日志-CHANGELOG.md) for the full version history.

## Author

- **YuHongChen (LiquidTeam)** — main developer
- QQ: `1464670605`

## License

This project is licensed under the [MIT License](../../LICENSE).
