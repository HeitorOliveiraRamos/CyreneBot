package com.cyrene.discord.command

import com.cyrene.discord.util.BotMessages
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * `/mutar membro: minutos: [motivo:]` — applies a Discord timeout. Lifting one is
 * [DesmutarCommand]'s job, not a magic `0` here.
 *
 * Requires **Moderar Membros**, enforced by Discord (see [ModerationGuards.modOnly]).
 */
@Component
class MutarCommand : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "mutar"

    override val definition: CommandData =
        Commands.slash(name, "Silencia um membro por um tempo")
            .setDefaultPermissions(ModerationGuards.modOnly(Permission.MODERATE_MEMBERS))
            .setGuildOnly(true)
            .addOption(OptionType.USER, "membro", "Quem silenciar", true)
            .addOption(OptionType.INTEGER, "minutos", "Duração em minutos (1 a 10080 = 7 dias)", true)
            .addOption(OptionType.STRING, "motivo", "Motivo registrado no audit log", false)

    override fun handle(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.replyEphemeral(BotMessages.GUILD_ONLY)
        val target = event.getOption("membro")?.asMember
            ?: return event.replyEphemeral("Não achei essa pessoa aqui no servidor 😔")
        val minutes = event.getOption("minutos")!!.asLong
        val reason = event.getOption("motivo")?.asString?.take(500) ?: "Sem motivo informado"

        if (minutes !in 1..10080) {
            return event.replyEphemeral("Os minutos precisam estar entre 1 e 10080 (7 dias). Pra liberar alguém, usa `/desmutar`.")
        }
        ModerationGuards.checkTarget(event, target, Permission.MODERATE_MEMBERS)
            ?.let { return event.replyEphemeral(it) }

        event.deferReply().queue()
        guild.timeoutFor(target, Duration.ofMinutes(minutes)).reason(reason).queue(
            {
                log.info("AUDIT mutar caller={} target={} guild={} minutos={}", event.user.id, target.id, guild.id, minutes)
                event.hook.sendMessage("${target.asMention} está de castigo por **$minutes min** 😌\n> $reason").queue()
            },
            { error ->
                log.error("mutar falhou para target={} guild={}", target.id, guild.id, error)
                event.hook.sendMessage("O Discord recusou: ${error.message}").setEphemeral(true).queue()
            },
        )
    }
}
