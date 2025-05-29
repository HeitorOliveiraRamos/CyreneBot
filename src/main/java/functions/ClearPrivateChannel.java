package functions;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;

public class ClearPrivateChannel extends ListenerAdapter {

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("limpar")) return;

        if (event.getUser().isBot()) return;
        if (event.isFromGuild()) {
            event.reply("Este comando só pode ser usado em conversar privadas!").setEphemeral(true).queue();
            return;
        }

        final OptionMapping amountOption = event.getOption("quantidade");
        final int quantidade = amountOption != null ? amountOption.getAsInt() : 0;

        if (quantidade < 1 || quantidade > 100) {
            event.reply("Informe um número entre 1 e 100.").setEphemeral(true).queue();
            return;
        }

        final PrivateChannel channel = (PrivateChannel) event.getChannel();

        event.deferReply(true).queue();

        channel.getHistory().retrievePast(quantidade).queue(messages -> {
            int deletadas = 0;

            for (Message msg : messages) {
                if (msg.getAuthor().isBot()) {
                    msg.delete().queue();
                    deletadas++;
                }
            }

            event.getHook().sendMessage("✅ " + deletadas + " mensagens do bot foram apagadas.").setEphemeral(true).queue();
        });
    }
}
