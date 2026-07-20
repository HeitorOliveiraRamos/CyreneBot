package com.cyrene.discord.command

import com.cyrene.discord.util.BotMessages
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `/criar-canal nome: [tipo:] [categoria:]` — creates a text or voice channel.
 *
 * Requires **Gerenciar Canais**, enforced by Discord (see [ModerationGuards.modOnly]).
 */
@Component
class CriarCanalCommand : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "criar-canal"

    override val definition: CommandData =
        Commands.slash(name, "Cria um canal novo no servidor")
            .setDefaultPermissions(ModerationGuards.modOnly(Permission.MANAGE_CHANNEL))
            .setGuildOnly(true)
            .addOption(OptionType.STRING, "nome", "Nome do canal", true)
            .addOptions(
                OptionData(OptionType.STRING, "tipo", "Texto ou voz (padrão: texto)", false)
                    .addChoice("texto", TYPE_TEXT)
                    .addChoice("voz", TYPE_VOICE),
                OptionData(OptionType.CHANNEL, "categoria", "Categoria onde criar", false)
                    .setChannelTypes(ChannelType.CATEGORY),
            )

    override fun handle(event: SlashCommandInteractionEvent) {
        val guild = event.guild ?: return event.replyEphemeral(BotMessages.GUILD_ONLY)
        val rawName = event.getOption("nome")!!.asString.trim()
        val type = event.getOption("tipo")?.asString ?: TYPE_TEXT
        val category = event.getOption("categoria")?.asChannel?.asCategory()

        if (rawName.isEmpty() || rawName.length > 100) {
            return event.replyEphemeral("O nome do canal precisa ter entre 1 e 100 caracteres.")
        }
        if (!guild.selfMember.hasPermission(Permission.MANAGE_CHANNEL)) {
            return event.replyEphemeral("Eu não tenho a permissão **Gerenciar Canais** neste servidor 😔")
        }

        event.deferReply().queue()
        val action = if (type == TYPE_VOICE) guild.createVoiceChannel(rawName) else guild.createTextChannel(rawName)
        category?.let { action.setParent(it) }

        action.reason("Criado por ${event.user.name}").queue(
            { channel ->
                log.info("AUDIT criar-canal caller={} channel={} guild={}", event.user.id, channel.id, guild.id)
                event.hook.sendMessage("Canal ${channel.asMention} criado ✨").queue()
            },
            { error ->
                log.error("criar-canal falhou em guild={}", guild.id, error)
                event.hook.sendMessage("O Discord recusou: ${error.message}").setEphemeral(true).queue()
            },
        )
    }

    private companion object {
        const val TYPE_TEXT = "texto"
        const val TYPE_VOICE = "voz"
    }
}
