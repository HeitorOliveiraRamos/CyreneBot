package com.cyrene.discord.listener

import com.cyrene.conversation.UsuarioService
import com.cyrene.discord.command.MemoryCommand
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.stereotype.Component

/**
 * Trata a submissão do modal aberto por `/memoria` ([MemoryCommand]). Salva o texto que o
 * usuário escreveu como sua memória; se o campo voltar vazio, apaga a memória guardada.
 */
@Component
class MemoryModalListener(
    private val usuarioService: UsuarioService,
) : ListenerAdapter() {

    override fun onModalInteraction(event: ModalInteractionEvent) {
        if (event.modalId != MemoryCommand.MODAL_ID) return

        val nome = event.member?.effectiveName ?: event.user.effectiveName
        val texto = event.getValue(MemoryCommand.INPUT_ID)?.asString?.trim().orEmpty()

        if (texto.isEmpty()) {
            usuarioService.limparMemoria(event.user.id, nome)
            event.reply("Tudo bem, $nome. Apaguei o que eu guardava sobre você.")
                .setEphemeral(true).queue()
        } else {
            usuarioService.salvarMemoria(event.user.id, nome, texto)
            event.reply("Pronto, amor. Vou lembrar disso quando a gente conversar.")
                .setEphemeral(true).queue()
        }
    }
}
