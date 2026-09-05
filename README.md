# Server Reset On Any Player Death

A dedicated-server Fabric mod that turns the entire Minecraft server into a shared hardcore run. When a player dies and the death allowance is reached, every online player is synchronized to the death screen, the triggering death message is broadcast in quotes, and a fresh world set (Overworld, Nether, and End) is seamlessly activated—**completely restartless**, with no server shutdown or batch loop needed!

The previous world set is cleanly unloaded and deleted in the background once players have moved to the new world.

---

## Features

- **Seamless Restartless Rotation**: Fresh gameplay dimensions (`server_reset_hardcore:reset_X`, `..._nether`, `..._end`) are generated, activated, and cleaned up dynamically without stopping the server process.
- **Synchronized Death Screen**: When any player dies, all other players are killed simultaneously so everyone enters the death screen together while waiting for the countdown or console confirmation.
- **Pre-generated Standby World**: The next world and its spawn chunks are precalculated and generated in the background, ensuring instantaneous transitions when a reset occurs.
- **Safe Land Spawn**: Uses climate sampling and terrain analysis to place `/setworldspawn` safely on solid ground above sea level, guaranteeing players never spawn submerged in water or oceans.
- **Direct Dimension Login Routing**: Intercepts player login to place joining players directly into the active custom dimension at the safe spawn, preventing duplicate UUID collisions, ghost player states, and packet desync.
- **Dynamic Portal Linking**: Nether and End portals dynamically route between sibling dimensions within the active reset set.
- **Synchronized Seeds**: All three dimensions within a world set share the uniform `activeSeed`. Across resets, fresh unique seeds are generated automatically.
- **Time & Weather Reset**: World time is reset to `0` (daybreak) and weather is cleared on every world reset.
- **Persistent Datapacks**: Datapacks placed in `persistent-datapacks` are automatically copied to new worlds before startup.

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) and [Fabric API](https://curseforge.com/minecraft/mc-mods/fabric-api) on your dedicated server.
2. Place `server-reset-on-any-player-death-<version>.jar` into your server's `mods` folder.
3. Start the server. Configuration is generated at `config/server_reset_hardcore.json`.
4. (Optional) Place any datapacks you want preserved across resets into the newly created `persistent-datapacks` folder in your server root.

---

## Configuration

Configuration is located at `config/server_reset_hardcore.json`:

```json
{
  "allowedDeaths": 1,
  "resetDelaySeconds": 5,
  "trackResets": true,
  "requireConsoleConfirmation": false,
  "motdText": "A new world awaits",
  "resetCount": 0,
  "deathsSinceLastReset": 0,
  "activeSeed": 0,
  "nextSeed": 0
}
```

### Config Options

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `allowedDeaths` | Integer | `1` | Number of deaths allowed before triggering a world reset. |
| `resetDelaySeconds` | Integer | `5` | Countdown in seconds before moving players to the fresh world. |
| `trackResets` | Boolean | `true` | If true, displays the active world number in the server MOTD. |
| `requireConsoleConfirmation` | Boolean | `false` | If true, prompts the server console for confirmation before resetting. |
| `motdText` | String | `"A new world awaits"` | Second line text of the server MOTD when reset tracking is enabled. |
| `resetCount` | Integer | `0` | Number of resets that have occurred (managed automatically). |
| `deathsSinceLastReset` | Integer | `0` | Tracked deaths towards `allowedDeaths` (managed automatically). |
| `activeSeed` | Long | `0` | Seed used for the current Overworld, Nether, and End dimensions (`0` = auto-generate unique seed). |
| `nextSeed` | Long | `0` | Seed prepared for the upcoming standby world set (`0` = auto-generate unique seed). |

---

## Console Confirmation

When `requireConsoleConfirmation` is set to `true`:
- The server pauses before world activation and prints:
  ```
  [Server thread/WARN]: Reset the world? [Y/n]
  ```
- Type `Y` (or press Enter) in the server console to confirm and begin the reset.
- Type `N` in the server console to cancel the reset.
- In-game player commands cannot confirm console prompts.

---

## Server Launch Script

Because resets occur in-memory without server shutdown, a simple launch script is all you need:

```bat
@echo off
java -Xmx4G -jar fabric-server-launch.jar nogui
pause
```
