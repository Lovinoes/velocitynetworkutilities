# VelocityNetworkUtilities

A plugin suite for a Velocity network: chat, private messages, vanish, moderation and player info, shared across every server. Built for my own server (vibecoded).

## Plugins

| Plugin | Runs on | What it does |
|---|---|---|
| VelocityNetworkUtilities | Proxy | Database pool and cross-server messaging. Required by all the others |
| PlayerUUIDCacheVelocity | Proxy | Name, UUID and session cache. Required by Moderation and PlayerInfo |
| VelocityNetworkChat | Proxy | Channels, mentions, private messages, `[item]` `[inv]` `[pos]`, anti-spam |
| VelocityNetworkVanish | Proxy | Vanish across the whole network |
| VelocityNetworkModeration | Proxy | Bans, mutes, kicks, warnings, history, chat filter |
| VelocityNetworkPlayerInfo | Proxy | Join and leave messages, `/pinfo` |
| PaperNetworkChat | Paper | Backend half of VelocityNetworkChat |
| PaperNetworkVanish | Paper | Backend half of VelocityNetworkVanish |

Install the Paper plugins on every backend. VelocityNetworkChat and PaperNetworkChat must always come from the same build.

## Requirements

| | |
|---|---|
| Java | 25 |
| Proxy | [Velocity-CTD](https://github.com/GemstoneGG/Velocity-CTD), recommended over Velocity (4.2+) |
| Backends | Paper 26.3 |
| Database | MariaDB or MySQL (SQLite for testing) |
| [SignedVelocity](https://github.com/4drian3d/SignedVelocity) | Required, on the proxy and every backend |
| [PAPIProxyBridge](https://github.com/WiIIiam278/PAPIProxyBridge) + PlaceholderAPI | Optional, for `%placeholders%` in chat formats |

## Features

### Chat

- Channels `GLOBAL`, `LOCAL` (100 blocks), `NEAR` (64 blocks) and `STAFF`, switched with `/ch <channel>`, shortcuts like `/g` and `/sc`, or a prefix (`!` global, `#` staff)
- Channels a player may not use are invisible to them: `#hello` is just a normal message
- `@name` mentions with a sound, toggled with `/mentionsound`
- `[item]`, `[inv]` and `[pos]` show your held item, inventory or position, with hover details
- `/msg` and `/r` across servers
- Anti-spam: 1.5s between messages and no repeats within 10s
- Legacy `&` colours and MiniMessage, rank prefixes, PlaceholderAPI in formats
- Click a name for staff actions such as mute or ban

### Vanish

- `/vanish` (`/v`) with a fake leave and join, `/silentvanish` (`/sv`) without
- Hidden entity, name tag, tab entry and tab completion, on every server
- Hidden from `/pinfo`, `/msg`, `/r` and protected commands like `/tpa`
- Survives server switches and proxy restarts

### Moderation

- `/ban`, `/banip`, `/mute`, `/kick`, `/warn`, and `/unban`, `/unbanip`, `/unmute`
- Durations like `30m`, `7d` or `1d12h`, or permanent
- `-s` for silent punishments, `/history` for every record
- Mutes also block `/msg`, `/r`, `/me` and any command you list

### Chat filter

- About 210 English and German entries in `filter.yml`, grouped into categories
- Each category either stars words out or blocks the message, and can count towards automatic punishments (warn, then mute)
- Sees through `f.u.c.k`, `fuuuck`, `sh1t`, look-alike letters and colour codes, without flagging words like "class" or "Scunthorpe"
- Everyday swearing ("shit", "scheiße") is left alone by default
- Staff are told about every catch

### Player info

- Network-wide join and leave messages
- `/pinfo <player>` (`/seen`) shows first login, last login, last seen and current server

## Permissions

| Permission | Allows |
|---|---|
| `velocitynetworkchat.channel.<channel>` | Writing in a channel (`global`, `local`, `near`, `staff`) |
| `velocitynetworkchat.msg` | `/msg` and `/r` |
| `velocitynetworkchat.color` | Colours and formatting in messages |
| `velocitynetworkchat.antispam.bypass` | No chat cooldown |
| `velocitynetworkchat.showcase.bypasscooldown` | No `[item]` cooldown |
| `velocitynetworkvanish.vanish` | `/vanish` and `/silentvanish` |
| `velocitynetworkvanish.see` | Seeing vanished players |
| `velocitynetworkmoderation.<command>` | Each moderation command, e.g. `velocitynetworkmoderation.ban` |
| `velocitynetworkmoderation.seesilent` | Seeing silent punishments |
| `velocitynetworkmoderation.exempt` | Cannot be punished |
| `velocitynetworkmoderation.filter.notify` | Filter notifications |
| `velocitynetworkmoderation.filter.bypass` | Not filtered |
| `velocitynetworkplayerinfo.use` | `/pinfo` |

Every permission can be changed in the plugin's `config.yml`, as can the names and aliases of the moderation commands.

## Configuration

Each plugin has a `config.yml` for settings and a `languages/` folder for every message.

- **Languages:** `en-us` (default), `de-de`, and `de-de-mixed` (German with the usual English server words). Pick one with `language:` in `config.yml`. Use the same language in Vanish and PlayerInfo, or a fake leave looks different from a real one.
- **Updates:** new options are added to your files automatically, removed ones are cleaned up, and your own values are never touched. The previous file is kept as `.bak`.

## Building

```bash
./gradlew clean build
```

The jars end up in `deploy/`. Add `-Pctd` to build against Velocity-CTD's API instead.
