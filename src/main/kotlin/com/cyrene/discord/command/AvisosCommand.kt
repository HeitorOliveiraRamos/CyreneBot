package com.cyrene.discord.command

import com.cyrene.discord.util.BotMessages
import com.cyrene.moderation.Aviso
import com.cyrene.moderation.AvisoRepository
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter

/**
 * `/avisos membro:` — the history behind [AvisarCommand]'s running count.
 *
 * Replies ephemerally: pulling up someone's record is a moderator's business, and posting it
 * publicly turns a lookup into a callout.
 *
 * Requires **Moderar Membros**, enforced by Discord (see [ModerationGuards.modOnly]).
 */
@Component
class AvisosCommand(
    private val avisos: AvisoRepository,
) : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "avisos"

    override val definition: CommandData =
        Commands.slash(name, "Mostra o histórico de advertências de um membro")
            .setDefaultPermissions(ModerationGuards.modOnly(Permission.MODERATE_MEMBERS))
            .setGuildOnly(true)
            .addOption(OptionType.USER, "membro", "De quem", true)

    override fun handle(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.replyEphemeral(BotMessages.GUILD_ONLY)
        val target = event.getOption("membro")!!.asUser

        event.deferReply(true).queue()
        val historico = try {
            avisos.findByGuildIdAndUsuarioIdOrderByCriadoEmDesc(guild.id, target.id)
        } catch (e: Exception) {
            log.error("avisos falhou ao ler target={} guild={}", target.id, guild.id, e)
            event.hook.sendMessage("Não consegui buscar o histórico agora 😔").setEphemeral(true).queue()
            return
        }

        if (historico.isEmpty()) {
            event.hook.sendMessage("${target.effectiveName} não tem nenhum aviso aqui — ficha limpa ✨")
                .setEphemeral(true).queue()
            return
        }

        event.hook.sendMessage(render(target.effectiveName, historico)).setEphemeral(true).queue()
    }

    /**
     * Newest first, capped at [MAX_SHOWN] entries so a long record can't blow past Discord's
     * 2000-character limit — the total is stated up front, so a truncated list never reads as
     * the whole story.
     */
    private fun render(nome: String, historico: List<Aviso>): String = buildString {
        appendLine("## Avisos de $nome — ${historico.size} no total")
        historico.take(MAX_SHOWN).forEach {
            appendLine("**${it.criadoEm.format(DATE)}** por <@${it.moderadorId}>")
            appendLine("> ${it.motivo.take(300)}")
        }
        if (historico.size > MAX_SHOWN) {
            append("-# Mostrando os ${MAX_SHOWN} mais recentes.")
        }
    }.trim()

    private companion object {
        const val MAX_SHOWN = 10
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    }
}
