package com.cyrene.discord.command

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.channel.attribute.ISlowmodeChannel
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `/modo-lento segundos:` — sets the current channel's slowmode; `0` turns it off.
 *
 * Channel-scoped rather than guild-scoped on purpose: both the caller's and the bot's
 * permission are evaluated against THIS channel, so a per-channel override is honoured in
 * both directions (Discord's default-permission gate only knows about the guild-wide one).
 *
 * Requires **Gerenciar Canais** (see [ModerationGuards.modOnly]).
 */
@Component
class ModoLentoCommand : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "modo-lento"

    override val definition: CommandData =
        Commands.slash(name, "Define o intervalo mínimo entre mensagens neste canal (0 desliga)")
            .setDefaultPermissions(ModerationGuards.modOnly(Permission.MANAGE_CHANNEL))
            .setGuildOnly(true)
            .addOption(
                OptionType.INTEGER,
                "segundos",
                "Segundos entre mensagens de cada pessoa (0 desliga, máximo 21600 = 6h)",
                true,
            )

    override fun handle(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return
        val seconds = event.getOption("segundos")!!.asInt
        val channel = event.channel as? ISlowmodeChannel
            ?: return event.replyEphemeral("Este tipo de canal não tem modo lento.")

        if (seconds !in 0..ISlowmodeChannel.MAX_SLOWMODE) {
            return event.replyEphemeral("Os segundos precisam estar entre 0 e ${ISlowmodeChannel.MAX_SLOWMODE} (6 horas).")
        }
        // Re-checked against this channel: a guild-wide permission can be denied here.
        if (!guild.selfMember.hasPermission(channel, Permission.MANAGE_CHANNEL)) {
            return event.replyEphemeral("Eu não tenho a permissão **Gerenciar Canais** neste canal 😔")
        }

        event.deferReply().queue()
        channel.manager.setSlowmode(seconds).reason("Ajustado por ${event.user.name}").queue(
            {
                log.info("AUDIT modo-lento caller={} channel={} guild={} s={}", event.user.id, channel.id, guild.id, seconds)
                val msg = if (seconds == 0) {
                    "Modo lento desligado — pode falar à vontade 💜"
                } else {
                    "Modo lento ligado: **${seconds}s** entre mensagens. Respira 😌"
                }
                event.hook.sendMessage(msg).queue()
            },
            { error ->
                log.error("modo-lento falhou em channel={}", channel.id, error)
                event.hook.sendMessage("O Discord recusou: ${error.message}").setEphemeral(true).queue()
            },
        )
    }
}
