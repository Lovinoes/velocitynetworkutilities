# Commands

`<required>` and `[optional]` arguments. Permissions and moderation command names can be changed in each plugin's `config.yml`.

## Chat

| Command | Aliases | Permission | Description |
|---|---|---|---|
| `/ch <channel>` | `/channel` | Permission of the channel | Switch channel |
| `/g` | `/global` | `velocitynetworkchat.channel.global` | Switch to global chat |
| `/l` | `/local` | `velocitynetworkchat.channel.local` | Switch to local chat (100 blocks) |
| `/n` | `/near` | `velocitynetworkchat.channel.near` | Switch to near chat (64 blocks) |
| `/sc` | `/staffchat` | `velocitynetworkchat.channel.staff` | Switch to staff chat |
| `/msg <player> <message>` | `/tell`, `/w`, `/whisper`, `/pm`, `/m` | `velocitynetworkchat.msg` | Send a private message |
| `/r <message>` | `/reply` | `velocitynetworkchat.msg` | Reply to the last private message |
| `/mentionsound` | `/pingsound`, `/pingtoggle` | None | Toggle the mention sound |

Start a message with `!` to send it to global chat or `#` for staff chat. `/chatactions` is used internally when a name in chat is clicked.

## Vanish

| Command | Aliases | Permission | Description |
|---|---|---|---|
| `/vanish` | `/v` | `velocitynetworkvanish.vanish` | Toggle vanish, with a fake leave and join message |
| `/silentvanish` | `/svanish`, `/sv` | `velocitynetworkvanish.vanish` | Toggle vanish without messages |

## Moderation

| Command | Aliases | Permission | Description |
|---|---|---|---|
| `/ban <player> [duration] [reason] [-s]` | `/tempban` | `velocitynetworkmoderation.ban` | Ban a player |
| `/banip <player> [duration] [reason] [-s]` | `/ipban` | `velocitynetworkmoderation.banip` | Ban a player's IP |
| `/mute <player> [duration] [reason] [-s]` | `/tempmute` | `velocitynetworkmoderation.mute` | Mute a player |
| `/kick <player> [reason] [-s]` | | `velocitynetworkmoderation.kick` | Kick a player |
| `/warn <player> [reason] [-s]` | | `velocitynetworkmoderation.warn` | Warn a player |
| `/unban <player> [-s]` | `/pardon` | `velocitynetworkmoderation.unban` | Unban a player |
| `/unbanip <player\|ip> [-s]` | `/unipban` | `velocitynetworkmoderation.unbanip` | Unban an IP |
| `/unmute <player> [-s]` | | `velocitynetworkmoderation.unmute` | Unmute a player |
| `/history <player>` | `/punishments`, `/checkpunishments` | `velocitynetworkmoderation.history` | Show a player's punishments |
| `/history <player> clear` | | `velocitynetworkmoderation.history.clear` | Delete a player's history, after confirming |
| `/history <player> remove <id>` | | `velocitynetworkmoderation.history.clear` | Delete one entry |

Durations look like `30m`, `7d` or `1d12h`. Without one, a ban or mute is permanent. `-s` keeps the punishment silent: only players with `velocitynetworkmoderation.seesilent` are told.

Bans and mutes that are still in force are never deleted from a history. Lift them first with `/unban` or `/unmute`.

## Player info

| Command | Aliases | Permission | Description |
|---|---|---|---|
| `/pinfo [player]` | `/playerinfo`, `/seen`, `/ui`, `/userinfo` | `velocitynetworkplayerinfo.use` | Show first login, last login and status |

## Other permissions

| Permission | Description |
|---|---|
| `velocitynetworkchat.color` | Use colors in chat |
| `velocitynetworkchat.antispam.bypass` | Bypass the chat cooldown |
| `velocitynetworkchat.showcase.bypasscooldown` | Bypass the `[item]`, `[inv]` and `[pos]` cooldown |
| `velocitynetworkvanish.see` | See vanished players |
| `velocitynetworkmoderation.seesilent` | See silent punishments |
| `velocitynetworkmoderation.exempt` | Cannot be punished |
| `velocitynetworkmoderation.filter.notify` | Get chat filter notifications |
| `velocitynetworkmoderation.filter.bypass` | Bypass the chat filter |
