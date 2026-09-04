# adotishere

A dedicated-server-only Fabric mod for Minecraft 26.1.2. Deaths are counted
server-wide. When the configured allowance is reached, every other online player
is killed, the triggering player's death message is repeated in quotes, and the
server stops after a configurable delay. The supplied watchdog deletes the old
world and starts the server again.

## Install

1. Install Fabric Loader 0.18.4 or newer for a Minecraft 26.1.2 dedicated server.
2. Put the built mod JAR and Fabric API 0.155.0+26.1.2 in the server's `mods` folder.
3. Put `start-server.ps1` (Windows) or `start-server.sh` (Linux) beside
   `server.properties` and start the server through that script. Do not start the
   server JAR directly, because a stopped Java process cannot restart itself.
4. Put persistent datapacks in `reset-datapacks` beside the launcher. Its contents
   are copied into the world's `datapacks` folder before every server start.
5. On first launch, edit `config/server_reset_hardcore.json`, then restart once
   to apply changes.

Windows example:

```powershell
.\start-server.ps1 -ServerJar fabric-server-launch.jar -JvmArgs "-Xms2G -Xmx4G"
```

Linux example:

```bash
chmod +x start-server.sh
SERVER_JAR=fabric-server-launch.jar JAVA_ARGS="-Xms2G -Xmx4G" ./start-server.sh
```

## Configuration

```json
{
  "allowedDeaths": 1,
  "shutdownDelaySeconds": 5,
  "trackResets": true,
  "requireConsoleConfirmation": false,
  "motdText": "A new world awaits",
  "resetCount": 0,
  "deathsSinceLastReset": 0
}
```

With tracking enabled, the displayed MOTD is `Reset #1 : 'A new world awaits'`.
The number shown is the current world's number. With tracking disabled, the
normal `motd` value from `server.properties` is used unchanged.

When `requireConsoleConfirmation` is true, the wipe still kills all online
players, but shutdown pauses until an administrator types `confirmreset` in the
server console. For safety, that command is rejected when entered by a player.

The watchdog only deletes a direct child directory of the server directory and
only after the mod writes a valid reset marker during an intentional reset.

## Build

Requires Java 25:

```text
./gradlew build
```

The distributable JAR is created under `build/libs/` (not the `-sources` JAR).
