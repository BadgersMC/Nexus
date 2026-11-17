# Paper command composition (LumaGuilds migration prerequisites)

The `nexus-paper` command adapter groups classes with the same `@Command(name = "...")` into one Brigadier root tree. This supports splitting a large command into independently injectable, independently reviewable command classes without duplicating registration for the same literal.

## Example

```kotlin
@Command(name = "guild", aliases = ["g"])
class GuildRoot(private val menus: GuildMenus) {
    @Subcommand("") // /guild and /g
    fun menu(@Context player: Player) = menus.open(player)
}

@Command(name = "guild", aliases = ["g"])
class GuildHomes(private val homes: GuildHomeService) {
    @Subcommand("home") // /guild home [name]
    @Permission("guild.home")
    fun home(
        @Context player: Player,
        @Arg("name", required = false) name: String = "main"
    ) = homes.teleport(player, name)

    @Subcommand("announce")
    fun announce(@Context player: Player, @Arg("message") @Greedy message: String) {
        // `message` includes whitespace through end of input.
    }
}
```

## Rules

- Repeated root names are composed into one registration and aliases are deduplicated; different root permissions or descriptions are rejected.
- Two handlers cannot claim the same literal path, including the empty root/default path; errors occur before the Paper lifecycle registration event.
- Nested literals share parents; child and parent permissions remain separate. Prefix visibility is permitted if any descendant is permitted; executing a route always checks its own permission and player-only requirement.
- `@Subcommand("")` denotes the root/default handler (not ACF's `@Default`).
- `@Arg(required = false)` and `@Arg(defaultValue = "...")` produce executable shorter Brigadier paths. Optional args must be trailing. Omitted arguments use the annotation default, the Kotlin parameter default, or `null` if nullable. Non-null optional parameters without either default are rejected by scanning.
- `@Greedy` is valid only on the final String `@Arg`; useful for guild chat and announcements.
- `@Suggests("providerName")` still resolves from the `suggestionProviders` map supplied to `registerPaperCommands()`.
- `@Arg("name")` supplies the Brigadier argument name even when the Kotlin parameter identifier differs.
- These are Nexus annotations, not drop-in ACF annotations. Migrate source deliberately, retaining command aliases, permissions and completion contracts.

## Boundaries

This is **command infrastructure only**, not the LumaGuilds migration. The existing NexusContext DI and lifecycle APIs remain unchanged. Large ACF command suites need a command-by-command parity inventory: syntax, optional/greedy arguments, async/main-thread constraints, player-only behavior, command help, and completions. Remaining ACF features (such as `@Conditions`, custom contexts, or automated help) must be implemented or deliberately rewritten before removal.

Run `./gradlew :nexus-paper:test` on JDK 25. Do not publish a Nexus version until CI and the LumaGuilds command parity review pass.
