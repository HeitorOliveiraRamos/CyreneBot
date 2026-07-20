package com.cyrene.discord.command

import com.cyrene.discord.util.BotMessages
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `/desbanir usuario_id:` — reverses a ban.
 *
 * The only moderation command that takes a raw ID instead of a user picker: a banned user is
 * not a guild member, so Discord has nobody to offer in the dropdown. That makes the
 * snowflake check ([ModerationGuards.isSnowflake]) load-bearing — free text reaches JDA here,
 * where every other command hands it a resolved member.
 *
 * Requires **Banir Membros**, enforced by Discord (see [ModerationGuards.modOnly]).
 */
@Component
class DesbanirCommand : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "desbanir"

    override val definition: CommandData =
        Commands.slash(name, "Remove o banimento de alguém (pelo ID, já que a pessoa não está no servidor)")
            .setDefaultPermissions(ModerationGuards.modOnly(Permission.BAN_MEMBERS))
            .setGuildOnly(true)
            .addOption(OptionType.STRING, "usuario_id", "ID numérico do usuário banido", true)

    override fun handle(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.replyEphemeral(BotMessages.GUILD_ONLY)
        val userId = event.getOption("usuario_id")!!.asString.trim()

        if (!ModerationGuards.isSnowflake(userId)) {
            return event.replyEphemeral(
                "Isso não parece um ID válido — é só números 💜 " +
                    "Ative o Modo Desenvolvedor no Discord e copie o ID da pessoa na lista de banidos.",
            )
        }
        if (!guild.selfMember.hasPermission(Permission.BAN_MEMBERS)) {
            return event.replyEphemeral("Eu não tenho a permissão **Banir Membros** neste servidor 😔")
        }

        event.deferReply().queue()
        guild.unban(UserSnowflake.fromId(userId)).reason("Desbanido por ${event.user.name}").queue(
            {
                log.info("AUDIT desbanir caller={} target={} guild={}", event.user.id, userId, guild.id)
                event.hook.sendMessage("Desbanido 💜 <@$userId> já pode voltar com um convite novo.").queue()
            },
            { error ->
                // The common case by far: the id is real but was never banned here.
                log.warn("desbanir falhou para target={} guild={}: {}", userId, guild.id, error.message)
                event.hook.sendMessage(
                    "Não consegui desbanir — confere se esse ID está mesmo na lista de banidos daqui. " +
                        "(Discord: ${error.message})",
                ).setEphemeral(true).queue()
            },
        )
    }
}
