package functions;

import conexao.ConnectionOllama;
import conexao.Messages;
import net.dv8tion.jda.api.entities.Message;
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
        // Acessar ou criar o histórico de forma segura para threads, se necessário,
        // mas como cada sessão de usuário tem seu próprio histórico,
        // e as operações no JSONArray são feitas dentro do CompletableFuture,
        // o acesso direto aqui para computeIfAbsent deve ser seguro.
        final JSONArray currentUserAIHistory = this.userAIConversationHistories.computeIfAbsent(sessionKey, k -> new JSONArray());

        if (!isInCurrentUserSession) {
            if (content.equalsIgnoreCase("vamos conversar")) {
                this.userChatSessions.put(sessionKey, true);
                // Limpar o histórico de forma segura
                synchronized (currentUserAIHistory) {
                    currentUserAIHistory.clear();
                }

                final String openingLine = "Olá! Sobre o que iremos conversar hoje?";
                message.reply(openingLine).queue();

                final JSONObject assistantOpeningMessage = new JSONObject();
                assistantOpeningMessage.put("role", "assistant");
                assistantOpeningMessage.put("content", openingLine);
                // Adicionar ao histórico de forma segura
                synchronized (currentUserAIHistory) {
                    currentUserAIHistory.put(assistantOpeningMessage);
                }
            }
        } else {
            if (content.equalsIgnoreCase("encerrar conversa")) {
                this.userChatSessions.put(sessionKey, false);
                message.reply("Até mais!").queue();
                // Limpar o histórico de forma segura
                synchronized (currentUserAIHistory) {
                    currentUserAIHistory.clear();
                }
            } else {
                // Adiciona a mensagem do usuário ao histórico antes de chamar a IA
                final JSONObject userMessageEntry = new JSONObject();
                userMessageEntry.put("role", "user");
                userMessageEntry.put("content", content);
                synchronized (currentUserAIHistory) {
                    currentUserAIHistory.put(userMessageEntry);
                }

                // Cria uma cópia do histórico para passar para a thread da API
                // para evitar ConcurrentModificationException se o histórico for modificado
                // enquanto a API está processando.
                final JSONArray historySnapshot;
                synchronized (currentUserAIHistory) {
                    historySnapshot = new JSONArray(currentUserAIHistory.toString()); // Cria uma cópia profunda
                }

                CompletableFuture.supplyAsync(() -> Messages.sendMessageWithHistory(content, historySnapshot), ollamaApiExecutor)
                        .thenAccept(aiResponse -> {
                            if (!aiResponse.startsWith(ConnectionOllama.MESSAGE_ERROR)) {
                                final JSONObject assistantMessageEntry = new JSONObject();
                                assistantMessageEntry.put("role", "assistant");
                                assistantMessageEntry.put("content", aiResponse);
                                // Adicionar ao histórico original de forma segura
                                synchronized (currentUserAIHistory) {
                                    // Verifica se a sessão ainda está ativa antes de adicionar a resposta da IA
                                    // Isso evita adicionar ao histórico se o usuário encerrou a conversa
                                    // enquanto a IA processava.
                                    if (this.userChatSessions.getOrDefault(sessionKey, false)) {
                                        currentUserAIHistory.put(assistantMessageEntry);
                                    }
                                }
                            }
                            // Envia a resposta independentemente de erro, pois Messages.sendLongMessageReply lida com isso
                            Messages.sendLongMessageReply(message, aiResponse);
                        }).exceptionally(ex -> {
                            System.err.println("ChatBot: Exceção ao processar mensagem com IA: " + ex.getMessage());
                            message.reply("Desculpe, ocorreu um erro inesperado ao tentar obter uma resposta.").queue();
                            return null;
                        });
            }
        }
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