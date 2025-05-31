// Em src/main/java/main/Main.java
package main;

import functions.ChatBot;
import functions.ClearPrivateChannel;
import functions.ModerationAI;
import functions.ReplyAI;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class Main extends ListenerAdapter {
    public static void main(String[] args) {
        final JDABuilder builder = JDABuilder.createDefault(Configuration.TOKEN, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.GUILD_MEMBERS);

        final ModerationAI moderationAIListener = new ModerationAI();
        final ReplyAI replyAIListener = new ReplyAI();
        final ChatBot chatBotListener = new ChatBot();

        builder.addEventListeners(chatBotListener);
        builder.addEventListeners(replyAIListener);
        builder.addEventListeners(moderationAIListener);
        builder.addEventListeners(new ClearPrivateChannel());

        final JDA jda = builder.build();
        jda.updateCommands().addCommands(Commands.slash("limpar", "Limpa as mensagens do bot no canal privado").addOption(OptionType.INTEGER, "quantidade", "Quantidade de mensagens a excluir", true)).queue();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Desligando o bot...");
            moderationAIListener.shutdownExecutor();
            replyAIListener.shutdownExecutor();
            chatBotListener.shutdownExecutor();
            jda.shutdown();
            try {
                jda.awaitShutdown();
            } catch (InterruptedException e) {
                System.err.println("Interrompido durante o desligamento do JDA.");
                Thread.currentThread().interrupt();
            }
            System.out.println("Bot desligado.");
        }));
    }
}