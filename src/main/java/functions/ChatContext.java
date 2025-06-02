package functions;

import conexao.ConnectionOllama;
import conexao.Messages;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ChatContext extends ListenerAdapter {

    private final ExecutorService ollamaApiExecutor;

    public ChatContext() {
        this.ollamaApiExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setName("OllamaAPI-ExplainContext-Thread-%d");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getName().equals("contexto-do-canal")) {
            event.deferReply(true).queue();

            event.getChannel().getHistory().retrievePast(50).queue(messages -> {
                final JSONArray conversationHistory = new JSONArray();
                for (Message message : messages.reversed()) {
                    final JSONObject messageEntry = new JSONObject();
                    messageEntry.put("role", "user");
                    messageEntry.put("content", message.getAuthor().getName() + (message.getAuthor().isBot() ? "Bot" : "") + ": " + message.getContentRaw());
                    conversationHistory.put(messageEntry);
                }

                final String userPromptForAI = "Based on the previous message history, provide a summary of the conversation's context in high detail, explaining what author had in it's mind. The summary should highlight the main topics discussed and the participants involved. Never break your personality character.";

                CompletableFuture.supplyAsync(() ->
                                        Messages.sendMessageWithPersonality(userPromptForAI, conversationHistory, null, false),
                                ollamaApiExecutor)
                        .thenAccept(aiResponse -> {
                            if (aiResponse != null && !aiResponse.startsWith(ConnectionOllama.MESSAGE_ERROR)) {
                                event.getHook().sendMessage(aiResponse).queue();
                            } else {
                                String errorMessage = "Desculpe, ocorreu um erro ao tentar resumir o contexto do canal.";
                                if (aiResponse != null) {
                                    System.err.println("ExplainChatContext: Erro da API Ollama: " + aiResponse);
                                }
                                event.getHook().sendMessage(errorMessage).queue();
                            }
                        }).exceptionally(ex -> {
                            System.err.println("ExplainChatContext: Exceção ao processar resumo do contexto: " + ex.getMessage());
                            event.getHook().sendMessage("Desculpe, ocorreu um erro inesperado ao processar sua solicitação de resumo.").queue();
                            return null;
                        });

            }, throwable -> {
                System.err.println("ExplainChatContext: Erro ao recuperar histórico de mensagens: " + throwable.getMessage());
                event.getHook().sendMessage("Não foi possível recuperar o histórico de mensagens para resumir o contexto.").queue();
            });
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
        System.out.println("ExplainChatContext ExecutorService desligado.");
    }
}