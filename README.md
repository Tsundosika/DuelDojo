# Duel Dojo

Single-player sword PvP practice for Fabric (Minecraft 1.21.8). Pick a bot from ten skill tiers,
**LT5** (weakest) to **HT1** (strongest), and fight it 1v1 with the same kit you get.

Each tier changes how the bot *plays*, not its stats: every bot has 20 health, full diamond armor and a
diamond sword, and follows the same rules you do. The tiers are placed using real data: how sword players
are spread over a large public tier list, plus research on human reaction time, aiming and timing.

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.8 (Java 21 or newer).
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) for 1.21.8 and `dueldojo-<version>.jar` from the
   [Releases page](https://github.com/Tsundosika/DuelDojo/releases) into your `mods` folder.
3. Start the game, open a single-player world (cheats don't need to be on) and type `/dojo`.

The current versions are betas: the tiers are calibrated in bot-vs-bot simulations, but not yet checked
against many real players. Feedback is welcome in the [issues](https://github.com/Tsundosika/DuelDojo/issues).

## Using it

| Command | What it does |
|---|---|
| `/dojo` | Clickable list of the ten tiers (hover for a description), plus first-to-10 buttons |
| `/dojo <tier>` | One fight in the sky arena, e.g. `/dojo lt3` |
| `/dojo <tier> <n>` | First to `n` rounds (1–20), like a tier test, e.g. `/dojo lt3 10` |
| `/dojo <tier> [n] here` | The same, but right where you stand instead of in the arena |
| `/dojo stats` | Your won/lost rounds, hit rate and crit share per tier |
| `/dojo stop` | Give up / cancel the current duel |
| `/dojo ladder [rounds]` | Bot vs bot: every tier against the next one up (see [Checking the tiers](#checking-the-tiers)) |
| `/dojo sim <a> <b> [rounds]` | Bot vs bot: one pairing, e.g. `/dojo sim lt3 ht3 50` |
| `/dojo sim stop` | Cancel a running simulation |
| `/dojo reload` | Re-read `config/dueldojo-tiers.json` after editing it |

`ladder`, `sim` and `reload` are for operators or the owner of a single-player world.

A duel works like this:
1. Your inventory, game mode, health, hunger, effects and position are saved.
2. An arena is built near the top of the world above you: a 31 x 31 mat floor with 4-high wooden walls (an
   original design). It is only built where every block of it is air, so it never replaces anything.
3. You and the bot both get: diamond helmet, chestplate, leggings, boots and a diamond sword. You start
   16 blocks apart, facing each other, frozen in place for a 3-second countdown, then **FIGHT!**
4. A round ends when one of you would die. You never really die: no item drops, no respawn screen. In a
   first-to-n duel, both of you go back to your marks with full health and a fresh kit for the next round.
5. You get **VICTORY** / **DEFEAT**, the score or your record, a line with both fighters' hits, swings,
   hit rate, crits and best combo, and clickable **[Rematch]** / **[Next tier]** buttons. You're put back
   where you were with everything you had, and the arena is taken down again.

With `here`, nothing is built: the bot starts 6 blocks in front of you.

If the game closes in the middle of a duel, the next time you join that world your items and position are
put back and a leftover arena is taken down (the mod keeps a small file in the world's `dueldojo` folder while
a duel runs).

Results are stored locally in `config/dueldojo-stats.json`, one win or loss per round. Nothing is ever sent
anywhere.

## The tiers

| Tier | Share of tested players | Real position (0–1) | Bot settings (0–1) | Plays like |
|---|---|---|---|---|
| LT5 | 7.9% | 0.00 | 0.00 | Times its hits but often a bit early, panic-clicks back when hit, walks straight in |
| HT5 | 30.1% | 0.17 | 0.11 | Still swings early and panic-clicks sometimes; rarely strafes, first W-taps and crit jumps |
| LT4 | 30.2% | 0.31 | 0.22 | Steadier timing, starts to strafe, W-taps and crit-jumps now and then |
| HT4 | 14.3% | 0.41 | 0.32 | Holds some spacing, W-taps about a third of the time, starts reading your cooldown |
| LT3 | 15.2% | 0.51 | 0.41 | Solid fundamentals: strafes half the time, W-taps, crits and jump resets more often |
| HT3 | 2.2% | 0.67 | 0.59 | Reads your cooldown about half the time and punishes early swings; technique about every other exchange |
| LT2 | 6 players | 0.87 | 0.89 | Clean tracking, consistent technique, rarely wastes a hit |
| HT2 | 6 players | 0.91 | 0.94 | Out-spaces you and punishes every early swing |
| LT1 | 1 player | 0.96 | 0.97 | Near-elite reactions and timing |
| HT1 | none yet | 1.00 | 1.00 | The human-elite ceiling |

### Where the numbers come from

**1. Real tier data → each tier's skill.** The share column is a public sword tier list's distribution of
50,883 tested players (snapshot 2026-10-29). A tier's middle player sits at some percentile of that
population, for example LT3's at the 90th. That percentile becomes a z-score on a normal skill curve, and the
z-scores are rescaled so LT5 = 0 and HT1 = 1. This keeps the real spacing: LT4 → HT4 is a small step, while
LT3 → HT3 and HT3 → LT2 are big ones, because few players make it past them. Tiers 2 and 1 hold only 13
players, too few to place one by one. So LT2 sits where tier 2 starts, HT1 at the expected best player of the
whole population, and HT2 and LT1 evenly in between. See `Calibration.java`.

Strength should follow that position linearly: who beats whom depends on the skill difference (Thurstone's
model, the idea behind Elo).

**2. Human research → the two ends of the scale.** Every bot setting is interpolated linearly between a
*skill 0* and a *skill 1* value (`Profile.java`). Linear in z is the same as giving a player at percentile p
the p-quantile of each trait, if traits are normally distributed. The ends are set from human performance:

| Setting | LT5 → HT1 | Basis |
|---|---|---|
| Reaction to your swing | 290 → 170 ms median, log-normal spread | Simple visual reaction is about 250–300 ms for most people (with typical hardware lag) and about 150–200 ms for elite esports players |
| Tracking delay | 150 → 110 ms | Visual feedback for online movement corrections takes about 100–150 ms |
| Smooth pursuit gain | 0.55 → 0.95 | Pursuit of predictable motion runs at a gain just below 1 in trained people, lower in untrained |
| Flick endpoint error | 15% → 6% of the flick | Signal-dependent motor noise: error grows with movement size (Harris & Wolpert, 1998) |
| Flick speed profile | minimum-jerk, up to 600 → 1500 °/s | Smooth point-to-point movements follow a minimum-jerk profile (Flash & Hogan, 1985); aiming is a series of corrective submovements (Meyer et al., 1988), and Fitts' law holds for FPS aiming (Boudaoud et al., 2022) |
| Swing point | 80% → 91% charge | Every tier times its hits; low tiers just swing a bit early. 91% is the earliest swing that still counts as a strong hit (over 90% charge, needed for sprint knockback and crits) |
| Click timing spread | 12% → 3.5% of the cooldown | Interval timing error grows with the interval (Weber's law, scalar timing); trained people reach a few percent, untrained about 10% |
| Panic click | 35% of hits taken → none from HT4 on | Not measured anywhere; the lowest tiers sometimes click straight back after getting hit, ready or not |
| Aim point wobble | 5° → 1° | Not measured anywhere for Minecraft; set so a hitbox at 3 blocks (about ±5.7° wide) is hard to keep at LT5 and easy at HT1 |
| Technique (strafe, W-tap, crit, jump reset, spacing, cooldown reading) | none → almost always | What players at each end are known to do; checked with the ladder below |

**3. Bot-vs-bot ladder → placing the bots.** Strength is not linear in the settings: some skills, like strong
hits and spacing, cost far more than the last few milliseconds of reaction time do. So the bots were measured: `/dojo ladder` pits every
tier against the next one in parallel arenas and fits the results to Elo-style ratings (Bradley-Terry). That
measured strength curve is then inverted, so each tier's bot sits at the settings position ("Bot settings" above)
whose simulated strength matches its real position. A final ladder run checks the result (below).

The bots can't be checked against *you* automatically. Your own record does that: a player of tier X
should win about half their duels against the X bot. `/dojo stats` and the per-duel summary give you the data
to see it. If every tier feels too easy or too hard, shift the anchors in the config file.

## How the bot decides what to do

Every server tick (20 per second) the bot's brain (`BotBrain.java`) runs the same four steps. The tier only
changes the numbers it uses.

1. **Perception.** Aiming uses where you were one tracking delay ago, extrapolated along your movement
   (higher tiers extrapolate more, so strafing in a straight line stops fooling them). Your *swings* are
   noticed after a reaction time drawn from a log-normal distribution, like real reaction times.
2. **Aim.** The view follows your motion with a pursuit gain below 1. The leftover error is fixed by
   corrective flicks, one at a time, each planned only after seeing where the last one landed. Flicks have a
   minimum-jerk speed profile, a turn-speed limit, and an endpoint error that grows with the flick's size.
   Meanwhile the intended aim point wanders a little. Hits only land if the bot's **real view direction**
   passes through your hitbox within 3 blocks, so bad aim really misses.
3. **Movement.** Using normal player movement physics, it:
   - **approaches** when its cooldown is almost ready, and **holds a spacing** distance while it recharges
     (LT5 1.6 blocks, HT1 3.0, the edge of your reach), backing off if you get too close,
   - **strafes** in random-length phases (more often and shorter at high tiers),
   - **W-taps** after landing a hit (lets go of forward for a moment) so it doesn't run into your counter,
   - **crit-jumps**: once per cooldown cycle it may jump so it is *falling* when the charge passes 90%, but
     only if it expects you to be in reach by then (from its own momentum and how it sees you moving). From
     further out it sprint-jumps and lets go of sprint once it falls, because crits don't count while
     sprinting. Low tiers jump whenever that fits; tiers that read your cooldown only jump when you can't hit
     them out of the air (you just swung, or you're still flying from their hit). If you step back while it's
     in the air, the crit misses, just like against a player,
   - **jump-resets**: jumps the moment it gets hit, to take less knockback,
   - **reads your cooldown** (now and then from LT4, about half the time at HT3, almost always from LT2):
     steps in right after you swing, before you can answer,
   - plays safer when behind on health and pushes when ahead (higher tiers),
   - jumps over a block if it walks into one.
4. **Attack.** Every tier times its hits. It plans each swing by anticipation: aiming for a point of the
   cooldown (LT5 about 80%, HT1 91%, the earliest strong hit), with a timing spread that follows Weber's law,
   so low tiers often swing a little early (in the simulation about 60% of LT5's swings are full-strength,
   against all of HT1's). It clicks when its crosshair
   *looks* on you within reach, judged on the same delayed view it aims with, so if you strafe or step back
   in that moment it whiffs. The lowest tiers sometimes also **panic-click** straight back after getting hit.
   Like for a player, a miss resets the cooldown.

## Tuning: config/dueldojo-tiers.json

Written with the defaults on first start. `skill` places each tier between the anchors, `skill0` (LT5) and
`skill1` (HT1) hold every setting at the two ends, and each tier gets the straight-line value in between,
clamped to what a human can do (a negative chance means "never"). Edit, then `/dojo reload`. Delete the file
to get the defaults back. When an update changes the file's format, your old file is kept as
`dueldojo-tiers.json.old`.

## Checking the tiers

`/dojo ladder 40` runs 40 rounds of each neighbouring pairing (LT5–HT5, HT5–LT4, … LT1–HT1) in nine arenas
around you, with time sped up. Use a superflat world: hills, trees and water make rounds noisy. The report
goes to chat, the log and `dueldojo-ladder.txt` in the game folder. Rounds end when a bot would die (it
doesn't), after two minutes (draw), or if a bot gets knocked more than 30 blocks away (draw).

Besides the results, the report lists how each tier played: full-strength swings, hit rate, sprint hits
and crits, crit jumps (and how many came down without a hit), W-taps, panic clicks, strafing and jump resets.

For developers, `gradlew runClientGameTest --no-daemon` runs the same ladder automatically in a throwaway
flat world (rounds per pairing: environment variable `DUELDOJO_LADDER_ROUNDS`, default 30, `0` skips it;
`DUELDOJO_LADDER_WIDE=1` also pairs each tier with the one two above it, and `DUELDOJO_POSITIONS` takes ten
comma-separated settings positions to test instead of the defaults). The report is written to
`build/run/clientGameTest/dueldojo-ladder.txt`. Before the ladder, an arena test plays a
first-to-2 and checks that the arena goes up and comes down, the countdown freeze, and that items, position
and game mode come back, also after a simulated crash (`DUELDOJO_ARENA_TEST=0` skips it).

How the default positions were found (v1.4, 2026-10-29): three calibration runs, each pitting every tier
against the next one and the one after that (17 pairings, 120 rounds each), with the tiers at different
positions. All runs were fitted together into one strength curve, the curve was inverted, and the tiers were
moved, until another round of this moved no tier by more than about 0.01. Then a separate check run with the
final defaults, 180 rounds per pairing:

| Pairing | Upper tier wins | Elo step (simulated) | Elo step the real spacing predicts |
|---|---|---|---|
| LT5 vs HT5 | 81% | +266 | +225 |
| HT5 vs LT4 | 75% | +181 | +181 |
| LT4 vs HT4 | 72% | +126 | +135 |
| HT4 vs LT3 | 72% | +136 | +133 |
| LT3 vs HT3 | 79% | +249 | +219 |
| HT3 vs LT2 | 79% | +245 | +264 |
| LT2 vs HT2 | 60% | +81 | +59 |
| HT2 vs LT1 | 55% | +17 | +59 |
| LT1 vs HT1 | 61% | +32 | +59 |

Every tier beats the one below it, every step is within about 40 Elo of the real spacing (with 180 rounds and
the extra pairings a step is accurate to roughly ±30), the big real gaps (LT3 → HT3 → LT2, where the player
count drops from 15% to 2% to a handful) are the big gaps here too, and tiers 2 and 1 are close together, as
in the data. What the data can't give is the overall scale, how much an HT1 beats an LT5 by (here about
1,300 Elo: it wins practically every round). That comes from the two anchors and should be checked against
real players (see above).

How each tier played in that run (bot against bot):

| Tier | Full-strength swings | Swings that hit | Crits per hit | W-taps per hit | Strafing when close | Jump resets per hit taken | Panic clicks per 100 swings |
|---|---|---|---|---|---|---|---|
| LT5 | 61% | 59% | 2% | 0% | 15% | 0% | 14 |
| HT5 | 70% | 67% | 6% | 10% | 25% | 8% | 7 |
| LT4 | 80% | 74% | 11% | 21% | 33% | 15% | 2 |
| HT4 | 86% | 79% | 12% | 31% | 42% | 20% | 0 |
| LT3 | 92% | 84% | 12% | 40% | 44% | 24% | 0 |
| HT3 | 97% | 92% | 13% | 57% | 54% | 35% | 0 |
| LT2 | 100% | 96% | 8% | 86% | 73% | 57% | 0 |
| HT2 | 100% | 94% | 5% | 91% | 73% | 66% | 0 |
| LT1 | 100% | 93% | 3% | 94% | 73% | 73% | 0 |
| HT1 | 100% | 93% | 2% | 97% | 74% | 80% | 0 |

The top tiers crit rarely against each other because they only jump when the other bot can't hit them out of
the air, and an equally good bot rarely gives that chance. Against you, every early swing or whiff opens it.

## Fair play

- **Same rules as you:** 3-block reach along the bot's real view, line of sight required, the 1.9+ attack
  cooldown and damage formula, crit rules (falling, charge > 90%, not sprinting), sprint knockback and its
  slowdown, invulnerability frames, armor, and hunger-based regeneration (bots get hungry and can't sprint
  at 6 food or less).
- **No superhuman reactions:** even HT1 reacts in about 170 ms and tracks with a 110 ms delay. Turn speed is
  limited, and it can only jump when on the ground.
- **Not a cheat:** the mod only adds an opponent. It gives the player no aim help, auto-clicking, reach
  or anything else. It is meant for single-player or your own private server.

## Building from source

Requirements: **JDK 21** to run the mod; the build itself (Loom 1.19) needs Gradle to run on **JDK 25**. An
internet connection is needed for the first build.

```
git clone https://github.com/Tsundosika/DuelDojo.git
cd DuelDojo
./gradlew build        # Windows: gradlew.bat build
```

The finished mod is `build/libs/dueldojo-<version>.jar`. Put it in your `mods` folder together with
**Fabric API** for 1.21.8. To test in a development client: `./gradlew runClient`.

The artwork can be regenerated with `tools/generate-art.ps1` (Windows PowerShell).

## License

MIT, see [LICENSE](LICENSE). Third-party libraries and their licenses: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
