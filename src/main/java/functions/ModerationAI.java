package functions;

import conexao.ConnectionOllama;
import conexao.Messages;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ModerationAI extends ListenerAdapter {

    private static final int MAX_WARNINGS = 3;
    private static final Duration TIMEOUT_DURATION = Duration.ofMinutes(15);
    private final Map<String, Integer> userWarnings = new HashMap<>();

    private final ExecutorService ollamaApiExecutor;

    public ModerationAI() {
        this.ollamaApiExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setName("OllamaAPI-Moderation-Thread-%d");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || !event.getChannel().getId().equals("1377827826903941231")) {
            return;
        }

        final String messageContent = event.getMessage().getContentRaw();
        final String userId = event.getAuthor().getId();
        final String userName = event.getAuthor().getName();

        CompletableFuture.supplyAsync(() -> checkMessageWithAI(messageContent), ollamaApiExecutor)
                .thenAccept(isContentInappropriate -> {
                    if (isContentInappropriate) {
                        event.getMessage().delete().queue(
                                success -> event.getChannel().sendMessage(event.getAuthor().getAsMention() + ", sua mensagem foi considerada inadequada e removida.").queue(),
                                error -> System.err.println("ModerationAI: Falha ao excluir mensagem: " + error.getMessage())
                        );

                        final int warnings = userWarnings.compute(userId, (k, v) -> (v == null ? 0 : v) + 1);
                        final boolean isSilenced = warnings >= MAX_WARNINGS;

                        @SuppressWarnings("StringBufferReplaceableByString") final StringBuilder messageBuilder = new StringBuilder();

                        messageBuilder.append(event.getGuild().getName()).append("\n");
                        messageBuilder.append("Notifica que o usuário ").append("***").append(userName).append("***").append(" foi ").append(isSilenced ? "mutado" : "avisado").append(" por conteúdo inadequado.\n\n");
                        messageBuilder.append("**Mensagem que causou o ").append(isSilenced ? "silenciamento" : "aviso").append(":** ").append("```").append(messageContent).append("```").append("\n");
                        messageBuilder.append("**Avisos acumulados:** ").append(warnings).append("/").append(MAX_WARNINGS).append("\n");

                        if (isSilenced) {
                            event.getGuild().timeoutFor(UserSnowflake.fromId(userId), TIMEOUT_DURATION).queue(success -> userWarnings.remove(userId),
                                    error -> {
                                        event.getChannel().sendMessage("Erro ao tentar silenciar " + userName + ": " + error.getMessage()).queue();
                                        System.err.println("ModerationAI: Falha ao tentar silenciar o usuário: " + userId + ": " + error.getMessage());
                                    });
                        }
                        event.getAuthor().openPrivateChannel().queue(
                                privateChannel -> privateChannel.sendMessage(messageBuilder.toString()).queue(),
                                error -> System.err.println("ModerationAI: Falha ao enviar mensagem privada para o usuário: " + userId + ": " + error.getMessage())
                        );
                    }
                }).exceptionally(ex -> {
                    System.err.println("ModerationAI: Exceção ao verificar mensagem com IA para conteúdo \"" + messageContent + "\": " + ex.getMessage());
                    return null;
                });
    }

    private boolean checkMessageWithAI(String messageContent) {
        final String moderationSystemPrompt = """
                You are a strict and objective AI moderator. Your task is to assess whether a user's message violates community guidelines due to extreme content.
                
                Only respond with:
                - 'true' → if the message contains hate speech, threats, extreme harassment, or any other content that is severely inappropriate or harmful.
                - 'false' → for all other cases, including mild rudeness, spam, jokes, or off-topic content.
                
                Do not explain your answer. Respond only with 'true' or 'false'.
                """;


        final String aiResponse = Messages.sendMessageWithPersonality(messageContent.toLowerCase(), null, moderationSystemPrompt, true);

        if (aiResponse.startsWith(ConnectionOllama.MESSAGE_ERROR)) {
            System.err.println("ModerationAI: Erro da API Ollama: " + aiResponse + " para conteúdo: \"" + messageContent + "\"");
            return false;
        }

        if (aiResponse.trim().equalsIgnoreCase("true")) {
            return true;
        } else if (aiResponse.trim().equalsIgnoreCase("false") || aiResponse.trim().contains("Falso")) {
            return false;
        } else {
            System.err.println("ModerationAI: Unexpected response from AI: \"" + aiResponse + "\" for content: \"" + messageContent + "\"");
            return false;
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
        System.out.println("ModerationAI ExecutorService desligado.");
    }
}