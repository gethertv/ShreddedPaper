# LEGENDA.GG — Gildie engine fork

Development branch: `legenda-gildie-1.21.11`.

Initial upstream: [MultiPaper/ShreddedPaper](https://github.com/MultiPaper/ShreddedPaper),
commit `a5e696092dba4029e1bbe9b7ea2d4bf4fefc4ee1` (`ver/1.21.11`).
This branch targets Minecraft 1.21.11 and Java 21. It is experimental;
creating this fork does not establish plugin compatibility or performance.
Upstream's GPL-3.0 license and authorship remain unchanged.

## Build

Use JDK 21 and Git Bash on Windows, as required by upstream. Allow long Git
paths for this build process on Windows (`GIT_CONFIG_COUNT=1`,
`GIT_CONFIG_KEY_0=core.longpaths`, `GIT_CONFIG_VALUE_0=true`).

```sh
./gradlew applyAllPatches --no-daemon --max-workers=4 -Dorg.gradle.jvmargs=-Xmx4g
./gradlew createMojmapPaperclipJar --no-daemon --max-workers=4 -Dorg.gradle.jvmargs=-Xmx4g
```

Record the source commit and artifact SHA-256 for every deployed build.
Keep source changes as upstream-style patches, with a regression reproducing
the bug. Do not disable ownership/thread checks to hide plugin errors.

## Private Gildie rollout

The initial target is the whitelisted `legenda_gildie_1` instance. Confirm actual
occupancy from its console before stopping it. Back up the stopped world's data,
plugins, configuration and startup script before a version upgrade. Rollback
restores the pre-upgrade data as well as the previous engine; never start 1.21.4
against a world already saved by 1.21.11.

Start with four region workers, eight-chunk regions and upstream's synchronous
compatibility mode for plugins without Folia support. This mode is not a plugin
compatibility guarantee. Compare the intended plugin list with enabled plugins,
then test joins, inventories, permissions, territory protection and saves.
Only a separate measured battle/TNT test can establish performance gains.

Operational records and mode configuration live in the Gether-Core repository
under `examples/legenda/gildie/`.

## Signed chat packet ordering (2026-10-20)

Signed commands previously deferred decoding to a player tick while subsequent
chat packets decoded on the network thread. That could advance the signed chain
out of order, producing `invalid_signature` followed by `chain_broken`.
Chat, signed commands and ACK packets now enter the same player-owned queue as
chat-session updates. A command already on that thread runs without another
queue hop. Decoration and broadcasting keep their asynchronous pipeline.

`ChatPacketOrderingTestSuite` exercises the actual packet handlers with a queued
owner scheduler and separately reproduces the permanent failure using real RSA
signatures. The ordering test failed before the patch; both tests pass after it:

```sh
./gradlew :shreddedpaper-server:test --tests '*ChatPacketOrderingTestSuite' --no-daemon --max-workers=4 -Dorg.gradle.jvmargs=-Xmx4g
```

This proves the ordering regression and decoder failure, not the cause of every
live chat error. A client session through Velocity/FreedomChat still needs
confirmation after rollout.
