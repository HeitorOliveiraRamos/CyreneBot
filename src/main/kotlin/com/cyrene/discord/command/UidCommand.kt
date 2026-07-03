package com.cyrene.discord.command

import com.cyrene.conversation.UsuarioService
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.springframework.stereotype.Component

/**
 * `/uid [uid]` — links the caller's HSR UID to their Discord account (stored on the
 * `usuario` row), so `/build` can fetch their showcase without asking every time.
 * Without the option it just shows the currently linked UID. All replies ephemeral —
 * a UID is the user's business.
 */
@Component
class UidCommand(
    private val usuarioService: UsuarioService,
) : SlashCommand {

    override val name = "uid"

    override val definition: CommandData =
        Commands.slash(name, "Vincula seu UID de Honkai: Star Rail (usado pelo /build)")
            .addOption(OptionType.STRING, "uid", "Seu UID no jogo (9 a 10 dígitos). Vazio = mostrar o atual.", false)

    override fun handle(event: SlashCommandInteractionEvent) {
        val uid = event.getOption("uid")?.asString?.trim()

        if (uid.isNullOrEmpty()) {
            val current = usuarioService.uidDe(event.user.id)
            val msg = if (current == null) {
                "Você ainda não vinculou um UID. Use `/uid uid:<seu UID>` — ele fica no seu perfil do jogo. 💜"
            } else {
                "Seu UID vinculado é **$current**. Para trocar, use `/uid uid:<novo UID>`."
            }
            event.reply(msg).setEphemeral(true).queue()
            return
        }

        if (!UID_PATTERN.matches(uid)) {
            event.reply("Hmm, isso não parece um UID válido — são 9 ou 10 dígitos, sem letras. Confere no seu perfil do jogo?")
                .setEphemeral(true).queue()
            return
        }

        usuarioService.salvarUid(event.user.id, event.user.effectiveName, uid)
        event.reply(
            "Prontinho, guardei seu UID **$uid** 💜 Agora é só usar `/build personagem:<nome>` — " +
                "lembra que a personagem precisa estar na sua vitrine (showcase) do jogo, com o perfil público.",
        ).setEphemeral(true).queue()
    }

    internal companion object {
        /** HSR UIDs are 9-10 digit numbers not starting with 0. */
        internal val UID_PATTERN = Regex("^[1-9][0-9]{8,9}$")
    }
}
