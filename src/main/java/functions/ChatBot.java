package functions;

import conexao.Messages;
import main.Configuration;
import main.Util;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class ChatBot extends ListenerAdapter {
    private final Map<String, Boolean> userChatSessions = new HashMap<>();
    private final Map<String, JSONArray> userAIConversationHistories = new HashMap<>();

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }

        final Message message = event.getMessage();
        final String userId = message.getAuthor().getId();
        final String content = message.getContentRaw();

        final boolean isInCurrentUserSession = this.userChatSessions.getOrDefault(userId, false);
        final JSONArray currentUserAIHistory = this.userAIConversationHistories.computeIfAbsent(userId, k -> new JSONArray());

        if (!isInCurrentUserSession) {
            if (content.equalsIgnoreCase("vamos conversar")) {
                this.userChatSessions.put(userId, true);
                currentUserAIHistory.clear();

                if (Configuration.PERSONALITY != null && !Configuration.PERSONALITY.isEmpty()) {
                    final JSONObject systemMessage = new JSONObject();
                    systemMessage.put("role", "system");
                    systemMessage.put("content", Configuration.PERSONALITY);
                    currentUserAIHistory.put(systemMessage);
                }
                message.reply("Olá! Sobre o que iremos conversar hoje?").queue();
            }
        } else {
            if (content.equalsIgnoreCase("encerrar conversa")) {
                this.userChatSessions.put(userId, false);
                message.reply("Até mais!").queue();
                currentUserAIHistory.clear();
            } else {
                final String aiResponse = Messages.sendMessageWithHistory(content, currentUserAIHistory);

                Messages.sendLongMessageReply(message, aiResponse);
            }
        }
    }
}