package functions;

import conexao.Messages;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ReplyAI extends ListenerAdapter {

    private final Map<String, Long> userCooldowns = new HashMap<>();
    private static final long COOLDOWN_DURATION_SECONDS = 5;

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
                return;
            }

            userCooldowns.put(userId, currentTime);

            if (event.getMessage().getReferencedMessage() != null) {
                if (event.getMessage().getReferencedMessage().getAuthor().isBot()) {
                    return;
                }

                final String contentWithoutMention = event.getMessage().getContentRaw().replace("<@" + event.getJDA().getSelfUser().getId() + ">", "").trim();
                final String replyContent = event.getMessage().getReferencedMessage().getContentRaw();
                final String personalityForReply = "This user is going to ask you a question and the content of the question is: " + replyContent + ".";

                final String aiResponse = Messages.sendMessageWithPersonality(contentWithoutMention, null, personalityForReply);
                Messages.sendLongMessageReply(event.getMessage(), aiResponse);

            } else {
                final String userMessageContent = event.getMessage().getContentRaw().replace("<@" + event.getJDA().getSelfUser().getId() + ">", "").trim();
                final String aiResponse = Messages.sendMessageWithoutHistory(userMessageContent);
                Messages.sendLongMessageReply(event.getMessage(), aiResponse);
            }
        }
    }
}