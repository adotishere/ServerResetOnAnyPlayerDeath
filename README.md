# Server Reset On Any Player Death

A dedicated-server Fabric mod that turns your Minecraft server into a shared hardcore run. When the death allowance is reached, players are synchronized to the death screen, the triggering death message is broadcast, and everyone is seamlessly moved into a fresh world set—**completely restartless**, with no server shutdown or restart scripts required.

---

## How It Works: The Rotating Dimensions System

Instead of deleting and recreating the vanilla root world on disk (which requires stopping the server), the mod runs all gameplay inside dynamically generated, numbered dimension sets:

- **Root World (`world`)**: Remains untouched as an internal server anchor.
- **Active Gameplay Dimensions**: All gameplay takes place within the active numbered set:
  - Overworld: `server_reset_hardcore:reset_<number>`
  - The Nether: `server_reset_hardcore:reset_<number>_nether`
  - The End: `server_reset_hardcore:reset_<number>_end`
- **Dynamic Portal Linking**: Nether and End portals automatically link between the sibling dimensions of the currently active world set.
- **Seamless Reset & Cleanup**:
  1. When the death limit is reached, all players enter the death screen while the countdown or console confirmation takes place.
  2. The next world set (`reset_<number+1>`) is activated immediately.
  3. Players are respawned together directly in the new world set with fresh stats and cleared inventories.
  4. The previous world set is cleanly unloaded and deleted from disk in the background.
- **Player Routing**: Any player connecting or reconnecting to the server is routed directly into the active `reset_<number>` dimension.

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) and [Fabric API](https://curseforge.com/minecraft/mc-mods/fabric-api) on your dedicated server.
2. Place `server-reset-on-any-player-death-<version>.jar` into your server's `mods` folder.
3. Start the server. The configuration file is generated at `config/server_reset_hardcore.json`.
4. (Optional) Place any datapacks you want preserved across resets into the `persistent-datapacks` folder in your server root.

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

### Options

- `allowedDeaths`: Number of deaths allowed before triggering a reset (default: `1`).
- `resetDelaySeconds`: Countdown in seconds before moving players to the new world (default: `5`).
- `trackResets`: Shows the active world number in the server MOTD (default: `true`).
- `requireConsoleConfirmation`: If `true`, prompts the server console `[Y/n]` before resetting (default: `false`).
- `motdText`: Subtext displayed on the server MOTD when reset tracking is enabled.
- `activeSeed`: Seed used for the active Overworld, Nether, and End dimensions (`0` = auto-generate).
- `nextSeed`: Seed prepared for the upcoming standby world set (`0` = auto-generate).
- `resetCount` & `deathsSinceLastReset`: Managed automatically by the mod to persist across normal restarts.

---

## Console Confirmation

When `requireConsoleConfirmation` is enabled:
- The server pauses before world activation and displays:
  ```
  [Server thread/WARN]: Reset the world? [Y/n]
  ```
- Type `Y` (or press Enter) in the server console to confirm and activate the fresh world.
- Type `N` in the server console to cancel the reset.

---

## Launch Example

Because resets take place live in-memory without server restarts, a standard launch script is all that is needed:

```bat
@echo off
java -Xmx4G -jar fabric-server-launch.jar nogui
pause
```
