package main;

import functions.ChatBot;
import functions.ClearPrivateChannel;
import functions.ModerationAI;
import functions.ReplyAI;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class Main extends ListenerAdapter {
    public static void main(String[] args) {
        final JDABuilder builder = JDABuilder.createDefault(Configuration.TOKEN, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.GUILD_MEMBERS);

        builder.addEventListeners(new ChatBot());
        builder.addEventListeners(new ReplyAI());
//        builder.addEventListeners(new ModerationAI()); // Comentado pois a moderação não está muito boa ainda
        builder.addEventListeners(new ClearPrivateChannel());

        builder.build().updateCommands().addCommands(Commands.slash("limpar", "Limpa as mensagens do bot no canal privado").addOption(OptionType.INTEGER, "quantidade", "Quantidade de mensagens a excluir", true)).queue();
    }
}
