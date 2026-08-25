<div align="center">

# VelocityChat

**一款 Velocity 代理端跨服聊天插件**

[![English](https://img.shields.io/badge/English-Read_in_English-blue?style=for-the-badge&logo=github)](README.md)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](../../LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)]()
[![Velocity](https://img.shields.io/badge/Velocity-3.x-blueviolet.svg)]()

</div>

让整个服务器网络的玩家可以一起聊天。**只需在 Velocity 代理端安装**，子服务器无需安装任何插件。

## 功能特性

- **跨服广播** — `/br <消息>` 把聊天消息发送到所有子服的所有玩家
- **跨服邀请** — `/yq [消息]` 在跨服聊天中广播邀请，其他玩家点击即可加入该玩家所在的服务器（可配置冷却与权限绕过）
- **违禁词检测** — 拦截包含违禁词的 `/br` 与 `/yq` 消息（可开关，违禁词放在 `banned_words.txt` 中、用分号 `;` 分隔，管理员可绕过）
- **定时消息** — 按时刻调度公告，支持 每天/每周/每月/仅一次 循环，也可按 `30min` 等时长间隔循环（在 `auto_broadcast.yml` 中配置）
- **颜色代码** — 完整支持 `&` 颜色代码（`&c`、`&6`、`&l`、`&r`……）
- **进服/切服/离服消息** — 玩家进服、切换服务器、断开连接时自动广播
- **服务器别名** — 直接在 `config.yml` 中配置（`服务器ID: "&a显示名称"`），无需再改语言文件
- **群组称号** — 创建群组并给成员设置称号前缀，显示在聊天中的玩家名前
- **分区聊天** — 玩家通过 `/ch` 加入不同频道，只有同频道玩家才能互相看到消息（跨服）；频道可设置进入权限并可指定默认频道
- **自定义 TabList** — 在 `tablist:` 配置节中自定义全代理的 TabList 标题、页脚与玩家条目显示；条目采用原位更新，因此 Carpet 模组的假人会被保留；每个条目的显示文本可通过 `entry-format` 配置，支持 `{player}`/`{server}`/`{title}`/`{group}` 占位符
- **权限细分** — `velocitychat.admin.*` 子权限节点，可与 LuckPerms 搭配（可选依赖）
- **广播冷却** — 可选的防刷屏冷却，管理员可绕过
- **消息可见性** — 进服/切服/离服提示可设为 全体可见 / 仅管理员 / 关闭
- **双语言** — 内置简体中文 `zh_CN` 和英文 `en_US` 语言文件，运行时可切换

## 环境要求

| 要求 | 版本 |
|---|---|
| Java | 17+ |
| 代理端 | Velocity 3.x（所有现代版本） |
| 可选 | 权限插件（如 [LuckPerms](https://luckperms.net)）用于细分管理权限 |

## 安装方法

1. 下载插件 JAR（自行编译或下载发行版）
2. 放入 Velocity 代理端的 `plugins/` 文件夹
3. 重启服务器
4. 首次运行会自动生成 `config.yml`——请检查配置，修改后重启生效

> **子服无需任何插件** — 全部在代理端运行。

## 命令

### 玩家命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/br <消息>`（别名 `/broadcast`，可自定义） | 向所有玩家发送跨服聊天消息 | 所有玩家 |
| `/yq [消息]` | 在跨服聊天中广播邀请，其他玩家点击即可加入你所在的服务器 | 所有玩家 |
| `/ch <消息>`（别名 `/channel`） | 发送消息到你当前所在的频道 | 所有玩家 |
| `/ch join <频道ID>` | 加入一个频道（只有同频道玩家能看到彼此聊天） | 各频道的进入权限 |
| `/ch leave` | 离开当前频道，返回默认频道 | 所有玩家 |
| `/ch list` | 查看所有频道 | 所有玩家 |

### 管理命令（`/velocitychat`，别名 `/vchat`）

| 命令 | 说明 | 权限 |
|---|---|---|
| `/vchat create group <名称> [称号]` | 创建群组并可设置称号 | `velocitychat.admin.create` |
| `/vchat group <名称> join <玩家>` | 将玩家加入群组 | `velocitychat.admin.group.join` |
| `/vchat group <名称> remove <玩家>` | 将玩家移出群组 | `velocitychat.admin.group.remove` |
| `/vchat group <名称> settitle <称号>` | 修改群组称号 | `velocitychat.admin.group.settitle` |
| `/vchat group <名称> delete` | 删除群组 | `velocitychat.admin.group.delete` |
| `/vchat group list` | 查看所有群组及成员 | `velocitychat.admin.group.list` |
| `/vchat channel add <ID> [名称] [--perm 权限] [--default]` | 创建频道（可设进入权限和默认频道） | `velocitychat.admin.channel.*` |
| `/vchat channel remove <ID>` | 删除频道 | `velocitychat.admin.channel.*` |
| `/vchat channel setdefault <ID>` | 设置默认频道 | `velocitychat.admin.channel.*` |
| `/vchat channel setname <ID> <名称>` | 修改频道显示名称 | `velocitychat.admin.channel.*` |
| `/vchat channel list` | 查看所有频道及在线人数 | `velocitychat.admin.channel.*` |
| `/vchat reload` | 热重载配置、语言文件、群组与频道数据、违禁词列表及定时消息 | `velocitychat.admin.reload` |

称号支持 `&` 颜色代码，例如 `/vchat create group admin &c&l[管理员]`。

## 权限

| 权限 | 说明 |
|---|---|
| `velocitychat.admin` | 顶级管理权限——包含所有子权限，并绕过广播冷却 |
| `velocitychat.admin.create` | 创建群组 |
| `velocitychat.admin.group.*` | 所有群组管理权限的通配符 |
| `velocitychat.admin.group.join` | 添加成员 |
| `velocitychat.admin.group.remove` | 移除成员 |
| `velocitychat.admin.group.settitle` | 修改称号 |
| `velocitychat.admin.group.delete` | 删除群组 |
| `velocitychat.admin.group.list` | 查看群组及成员 |
| `velocitychat.admin.channel.*` | 所有频道管理权限的通配符 |
| `velocitychat.admin.reload` | 重载配置 |

> **提示：** LuckPerms 示例 — `/lpv user <玩家> permission set velocitychat.admin true`

## 配置说明（`config.yml`）

生成的配置文件中所有选项都带注释，主要设置如下：

| 设置项 | 默认值 | 说明 |
|---|---|---|
| `language` | `zh_CN` | 语言文件：`zh_CN` 或 `en_US` |
| `broadcast-aliases` | `br`、`broadcast` | 跨服广播命令的别名 |
| `broadcast-cooldown` | `0` | 两次 `/br` 的最小间隔（`0` = 无冷却） |
| `broadcast-cooldown-bypass` | `false` | 启用后，拥有 `velocitychat.admin` 的玩家无视冷却 |
| `invite-cooldown` | `30` | 两次 `/yq` 邀请的最小间隔（`0` = 无冷却） |
| `invite-cooldown-bypass` | `false` | 启用后，拥有 `invite-bypass-permission` 的玩家无视邀请冷却 |
| `invite-bypass-permission` | `velocitychat.admin` | 绕过邀请冷却所需的权限节点 |
| `forbidden-words-enabled` | `true` | 开启 `/br` 与 `/yq` 消息的违禁词检测 |
| `forbidden-words-bypass-permission` | `velocitychat.admin` | 可绕过违禁词检测的权限 |
| `server-aliases` | map 预设 | 服务器显示名称：`服务器ID: "&a显示名称"`（支持 `&` 颜色） |
| `notify-mode` | `all` | 进服/切服/离服消息可见性：`all`、`admin`（仅管理员）、`none`（关闭） |
| `channels-enabled` | `true` | 是否开启分区聊天功能 |
| `route-chat` | `true` | 是否拦截普通聊天并路由到玩家当前频道 |
| `channels` | — | 首次启动时生成的预设频道 |
| `tablist` | 关闭 | 自定义 TabList：`enabled`、`header`、`footer`（支持 `{online}` 占位符）、`entry-format`（每条显示文本，支持 `{player}`/`{server}`/`{title}`/`{group}` 占位符）；条目原位更新，Carpet 假人不会消失 |
| `groups` | — | 首次启动时生成的预设群组 |
| `messages` | — | 覆盖语言文件中的指定消息（优先级更高） |

群组数据保存在 `groups.yml`，频道数据在 `channels.yml`。违禁词放在 `banned_words.txt`，定时消息在 `auto_broadcast.yml` 中配置（均在首次启动时自动生成）。

## 从源码构建

需要 JDK 17+ 和 Maven 3.8+。

```bash
git clone https://github.com/LiquidTeamYHC/Velocity_Plugin.git
cd Velocity_Plugin/VelocityChat
mvn package
```

构建产物位于 `target/VelocityChat-2.2.0.jar`。

## 更新日志

完整版本历史请见 [更新日志-CHANGELOG.md](更新日志-CHANGELOG.md)。

## 作者

- **YuHongChen（LiquidTeam）** — 主要开发者
- QQ：`1464670605`

## 开源协议

本项目基于 [MIT License](../../LICENSE) 开源。
