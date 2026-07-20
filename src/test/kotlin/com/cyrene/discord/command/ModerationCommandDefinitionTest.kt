package com.cyrene.discord.command

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The moderation commands' definitions are only validated by Discord at registration time,
 * so a malformed one doesn't fail a build — it fails the bot's startup, in production, after
 * a deploy. These assertions run the same JDA builders and check the two things that
 * actually matter:
 *
 *  1. Every command declares the permission that gates it. This is the ONLY thing standing
 *     between an ordinary member and `/banir` — Discord hides and rejects the command based
 *     on it — so a missing or wrong permission here is a privilege-escalation bug, not a
 *     cosmetic one.
 *  2. Names are unique. [CommandRouter] indexes by name with `associateBy`, which silently
 *     keeps the last of a duplicate pair and drops the other.
 */
class ModerationCommandDefinitionTest {

    private val commands: List<SlashCommand> = listOf(
        LimparCommand(),
        MutarCommand(),
        BanirCommand(),
        CargoCommand(),
        CriarCanalCommand(),
        ExpulsarCommand(),
        ModoLentoCommand(),
        DesmutarCommand(),
        DesbanirCommand(),
        AvisarCommand(NoopAvisoRepository),
        AvisosCommand(NoopAvisoRepository),
    )

    /** Only the definitions are under test here; no query ever runs. */
    private val ungated: List<SlashCommand> = listOf(InfoServidorCommand(), InfoMembroCommand())

    @Test
    fun `each moderation command is gated on the permission it needs`() {
        val expected = mapOf(
            "limpar" to Permission.MESSAGE_MANAGE,
            "mutar" to Permission.MODERATE_MEMBERS,
            "banir" to Permission.BAN_MEMBERS,
            "cargo" to Permission.MANAGE_ROLES,
            "criar-canal" to Permission.MANAGE_CHANNEL,
            "expulsar" to Permission.KICK_MEMBERS,
            "modo-lento" to Permission.MANAGE_CHANNEL,
            "desmutar" to Permission.MODERATE_MEMBERS,
            "desbanir" to Permission.BAN_MEMBERS,
            "avisar" to Permission.MODERATE_MEMBERS,
            "avisos" to Permission.MODERATE_MEMBERS,
        )
        for (command in commands) {
            val permission = expected.getValue(command.name)
            // DefaultMemberPermissions has no equals(), so compare the raw permission bits.
            assertEquals(
                permission.rawValue,
                (command.definition as SlashCommandData).defaultPermissions.permissionsRaw,
                "/${command.name} must be gated on ${permission.getName()}",
            )
        }
    }

    @Test
    fun `no command is left open to everyone`() {
        for (command in commands) {
            val raw = (command.definition as SlashCommandData).defaultPermissions.permissionsRaw
            // ENABLED carries a null raw value — "no restriction, anyone may run it".
            assertTrue(raw != null && raw != 0L, "/${command.name} would be runnable by any member")
        }
    }

    @Test
    fun `the read-only info commands are deliberately open to everyone`() {
        // They expose nothing Discord's own UI doesn't; gating them would only make them
        // useless to the people who ask. Asserted so the choice is explicit, not an oversight.
        for (command in ungated) {
            val perms = (command.definition as SlashCommandData).defaultPermissions
            assertEquals(DefaultMemberPermissions.ENABLED, perms, "/${command.name} should stay open")
        }
    }

    @Test
    fun `command names are unique, so the router can't silently drop one`() {
        val names = (commands + ungated).map { it.name }
        assertEquals(names.size, names.toSet().size, "duplicate command name in $names")
    }

    @Test
    fun `every definition builds into valid command data`() {
        // Builds the payload JDA would send. Invalid names, descriptions or option shapes
        // throw here instead of at startup.
        for (command in commands + ungated) {
            val data = command.definition.toData()
            assertEquals(command.name, data.getString("name"))
            assertTrue(data.getString("description").isNotBlank(), "/${command.name} needs a description")
        }
    }
}

/**
 * Stand-in for [com.cyrene.moderation.AvisoRepository] so the warning commands can be
 * constructed for a definition-only check. Every method throws: if a definition ever starts
 * touching the database, this fails loudly instead of silently passing.
 */
private object NoopAvisoRepository : com.cyrene.moderation.AvisoRepository by NoopDelegate

private val NoopDelegate: com.cyrene.moderation.AvisoRepository =
    java.lang.reflect.Proxy.newProxyInstance(
        com.cyrene.moderation.AvisoRepository::class.java.classLoader,
        arrayOf(com.cyrene.moderation.AvisoRepository::class.java),
    ) { _, method, _ -> error("AvisoRepository.${method.name} must not be called while building a definition") }
        as com.cyrene.moderation.AvisoRepository
