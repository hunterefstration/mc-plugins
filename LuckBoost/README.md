# LuckBoost

Admin-only luck boost for Paper 1.21.11. Ops give a player a level; every loot roll that player
causes is rolled that many extra times (with that much more luck) and they get the best result.
The player is never told.

Covers loot chests, barrels and minecart chests, trial vaults and trial spawners, fishing, mob
drops, block drops (ores, leaves, gravel...) and piglin bartering, and adds the level to the
player's Luck attribute.

## Commands (ops only, permission `luckboost.use`)
- `/lb <player> <1-50>`: set a level (level N = best of N+1 rolls).
- `/lb <player> off`: remove it.
- `/lb <player>`: show their level.
- `/lb list`: everyone with a boost.

Levels are kept in `plugins/LuckBoost/luck.yml`. The first time it runs, it copies the levels from
`plugins/SurvivalCore/luck.yml` (from when the boost was part of SurvivalCore).

## Build
`./gradlew jar` (Java 21); the jar is in `build/libs/`.
