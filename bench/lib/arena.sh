# The flat bench arena, shared by bench/ladder.sh and bench/diag/phase1-w4.sh. Sourced; the caller defines
# say (one console line to the server) and wait_for (<pattern> <seconds>, 0 when the pattern appears in its log).
#
# The same flat arena as bench/saturate.sh: empty sky so worldgen cannot vary between arms, walls so
# nothing falls out, pillars with gaps so a route is a search and not a straight line.
#
# pw_arena returns non-zero, with the reason on stdout, when the arena is not what it claims to be. Before
# 2026-09-18 nothing checked: every gamerule used the pre-26.1 names and failed, and Carpet's fake player was
# removed a second after joining (bench/lib/hold.sh, WHY E4MC), so no run had a target for its mobs.

# name value: the 26.1 names, from the game's own GameRuleRegistryFix. doFireTick became a spread radius.
PW_ARENA_RULES=(
  "spawn_mobs false" "random_tick_speed 0" "advance_time false" "advance_weather false"
  "fire_spread_radius_around_player 0" "mob_drops false" "immediate_respawn true" "max_entity_cramming 0"
)

pw_arena() {
  say "forceload add -60 -60 60 60"
  say "fill -60 200 -60 60 200 60 minecraft:stone"
  for y in 201 202 203; do say "fill -60 $y -60 60 $y 60 minecraft:air"; done
  say "fill -60 201 -60 60 203 -60 minecraft:stone"
  say "fill -60 201 60 60 203 60 minecraft:stone"
  say "fill -60 201 -60 -60 203 60 minecraft:stone"
  say "fill 60 201 -60 60 203 60 minecraft:stone"
  for x in -40 -24 -8 8 24 40; do say "fill $x 201 -56 $x 203 56 minecraft:stone"; done
  for x in -40 -24 -8 8 24 40; do say "fill $x 201 -2 $x 203 2 minecraft:air"; done
  sleep 5
  local rule
  for rule in "${PW_ARENA_RULES[@]}"; do say "gamerule $rule"; done
  say "time set midnight"
  say "kill @e[type=!minecraft:player]"
  sleep 3
  for rule in "${PW_ARENA_RULES[@]}"; do
    wait_for "Gamerule ${rule% *} is now set to: ${rule#* }" 10 || { echo "ARENA: gamerule ${rule% *} was not set"; return 1; }
  done

  # A player must be present: this pack ships letmedespawn, which removes mobs with no player nearby
  # whatever PersistenceRequired says. The fake player is also what every zombie paths toward, and it
  # is in SURVIVAL because a spectator is not a valid target and the swarm would have nothing to chase.
  say "carpet commandPlayer true"
  sleep 2
  say "player Bench spawn at 0 201 0 facing 0 0"
  wait_for 'Bench.*logged in with entity id' 60 || { echo "ARENA: the fake player never logged in"; return 1; }
  sleep 3
  say "list"
  wait_for 'There are 1 of a max of [0-9]* players online: Bench' 10 ||
    { echo "ARENA: the fake player is not online after joining"; return 1; }
  say "gamemode survival Bench"
  say "effect give Bench minecraft:resistance 99999 255 true"
  say "effect give Bench minecraft:regeneration 99999 255 true"
  wait_for "Set Bench's game mode to Survival Mode" 10 || { echo "ARENA: the fake player's game mode was not set"; return 1; }
  sleep 2
}

# The player is still there and has moved where the last teleport sent it. For a caller's end-of-run check.
pw_arena_teleports_landed() {  # <log> -> the number of successful teleports of Bench
  grep -ac '^.*Teleported Bench to ' "$1"
}
