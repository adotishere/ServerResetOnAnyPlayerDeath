# Server Reset On Any Player Death

A dedicated-server-only Fabric mod. Deaths are counted
server-wide. When the configured allowance is reached, every other online player
is killed, the triggering player's death message is repeated in quotes, and the
server stops after a configurable delay. The supplied watchdog deletes the old
world and starts the server again.

## Install

- Configuration is stored in `config/server_reset_hardcore.json`.
- The mod creates `persistent-datapacks` beside the server files on first start.
  Datapacks placed there are copied into every newly generated world.

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

`deathsSinceLastReset` is managed by the mod. It stores how many deaths have
already counted toward `allowedDeaths`, including across normal server restarts.
It returns to `0` when a world reset is triggered.

With tracking enabled, the displayed MOTD uses two formatted lines: a bold gold
`Reset #1` heading followed by the configured MOTD text in gray. The number shown
is the current world's number. With tracking disabled, the
original `motd` value in `server.properties` is restored. The mod updates the
`motd=` property directly and keeps its backup outside the world folder.

When `requireConsoleConfirmation` is true, the wipe still kills all online
players, then the server console displays `Reset the world? [Y/n]`. Enter `Y`
or press Enter to continue; enter `N` to cancel. Player-entered responses are
rejected.

The mod only deletes a direct child directory of the server directory and only
after writing a valid reset marker during an intentional reset.

## Restart-loop example

A stopped Java process cannot start itself. If your server host does not already
restart stopped servers automatically, a minimal Windows batch loop is enough:

```bat
@echo off
:restart
java -jar fabric-server-launch.jar nogui
goto restart
```

The batch loop only starts Java again. World deletion and datapack copying are
performed by the mod before the world loads.
