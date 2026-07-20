package com.cyrene.discord.command

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionContextType
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `/limpar quantidade:N` — deletes the last N messages. What "messages" means depends on
 * where it runs:
 *
 *  - **In a DM**: only the bot's own messages. The user's own side is theirs to keep, and
 *    Discord wouldn't let us delete it anyway. Available to anyone — it's their conversation.
 *  - **In a server**: a real channel purge, requiring **Gerenciar Mensagens** from the
 *    caller (enforced by Discord via [ModerationGuards.modOnly]) and from the bot.
 *
 * Default member permissions only apply inside guilds, so gating on Gerenciar Mensagens
 * does not lock anyone out of the DM half.
 */
@Component
class LimparCommand : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "limpar"

    override val definition: CommandData =
        Commands.slash(name, "Apaga mensagens recentes (na DM, só as minhas; no servidor, as do canal)")
            .setDefaultPermissions(ModerationGuards.modOnly(Permission.MESSAGE_MANAGE))
            .setContexts(InteractionContextType.GUILD, InteractionContextType.BOT_DM)
            .addOption(OptionType.INTEGER, "quantidade", "Quantidade de mensagens a excluir (1 a 100)", true)

    override fun handle(event: SlashCommandInteractionEvent) {
        val amount = event.getOption("quantidade")?.asInt ?: 0
        if (amount !in 1..100) {
            return event.replyEphemeral("Informe um número entre 1 e 100.")
        }
        if (event.isFromGuild) purgeChannel(event, amount) else clearDm(event, amount)
    }

    /** Server purge: everyone's recent messages in this channel. */
    private fun purgeChannel(event: SlashCommandInteractionEvent, amount: Int) {
        val guild = event.guild ?: return
        val channel = event.channel as? GuildMessageChannel
            ?: return event.replyEphemeral("Não consigo apagar mensagens neste tipo de canal.")

        if (!guild.selfMember.hasPermission(channel, Permission.MESSAGE_MANAGE)) {
            return event.replyEphemeral("Eu não tenho a permissão **Gerenciar Mensagens** neste canal 😔")
        }

        event.deferReply(true).queue()
        channel.history.retrievePast(amount).queue({ messages ->
            // purgeMessages splits bulk vs single deletes internally and drops what Discord
            // refuses. Report the count we handed it and name the 14-day rule, so a partial
            // purge never reads as a silent success.
            channel.purgeMessages(messages)
            log.info("AUDIT limpar caller={} channel={} guild={} n={}", event.user.id, channel.id, guild.id, messages.size)
            event.hook.sendMessage(
                "🧹 Mandei apagar ${messages.size} mensagens. As com mais de 14 dias o Discord " +
                    "não deixa apagar em massa, então essas podem ter ficado.",
            ).setEphemeral(true).queue()
        }, { error ->
            log.error("limpar falhou em channel={}", channel.id, error)
            event.hook.sendMessage("O Discord recusou: ${error.message}").setEphemeral(true).queue()
        })
    }

    /** DM cleanup: only the bot's own messages. */
    private fun clearDm(event: SlashCommandInteractionEvent, amount: Int) {
        val channel = event.channel as? PrivateChannel
            ?: return event.replyEphemeral("Não consigo apagar mensagens neste tipo de canal.")

        event.deferReply(true).queue()
        channel.history.retrievePast(amount).queue({ messages ->
            val mine = messages.filter { it.author.id == event.jda.selfUser.id }
            mine.forEach { it.delete().queue({}, { e -> log.debug("DM delete falhou: {}", e.message) }) }
            event.hook.sendMessage("✅ ${mine.size} mensagens minhas foram apagadas.").setEphemeral(true).queue()
        }, { error ->
            log.error("limpar (DM) falhou", error)
            event.hook.sendMessage("O Discord recusou: ${error.message}").setEphemeral(true).queue()
        })
    }
}
