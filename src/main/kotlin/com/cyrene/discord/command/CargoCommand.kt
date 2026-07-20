package com.cyrene.discord.command

import com.cyrene.discord.util.BotMessages
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `/cargo acao:<dar|tirar> membro: cargo:` — adds or removes a role.
 *
 * One command with an action choice rather than two near-identical ones: the target,
 * the guards and the audit line are the same either way, only the JDA call differs.
 * The role arrives as a real [OptionType.ROLE] snowflake, so there is no name matching
 * and no ambiguity when two roles share a name.
 *
 * Requires **Gerenciar Cargos**, enforced by Discord (see [ModerationGuards.modOnly]).
 */
@Component
class CargoCommand : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "cargo"

    override val definition: CommandData =
        Commands.slash(name, "Dá ou tira um cargo de um membro")
            .setDefaultPermissions(ModerationGuards.modOnly(Permission.MANAGE_ROLES))
            .setGuildOnly(true)
            .addOptions(
                net.dv8tion.jda.api.interactions.commands.build.OptionData(
                    OptionType.STRING, "acao", "Dar ou tirar o cargo", true,
                ).addChoice("dar", ACTION_ADD).addChoice("tirar", ACTION_REMOVE),
            )
            .addOption(OptionType.USER, "membro", "De quem", true)
            .addOption(OptionType.ROLE, "cargo", "Qual cargo", true)

    override fun handle(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.replyEphemeral(BotMessages.GUILD_ONLY)
        val action = event.getOption("acao")!!.asString
        val target = event.getOption("membro")?.asMember
            ?: return event.replyEphemeral("Não achei essa pessoa aqui no servidor 😔")
        val role = event.getOption("cargo")!!.asRole

        // Roles are not a destructive action on the PERSON, so the self/owner guards in
        // checkTarget don't apply — only the bot's permission and both hierarchies do.
        if (!guild.selfMember.hasPermission(Permission.MANAGE_ROLES)) {
            return event.replyEphemeral("Eu não tenho a permissão **Gerenciar Cargos** neste servidor 😔")
        }
        ModerationGuards.checkRole(event, role)?.let { return event.replyEphemeral(it) }

        event.deferReply().queue()
        val adding = action == ACTION_ADD
        val call = if (adding) guild.addRoleToMember(target, role) else guild.removeRoleFromMember(target, role)

        call.reason("${if (adding) "Adicionado" else "Removido"} por ${event.user.name}").queue(
            {
                log.info(
                    "AUDIT cargo/{} caller={} target={} role={} guild={}",
                    action, event.user.id, target.id, role.id, guild.id,
                )
                val verb = if (adding) "agora tem" else "não tem mais"
                event.hook.sendMessage("${target.asMention} $verb o cargo **${role.name}** 💜").queue()
            },
            { error ->
                log.error("cargo/{} falhou para target={} role={}", action, target.id, role.id, error)
                event.hook.sendMessage("O Discord recusou: ${error.message}").setEphemeral(true).queue()
            },
        )
    }

    private companion object {
        const val ACTION_ADD = "dar"
        const val ACTION_REMOVE = "tirar"
    }
}
