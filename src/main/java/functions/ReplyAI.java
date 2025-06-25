package functions;

import conexao.ConnectionOllama;
import conexao.Messages;
import main.Configuration;
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
    private final ChatBot chatBot;

    public ReplyAI(ChatBot chatBot) {
        this.chatBot = chatBot;
        this.ollamaApiExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setName("OllamaAPI-Reply-Thread-%d");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {

        if (!event.getMessage().getChannel().getId().equals(Configuration.TEST_CHANNEL)) {
            return;
        }

        if (event.getAuthor().isBot()) {
            return;
        }

        final String userId = event.getAuthor().getId();
        final String channelId = event.getChannel().getId();

        if (chatBot.isUserInChatSession(userId, channelId)) {
            return;
        }

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
                final String contentWithoutMention = contentRaw.replace("<@" + selfUserId + ">", "").trim();
                if (event.getMessage().getReferencedMessage() != null) {
                    if (event.getMessage().getReferencedMessage().getAuthor().isBot()) {
                        return null;
                    }
                    final String replyContent = event.getMessage().getReferencedMessage().getContentRaw();
                    final String personalityForReply = "The user " + event.getAuthor().getName() + " is going to ask you a question and the content of the question is: " + replyContent + ".";
                    return Messages.sendMessageWithPersonality(contentWithoutMention, null, personalityForReply, false);
                } else {
                    return Messages.sendMessageWithPersonality(contentWithoutMention, null, "The user you are talking to is called: " + event.getAuthor().getName() + ". **Important: You must add his name in your response.**", false);
                }
            }, ollamaApiExecutor).thenAccept(aiResponse -> {
                if (aiResponse != null && !aiResponse.startsWith(ConnectionOllama.MESSAGE_ERROR)) {
                    Messages.sendLongMessageReply(event.getMessage(), aiResponse);
                } else if (aiResponse != null && aiResponse.startsWith(ConnectionOllama.MESSAGE_ERROR)) {
                    System.err.println("ReplyAI: Erro da API Ollama: " + aiResponse);
                    event.getMessage().reply("Desculpe, ocorreu um erro ao processar sua solicitação com a IA.").queue();
                }
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