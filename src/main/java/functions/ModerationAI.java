package functions;

import conexao.Messages;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class ModerationAI extends ListenerAdapter {

    private static final int MAX_WARNINGS = 3;
    private static final Duration TIMEOUT_DURATION = Duration.ofMinutes(10);
    private static final String MODERATION_SYSTEM_PROMPT = "You are a villain – a truly wicked character, an enemy, someone who spreads chaos. However, you have limits. Read the user's message and determine if it exceeds those limits. You don’t respond to lengthy text or spam; you’ll only respond with something other than 'false' when the message is exceptionally disturbing. Respond with 'true' only when the user's statement goes far beyond any reasonable limit. Otherwise, respond with ‘false’. Just say ‘true’ or ‘false’, nothing more.";
    private final Map<String, Integer> userWarnings = new HashMap<>();

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }

        final String messageContent = event.getMessage().getContentRaw();
        final String userId = event.getAuthor().getId();
        final String userName = event.getAuthor().getName();

        if (checkMessageWithAI(messageContent)) {
            event.getMessage().delete().queue(success -> event.getChannel().sendMessage(event.getAuthor().getAsMention() + ", sua mensagem foi considerada inadequada e removida.").queue(), error -> System.err.println("ModerationAI: Falha ao excluir mensagem: " + error.getMessage()));

            final int warnings = userWarnings.getOrDefault(userId, 0) + 1;
            userWarnings.put(userId, warnings);

            final boolean isSilenced = warnings >= MAX_WARNINGS;

            final StringBuilder messageBuilder = new StringBuilder();

            messageBuilder.append(event.getGuild().getName()).append("\n");
            messageBuilder.append("Notifica que o usuário ").append("***").append(userName).append("***").append(" foi ").append(isSilenced ? "mutado" : "avisado").append(" por conteúdo inadequado.\n\n");
            messageBuilder.append("**Mensagem que causou o ").append(isSilenced ? "silenciamento" : "aviso").append(":** ").append("```").append(messageContent).append("```").append("\n");
            messageBuilder.append("**Avisos acumulados:** ").append(warnings).append("/").append(MAX_WARNINGS).append("\n");

            if (isSilenced) {
                try {
                    event.getGuild().timeoutFor(UserSnowflake.fromId(userId), TIMEOUT_DURATION).queue(
                   success -> userWarnings.remove(userId), error -> {
                        event.getChannel().sendMessage("Erro ao tentar silenciar " + userName + ": " + error.getMessage()).queue();
                        System.err.println("ModerationAI: Falha ao tentar silenciar o usuário: " + userId + ": " + error.getMessage());
                    });
                } catch (Exception e) {
                    event.getChannel().sendMessage("Erro ao tentar silenciar " + userName + ": " + e.getMessage()).queue();
                    System.err.println("ModerationAI: Falha ao tentar silenciar o usuário: " + userId + ": " + e.getMessage());
                }
            }

            event.getAuthor().openPrivateChannel().queue(privateChannel -> privateChannel.sendMessage(messageBuilder.toString()).queue(), error -> System.err.println("ModerationAI: Falha ao enviar mensagem privada para o usuário: " + userId + ": " + error.getMessage()));
        }
    }

    private boolean checkMessageWithAI(String messageContent) {
        final JSONArray tempConversationHistory = new JSONArray();

        final JSONObject systemMessage = new JSONObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", MODERATION_SYSTEM_PROMPT);
        tempConversationHistory.put(systemMessage);

        final String aiResponse = Messages.sendMessageWithHistory(messageContent.toLowerCase(), tempConversationHistory);

        if (aiResponse.trim().equalsIgnoreCase("true")) {
            return true;
        } else if (aiResponse.trim().equalsIgnoreCase("false")) {
            return false;
        } else {
            System.err.println("ModerationAI: Unexpected response from AI: \"" + aiResponse + "\" for content: \"" + messageContent + "\"");
            return false;
        }
    }
}