package com.cyrene.discord.command

import com.cyrene.conversation.UsuarioService
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.components.text.TextInput
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle
import net.dv8tion.jda.api.interactions.modals.Modal
import org.springframework.stereotype.Component

/**
 * Abre um modal onde o usuário escreve, com as próprias palavras, o que quer que o bot lembre
 * dele entre as conversas. O campo vem pré-preenchido com a memória atual (se houver), então o
 * mesmo comando serve para definir, editar e — enviando o campo vazio — apagar.
 *
 * A submissão do modal é tratada em [com.cyrene.discord.listener.MemoryModalListener].
 */
@Component
class MemoryCommand(
    private val usuarioService: UsuarioService,
) : SlashCommand {

    override val name = "memoria"

    override val definition: CommandData =
        Commands.slash(name, "Escolha o que eu vou lembrar sobre você entre as conversas")

    override fun handle(event: SlashCommandInteractionEvent) {
        if (event.user.isBot) return

        val atual = usuarioService.memoriaDe(event.user.id)

        val campo = TextInput.create(INPUT_ID, "O que você quer que eu lembre de você?", TextInputStyle.PARAGRAPH)
            .setPlaceholder("Ex.: meu nome é Léo, sou alérgico a amendoim, gosto de Honkai...")
            .setRequired(false)
            .setMaxLength(MAX_LENGTH)
            .apply { atual?.takeIf { it.isNotBlank() }?.let { setValue(it) } }
            .build()

        val modal = Modal.create(MODAL_ID, "Memória")
            .addComponents(net.dv8tion.jda.api.interactions.components.ActionRow.of(campo))
            .build()

        event.replyModal(modal).queue()
    }

    companion object {
        const val MODAL_ID = "memoria-modal"
        const val INPUT_ID = "memoria-texto"
        const val MAX_LENGTH = 1000
    }
}
