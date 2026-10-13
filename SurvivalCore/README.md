# SurvivalCore

One Paper 1.21.11 plugin that replaces these:

| Old plugin | In SurvivalCore |
|---|---|
| EffectBooks | `effect-books` |
| VeinMiner | `vein-miner` |
| CheapGaps, EzCobwebs | `recipes` |
| ClickVillagers | `villager-pickup` |
| InstantRestock | `instant-restock` |
| EndPortalTimer | `end-portal` |
| DupersBack | `dupers` |
| PaperAttributeSwapFix | `attribute-swap` |
| RodStasis | `rod-stasis` |
| BetterEnderChest | `ender-chest` |
| Revaulting | `revaulting` |
| SkinsRestorer | `skins` |

These are **not** included: CoreProtect (keep it as it is), Simple Voice Chat, and Item-NBT-API, which is only
a library for other plugins. ServerTweaks and SurvivalTweaks aren't included either, because there are several
plugins with those names and we couldn't tell which ones these are.

Every feature can be turned off in `config.yml`. Run `/sc` to see what's on, and `/sc reload` after
editing the config.

## Install
1. Stop the server.
2. Remove the old jars listed in the table above (keep CoreProtect, Simple Voice Chat, and Item-NBT-API if
   other plugins need it). **Don't delete the `plugins/EffectBooks` or `plugins/BetterEnderChest` folders yet.**
   SurvivalCore imports from them.
3. Drop `SurvivalCore-1.0.0.jar` into `plugins/` and start the server.

## Features

### Effect books (`/eb`, `/book`)
Everything works as before (see `effectbooks.yml`), except **the book no longer takes up an inventory slot**.
A holder's glowing book sits in the **output slot of the 2x2 crafting grid** in their inventory. It only shows
when that grid is empty, so it never gets in the way of crafting, and nobody else ever sees it.
- Click it (or type `/book`) to put it in your hand. It uses an empty hotbar slot, so nothing in your inventory moves.
- Right-click to write names, then press **Done**. The book goes back on its own. It also goes back if you switch
  items or press Q.
- If you hold several books, clicking opens a small menu to pick one.
- Books and names from the old EffectBooks plugin are imported on first start. Old book items in inventories
  are cleaned up automatically.

### VeinMiner (`/veinminer on|off`)
Mining one ore or log breaks the whole connected vein or tree, **whether you're sneaking or not**.
- Ores: coal, iron, copper, gold, redstone, lapis, diamond, emerald, quartz and ancient debris. Deepslate and normal
  ores count as the same vein.
- Trees: any log or stem, and only with an axe. Up to 64 ores or 256 logs.
- Every block is broken as if you mined it yourself, so Fortune, Silk Touch, Unbreaking, XP and CoreProtect logging
  all work.
- It stops before your tool breaks, and drops land at the block you mined.

### Recipes
- **Cobweb**: 5 string in an X (or a plus).
- **Golden apple**: an apple with 4 gold ingots around it (plus shape).
- Optional: **enchanted golden apple** from an apple and 8 gold blocks. It's off by default.

### Villager pickup
Sneak and right-click a villager with an empty hand to pick it up. Right-click a block to place it back.
- Trades, level, XP, profession, name and discounts are all kept.
- The item's lore lists the villager's trades.
- The item can't be used as a normal spawn egg or in a dispenser, so villagers can't be duplicated.

### Instant restock
Villagers never run out. Trades never lock, even if you use one over and over, and prices don't go up from heavy
trading. Wandering traders are included unless you turn them off.

### End portal lock (`/endportal`)
The End is **closed until you open it**:
- The 12th eye can't be placed. The first 11 can, so players can see they found a portal.
- Any portal that already exists won't let players or mobs through.
- `/endportal open` opens it now. `/endportal open in 2h30m` opens it on a timer and announces it in chat.
- `/endportal close` closes it again.
- Ops use `survivalcore.endportal.admin`. `survivalcore.endportal.bypass` lets a player through while it's closed.

### Dupers
- **Tripwire hook dupers** work again by turning on Paper's own `skip-tripwire-hook-placement-validation`, so
  this is the real vanilla code running.
- **String dupers** work again. Mojang removed string duping in 1.21.2, so no Paper setting can restore it.
  SurvivalCore re-creates the exact 1.21.1 rule: when a *disarmed* string in an attached hook-to-hook line is
  destroyed (by water, a piston, an explosion, a player, shears...), the hooks detach and put the string back.
  You get the dropped string and the string stays. Old automatic string farms work as they did.
- Optional, off by default: TNT/carpet/rail duping and sand/gravel (end portal) duping.

### Attribute swapping
Paper stops "attribute swapping" (hit with one item, swap in the same tick). This turns Paper's
`update-equipment-on-player-actions` off, which puts vanilla timing back exactly.

### Fishing rod stasis
Paper deletes your bobber when you change dimension, which fires your stasis chamber early. Now your bobber's
spot is held by an invisible stand-in until you **reel in with your rod from anywhere**. Then it lets go, the
chamber fires, and the rod loses 2 durability like vanilla.

Paper settings changed by Dupers and AttributeSwap only change while the server runs. `paper-global.yml` is
never edited, and the original values come back if the feature is turned off.

### Bigger ender chests (`/enderchest`)
Ender chests have 6 rows by default. You can set 3–6 rows in `config.yml`, or give more to some players with
`survivalcore.enderchest.rows.<n>`.
- The first 3 rows *are* the normal vanilla ender chest. The extra rows are saved in
  `plugins/SurvivalCore/enderchests/`.
- **BetterEnderChest import:** the first time each player opens their ender chest, their old BetterEnderChest
  contents are moved in automatically and the import is logged. Only BetterEnderChest's default world group is
  imported. If an import fails, the player sees their vanilla chest, the old file is left untouched, and the
  error is logged.
- `/enderchest` opens your own chest anywhere (op by default, `survivalcore.enderchest.command`).
  `/enderchest <player>` opens someone else's (`survivalcore.enderchest.others`).

### Revaulting
Trial chamber vaults (normal and ominous) can be opened again with another key. An optional cooldown is set
in ticks.

### Skins (`/skin <name>`, `/skin clear`)
- Wear any Minecraft account's skin. There's a 30-second cooldown, which ops skip.
- `/skin <name> <player>` changes someone else's skin (`survivalcore.skin.others`).
- On offline-mode servers, everyone automatically gets the skin of the account with their username.
- Skins chosen with SkinsRestorer aren't carried over, so players need to run `/skin` once.

## Build
```bash
./gradlew build
```
This runs the unit tests and outputs `build/libs/SurvivalCore-1.0.0.jar`.
