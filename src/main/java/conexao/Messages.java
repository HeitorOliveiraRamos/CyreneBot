package conexao;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

public class Messages {
    private static final int MAX_MESSAGE_LENGTH = 2000;

    /**
     * Envia uma mensagem para a API Ollama sem histórico.
     *
     * @param userMessageContent O conteúdo da mensagem do usuário.
     * @return A resposta da IA ou uma mensagem de erro.
     */
    public static String sendMessageWithoutHistory(String userMessageContent) {
        return ConnectionOllama.sendMessageToOllama(userMessageContent, null, null);
    }

    /**
     * Envia uma mensagem para a API Ollama com histórico.
     *
     * @param userMessageContent O conteúdo da mensagem do usuário.
     * @param conversationHistory O histórico da conversa.
     * @return A resposta da IA ou uma mensagem de erro.
     */
    public static String sendMessageWithHistory(String userMessageContent, JSONArray conversationHistory) {
        return ConnectionOllama.sendMessageToOllama(userMessageContent, conversationHistory, null);
    }

    /**
     * Envia uma mensagem para a API Ollama com histórico e personalidade.
     *
     * @param userMessageContent O conteúdo da mensagem do usuário.
     * @param conversationHistory O histórico da conversa.
     * @param personality A personalidade da IA.
     * @return A resposta da IA ou uma mensagem de erro.
     */
    public static String sendMessageWithPersonality(String userMessageContent, JSONArray conversationHistory, String personality) {
        return ConnectionOllama.sendMessageToOllama(userMessageContent, conversationHistory, personality);
    }

    /**
     * Envia uma mensagem longa para um canal.
     *
     * @param channel O canal onde a mensagem será enviada.
     * @param message O conteúdo da mensagem.
     */
    public static void sendLongMessage(MessageChannel channel, String message) {
        if (message.length() <= MAX_MESSAGE_LENGTH) {
            channel.sendMessage(message).queue();
        } else {
            final List<String> parts = splitMessage(message);
            for (String part : parts) {
                channel.sendMessage(part).queue();
            }
        }
    }

    /**
     * Envia uma mensagem longa como resposta.
     *
     * @param mensagemEnviada A mensagem original enviada pelo usuário.
     * @param message O conteúdo da resposta.
     */
    public static void sendLongMessageReply(Message mensagemEnviada, String message) {
        if (message.length() <= MAX_MESSAGE_LENGTH) {
            mensagemEnviada.reply(message).queue();
        } else {
            final List<String> parts = splitMessage(message);
            for (String part : parts) {
                mensagemEnviada.reply(part).queue();
            }
        }
    }

    /**
     * Divide uma mensagem longa em partes menores.
     *
     * @param message A mensagem longa.
     * @return Uma lista de partes menores da mensagem.
     */
    private static List<String> splitMessage(String message) {
        final List<String> parts = new ArrayList<>();
        final String ellipsis = "[...]";
        int index = 0;

        while (index < message.length()) {
            int remaining = message.length() - index;
            int maxPartLength = (remaining > MAX_MESSAGE_LENGTH) ? MAX_MESSAGE_LENGTH - ellipsis.length() : MAX_MESSAGE_LENGTH;

            int end = index + maxPartLength;
            if (end < message.length()) {
                int lastSpace = message.lastIndexOf(' ', end);
                if (lastSpace > index) {
                    end = lastSpace;
                }
            } else {
                end = Math.min(end, message.length());
            }

            String part = message.substring(index, end).trim();
            index = end;

            if (index < message.length()) {
                part += ellipsis;
                while (index < message.length() && message.charAt(index) == ' ') {
                    index++;
                }
            }

            parts.add(part);
        }

        return parts;
    }

}