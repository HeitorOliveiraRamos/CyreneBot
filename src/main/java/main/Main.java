package main;

import functions.*;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class Main extends ListenerAdapter {
    public static void main(String[] args) throws InterruptedException {
        final JDABuilder builder = JDABuilder.createDefault(Configuration.TOKEN, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.GUILD_MEMBERS);

        final ModerationAI moderationAIListener = new ModerationAI();
        final ChatBot chatBotListener = new ChatBot();
        final ReplyAI replyAIListener = new ReplyAI(chatBotListener);
        final ChatContext explainChatContextListener = new ChatContext();

        builder.addEventListeners(chatBotListener);
        builder.addEventListeners(replyAIListener);
        builder.addEventListeners(explainChatContextListener);
        builder.addEventListeners(moderationAIListener);
        builder.addEventListeners(new ClearPrivateChannel());

        final JDA jda = builder.build();

        jda.awaitReady();

        jda.updateCommands().addCommands(
                Commands.slash("limpar", "Limpa as mensagens do bot no canal privado").addOption(OptionType.INTEGER, "quantidade", "Quantidade de mensagens a excluir", true),
                Commands.slash("iniciar-conversa", "Inicia uma sessão de chat com o bot."),
                Commands.slash("encerrar-conversa", "Encerra a sessão de chat atual com o bot."),
                Commands.slash("contexto-do-canal", "Verifica o contexto atual do canal que você está")
        ).queue();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            moderationAIListener.shutdownExecutor();
            replyAIListener.shutdownExecutor();
            chatBotListener.shutdownExecutor();
            explainChatContextListener.shutdownExecutor();
            jda.shutdown();
            try {
                jda.awaitShutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("Bot desligado.");
        }));
    }
}