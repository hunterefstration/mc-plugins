# EffectBooks

Paper/Spigot 1.21.11 plugin. Book holders write player names in a glowing **Book of &lt;Effect&gt;**,
and everyone written in it keeps that effect permanently.

## Install
Drop `build/libs/EffectBooks-1.0.0.jar` into your server's `plugins/` folder and restart.

## Config (`plugins/EffectBooks/config.yml`)
```yaml
books:
  Steve: SPEED
  Alex: STRENGTH:2        # optional level
  Notch:                  # several books for one holder
    - NIGHT_VISION
    - HASTE:2
```
Run `/eb reload` after editing by hand. Names written in books are stored in `data.yml`.

## How it works
- A listed holder gets their book (book & quill, enchant glint) when they join.
- Write **one player name per line**. Press *Done* to save. Signing is blocked, so the book stays editable.
- Everyone listed gets the effect as an infinite potion effect, and offline players get it on their next join.
  Milk, death, totems and `/effect clear` can't remove it. A background check re-applies it every 2 seconds.
- Erasing a name removes the effect. Normal potions of the same type are left alone.
- If a player is in two books with the same effect, the higher level wins.
- Every effect in the game can be used. Instant Health and Instant Damage can't last forever, so they give one dose
  every check (every 2 seconds by default). **An Instant Damage book can kill the players written in it.**

## Book protection
The book can't leave the holder's inventory. It can't be dropped, put in a chest, shift-clicked into
containers, placed in a crafting grid, bundle, item frame, lectern, chiseled bookshelf, shelf, decorated pot
or armor stand, or given to an allay. It never drops on death and comes back on respawn. Lava, fire, cactus,
explosions, the void and despawning can't reach it.
If it's ever removed anyway (for example with `/clear`), it's handed back within 2 seconds.
Copies in anyone else's inventory are deleted. Only the holder can write in it.
**Only an admin can delete a book**, using `/eb`.

## Admin (`effectbooks.admin`, default: op)
- `/eb` opens the menu:
  - list all books
  - **Add a book** (type the holder name in chat, then pick an effect)
  - Click a book to open its menu:
    - **Reassign holder**: the book and its names move to the new holder
    - **Change effect**
    - **Level** (+/-)
    - **Players in this book**: add or remove names
    - **Delete**: clears the effect from everyone in the book
- `/eb list`, `/eb add <holder> <effect> [level]`, `/eb delete <holder> <effect>`, `/eb reload`

Removing a holder from `config.yml` counts as deleting their book. If a config line has a typo,
that book's names are kept (inactive) until the line is fixed.

## Notes
- Holders and targets are matched by username. If a player changes their name, reassign the book or edit its names.
- If you uninstall the plugin, effects that were already given stay on players, because they are infinite potion effects.
  Delete the books first to clear them.

## Build
```bash
./gradlew build
```
Runs the 27 MockBukkit tests and outputs `build/libs/EffectBooks-1.0.0.jar`.
