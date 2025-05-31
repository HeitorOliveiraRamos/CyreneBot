package functions;

import conexao.ConnectionOllama; // Importar para usar MESSAGE_ERROR
import conexao.Messages;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ReplyAI extends ListenerAdapter {

    private final Map<String, Long> userCooldowns = new HashMap<>();
    private static final long COOLDOWN_DURATION_SECONDS = 5;
    private final ExecutorService ollamaApiExecutor;

    public ReplyAI() {
        this.ollamaApiExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setName("OllamaAPI-Reply-Thread-%d");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }

        final String userId = event.getAuthor().getId();
        final long currentTime = System.currentTimeMillis();

        if (event.getMessage().getMentions().getUsers().contains(event.getJDA().getSelfUser())) {
            final long lastRequestTime = userCooldowns.getOrDefault(userId, 0L);
            final long timeSinceLastRequest = TimeUnit.MILLISECONDS.toSeconds(currentTime - lastRequestTime);

            if (timeSinceLastRequest < COOLDOWN_DURATION_SECONDS) {
                event.getMessage().reply("Você precisa esperar " + (COOLDOWN_DURATION_SECONDS - timeSinceLastRequest) + " segundos antes de fazer outra pergunta.").queue();
                return;
            }

            userCooldowns.put(userId, currentTime);

            final String contentRaw = event.getMessage().getContentRaw();
            final String selfUserId = event.getJDA().getSelfUser().getId();

            CompletableFuture.supplyAsync(() -> {
                if (event.getMessage().getReferencedMessage() != null) {
                    if (event.getMessage().getReferencedMessage().getAuthor().isBot()) {
                        return null; // Não processar se a mensagem referenciada for de um bot
                    }
                    final String contentWithoutMention = contentRaw.replace("<@" + selfUserId + ">", "").trim();
                    final String replyContent = event.getMessage().getReferencedMessage().getContentRaw();
                    final String personalityForReply = "This user is going to ask you a question and the content of the question is: " + replyContent + ".";
                    // Usar o método que permite ignorar a personalidade padrão, passando 'false' para não ignorar
                    return Messages.sendMessageWithPersonality(contentWithoutMention, null, personalityForReply, false);
                } else {
                    final String userMessageContent = contentRaw.replace("<@" + selfUserId + ">", "").trim();
                    return Messages.sendMessageWithoutHistory(userMessageContent);
                }
            }, ollamaApiExecutor).thenAccept(aiResponse -> {
                if (aiResponse != null && !aiResponse.startsWith(ConnectionOllama.MESSAGE_ERROR)) {
                    Messages.sendLongMessageReply(event.getMessage(), aiResponse);
                } else if (aiResponse != null && aiResponse.startsWith(ConnectionOllama.MESSAGE_ERROR)) {
                    System.err.println("ReplyAI: Erro da API Ollama: " + aiResponse);
                    event.getMessage().reply("Desculpe, ocorreu um erro ao processar sua solicitação com a IA.").queue();
                }
                // Se aiResponse for null (caso da mensagem referenciada ser de um bot), não faz nada.
            }).exceptionally(ex -> {
                System.err.println("ReplyAI: Exceção ao processar mensagem com IA: " + ex.getMessage());
                event.getMessage().reply("Desculpe, ocorreu um erro inesperado ao processar sua solicitação.").queue();
                return null;
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
        System.out.println("ReplyAI ExecutorService desligado.");
    }
}