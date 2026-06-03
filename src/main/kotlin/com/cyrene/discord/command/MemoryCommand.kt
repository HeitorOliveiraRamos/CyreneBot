package com.cyrene.discord.command

import com.cyrene.conversation.UserProfileService
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.springframework.stereotype.Component

/**
 * Lets a user opt into (or out of) the bot remembering things about them. Memory is OFF by
 * default for privacy; this is the only way to turn it on. Turning it off wipes everything
 * the bot has stored about the user (summary + remembered facts).
 */
@Component
class MemoryCommand(
    private val userProfileService: UserProfileService,
) : SlashCommand {

    override val name = "memoria"

    override val definition: CommandData =
        Commands.slash(name, "Controla se eu posso lembrar de coisas sobre você entre conversas")
            .addOption(
                OptionType.BOOLEAN,
                "ativar",
                "true para eu começar a lembrar de você; false para eu esquecer tudo",
                true,
            )

    override fun handle(event: SlashCommandInteractionEvent) {
        if (event.user.isBot) return
        val enable = event.getOption("ativar")?.asBoolean ?: return
        val userId = event.user.id

        if (enable) {
            userProfileService.enableMemory(userId)
            event.reply(
                "Pronto, amor. A partir de agora eu vou guardar o que for importante sobre você " +
                    "para lembrar nas nossas próximas conversas. Use `/memoria ativar:false` quando " +
                    "quiser que eu esqueça tudo.",
            ).setEphemeral(true).queue()
        } else {
            userProfileService.disableMemory(userId)
            event.reply(
                "Tudo bem. Apaguei tudo o que eu lembrava sobre você e não vou guardar mais nada.",
            ).setEphemeral(true).queue()
        }
    }
}
