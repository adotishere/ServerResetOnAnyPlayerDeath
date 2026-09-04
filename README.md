# Server Reset On Any Player Death

A dedicated-server-only Fabric mod that turns the whole server into a shared
hardcore run. When the configured death allowance is reached, the original
death message is announced in quotes without showing a death screen. After the
configured delay, every online player's gameplay data is cleared and everyone
is moved into a newly generated world set containing an Overworld, Nether, and
End. The previous three gameplay dimensions are then unloaded and deleted
without restarting the server.

The vanilla Overworld remains as an internal fallback while gameplay takes place
in rotating world sets such as `server_reset_hardcore:reset_1`,
`server_reset_hardcore:reset_1_nether`, and
`server_reset_hardcore:reset_1_end`. Nether and End portals remain inside the
active set. Players are automatically sent to its Overworld when they join.

## Install

- Configuration is stored in `config/server_reset_hardcore.json`.
- The mod creates `persistent-datapacks` beside the server files on first start.
  Datapacks placed there are copied into the main world's datapack folder before
  startup so they are available to every generated gameplay world.

## Configuration

```json
{
  "allowedDeaths": 1,
  "resetDelaySeconds": 5,
  "trackResets": true,
  "requireConsoleConfirmation": false,
  "motdText": "A new world awaits",
  "resetCount": 0,
  "deathsSinceLastReset": 0,
  "activeWorldSeed": 123456789
}
```

`allowedDeaths`, `resetDelaySeconds`, `trackResets`,
`requireConsoleConfirmation`, and `motdText` are intended for server owners to
edit. The remaining values are maintained by the mod so progress survives a
normal server restart. Existing v1 configs are migrated from
`shutdownDelaySeconds` to `resetDelaySeconds` automatically.

With reset tracking enabled, the MOTD is a two-line gold-and-gray message with
the active world number. With tracking disabled, the original `motd` value in
`server.properties` is restored.

When console confirmation is enabled, the console displays
`Reset the world? [Y/n]` after the triggering death message. Enter `Y` or press
Enter to continue; enter `N` to cancel. Responses entered by players are
rejected.

## Simple launch example

No restart loop is needed for world resets. A normal server batch file is
enough:

```bat
@echo off
java -jar fabric-server-launch.jar nogui
pause
```
