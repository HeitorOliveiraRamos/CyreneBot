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
        final String channelId = message.getChannel().getId();
        final String sessionKey = userId + "-" + channelId;
        final String content = message.getContentRaw();

        final boolean isInCurrentUserSession = this.userChatSessions.getOrDefault(sessionKey, false);
        final JSONArray currentUserAIHistory = this.userAIConversationHistories.computeIfAbsent(sessionKey, k -> new JSONArray());

        if (!isInCurrentUserSession) {
            if (content.equalsIgnoreCase("vamos conversar")) {
                this.userChatSessions.put(sessionKey, true);
                currentUserAIHistory.clear();

                final String openingLine = "Olá! Sobre o que iremos conversar hoje?";
                message.reply(openingLine).queue();

                final JSONObject assistantOpeningMessage = new JSONObject();
                assistantOpeningMessage.put("role", "assistant");
                assistantOpeningMessage.put("content", openingLine);
                currentUserAIHistory.put(assistantOpeningMessage);
            }
        } else {
            if (content.equalsIgnoreCase("encerrar conversa")) {
                this.userChatSessions.put(sessionKey, false);
                message.reply("Até mais!").queue();
                currentUserAIHistory.clear();
            } else {
                final String aiResponse = Messages.sendMessageWithHistory(content, currentUserAIHistory);

                final JSONObject userMessageEntry = new JSONObject();
                userMessageEntry.put("role", "user");
                userMessageEntry.put("content", content);
                currentUserAIHistory.put(userMessageEntry);

                if (!aiResponse.startsWith(ConnectionOllama.MESSAGE_ERROR)) {
                    final JSONObject assistantMessageEntry = new JSONObject();
                    assistantMessageEntry.put("role", "assistant");
                    assistantMessageEntry.put("content", aiResponse);
                    currentUserAIHistory.put(assistantMessageEntry);
                }

                Messages.sendLongMessageReply(message, aiResponse);
            }
        }
    }
}
