package functions;

import conexao.ConnectionOllama;
import conexao.Messages;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ChatBot extends ListenerAdapter {
    private final Map<String, Boolean> userChatSessions = new HashMap<>();
    private final Map<String, JSONArray> userAIConversationHistories = new HashMap<>();
    private final ExecutorService ollamaApiExecutor;

    public ChatBot() {
        this.ollamaApiExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setName("OllamaAPI-ChatBot-Thread-%d");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        final String userId = event.getUser().getId();
        final String channelId = event.getChannel().getId();
        final String sessionKey = userId + "-" + channelId;

        if (event.getName().equals("iniciar-conversa")) {
            if (userChatSessions.getOrDefault(sessionKey, false)) {
                event.reply("Você já está em uma sessão de conversa neste canal. Use `/encerrar-conversa` para terminar a sessão atual primeiro.").setEphemeral(true).queue();
                return;
            }

            userChatSessions.put(sessionKey, true);
            JSONArray currentUserAIHistory = userAIConversationHistories.computeIfAbsent(sessionKey, k -> new JSONArray());
            synchronized (currentUserAIHistory) {
                currentUserAIHistory.clear();
            }

            final String openingLine = "Olá! Sobre o que iremos conversar hoje?";
            event.reply(openingLine).queue();

            final JSONObject assistantOpeningMessage = new JSONObject();
            assistantOpeningMessage.put("role", "assistant");
            assistantOpeningMessage.put("content", openingLine);
            synchronized (currentUserAIHistory) {
                currentUserAIHistory.put(assistantOpeningMessage);
            }

        } else if (event.getName().equals("encerrar-conversa")) {
            if (!userChatSessions.getOrDefault(sessionKey, false)) {
                event.reply("Você não está em uma sessão de conversa ativa neste canal.").setEphemeral(true).queue();
                return;
            }

            userChatSessions.put(sessionKey, false);
            JSONArray currentUserAIHistory = userAIConversationHistories.get(sessionKey);
            if (currentUserAIHistory != null) {
                synchronized (currentUserAIHistory) {
                    currentUserAIHistory.clear();
                }
            }
            event.reply("Até mais!").queue();
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }

        final Message message = event.getMessage();
        final String userId = message.getAuthor().getId();
        final String channelId = message.getChannel().getId();
        final String sessionKey = userId + "-" + channelId;
        final String content = message.getContentRaw();

        final boolean isInCurrentUserSession = this.userChatSessions.getOrDefault(sessionKey, false);

        if (isInCurrentUserSession) {
            final JSONArray currentUserAIHistory = this.userAIConversationHistories.get(sessionKey);
            if (currentUserAIHistory == null) {
                System.err.println("ChatBot: History is null for an active sessionKey: " + sessionKey);
                this.userChatSessions.put(sessionKey, false);
                message.reply("Ocorreu um erro com sua sessão de chat. Por favor, inicie uma nova conversa.").queue();
                return;
            }


            final JSONObject userMessageEntry = new JSONObject();
            userMessageEntry.put("role", "user");
            userMessageEntry.put("content", content);
            synchronized (currentUserAIHistory) {
                currentUserAIHistory.put(userMessageEntry);
            }

            final JSONArray historySnapshot;
            synchronized (currentUserAIHistory) {
                historySnapshot = new JSONArray(currentUserAIHistory.toString());
            }

            CompletableFuture.supplyAsync(() -> Messages.sendMessageWithHistory(content, historySnapshot), ollamaApiExecutor)
                    .thenAccept(aiResponse -> {
                        if (aiResponse != null && !aiResponse.startsWith(ConnectionOllama.MESSAGE_ERROR)) {
                            final JSONObject assistantMessageEntry = new JSONObject();
                            assistantMessageEntry.put("role", "assistant");
                            assistantMessageEntry.put("content", aiResponse);
                            if (this.userChatSessions.getOrDefault(sessionKey, false)) {
                                synchronized (currentUserAIHistory) {
                                    if (this.userAIConversationHistories.containsKey(sessionKey) && this.userChatSessions.getOrDefault(sessionKey, false)) {
                                        this.userAIConversationHistories.get(sessionKey).put(assistantMessageEntry);
                                    }
                                }
                            }
                        }
                        if (aiResponse != null) {
                            Messages.sendLongMessageReply(message, aiResponse);
                        } else {
                            message.reply("Desculpe, não consegui gerar uma resposta para isso.").queue();
                        }
                    }).exceptionally(ex -> {
                        System.err.println("ChatBot: Exceção ao processar mensagem com IA: " + ex.getMessage());
                        message.reply("Desculpe, ocorreu um erro inesperado ao tentar obter uma resposta.").queue();
                        return null;
                    });
        }
    }

    public boolean isUserInChatSession(String userId, String channelId) {
        String sessionKey = userId + "-" + channelId;
        return userChatSessions.getOrDefault(sessionKey, false);
    }

    public void shutdownExecutor() {
        ollamaApiExecutor.shutdown();
        try {
            if (!ollamaApiExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ollamaApiExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ollamaApiExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("ChatBot ExecutorService desligado.");
    }
}