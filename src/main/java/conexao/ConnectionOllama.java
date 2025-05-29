package conexao;

import main.Configuration;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ConnectionOllama {

    public static final String MESSAGE_ERROR = "Erro";

    private static HttpURLConnection getConnection(String jsonPayload) throws Exception {
        final URL url = new URI(Configuration.BASE_URL).toURL();
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }
        return conn;
    }

    private static String performHttpRequest(String jsonPayload) {
        final StringBuilder response = new StringBuilder();
        HttpURLConnection conn = null;
        try {
            conn = getConnection(jsonPayload);
            int responseCode = conn.getResponseCode();

            BufferedReader br;
            if (responseCode >= 200 && responseCode < 300) {
                br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            } else {
                br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
            }

            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            br.close();

            if (responseCode >= 300) {
                return MESSAGE_ERROR + ": HTTP " + responseCode + " - " + response;
            }

        } catch (Exception e) {
            String errorMessage = MESSAGE_ERROR + ": " + e.getMessage();
            if (conn != null) {
                try {
                    int responseCode = conn.getResponseCode();
                    errorMessage = MESSAGE_ERROR + ": HTTP " + responseCode + " - " + e.getMessage();
                } catch (IOException ignored) {
                }
            }
            return errorMessage;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return response.toString();
    }

    public static String parseResponse(String jsonResponse) {
        try {
            final JSONObject responseObject = new JSONObject(jsonResponse);
            if (responseObject.has("message")) {
                final JSONObject messageObject = responseObject.getJSONObject("message");
                if (messageObject.has("content")) {
                    return messageObject.getString("content");
                }
            }
            if (responseObject.has("error")) {
                return MESSAGE_ERROR + ": " + responseObject.getString("error");
            }
            System.err.println("ConnectionOllama: Formato de resposta inesperado da API: " + jsonResponse);
            return MESSAGE_ERROR + ": resposta inesperada da API.";
        } catch (Exception e) {
            return MESSAGE_ERROR + ": ao processar a resposta: " + e.getMessage();
        }
    }

    /**
     * Envia uma mensagem para a API Ollama, construindo o payload necessário.
     *
     * @param userMessageContent      O conteúdo da mensagem do usuário.
     * @param baseConversationHistory O histórico da conversa (JSONArray contendo objetos de mensagem de 'user' e 'assistant'), pode ser nulo.
     * @param systemPersonality       A mensagem de sistema (personalidade), pode ser nula ou vazia.
     * @return O conteúdo da resposta da IA ou uma mensagem de erro prefixada com MESSAGE_ERROR.
     */
    public static String sendMessageToOllama(String userMessageContent, JSONArray baseConversationHistory, String systemPersonality) {
        final JSONArray messagesForPayload = new JSONArray();

        String effectiveSystemPersonality = null;

        if (Configuration.PERSONALITY != null && !Configuration.PERSONALITY.isEmpty()) {
            effectiveSystemPersonality = Configuration.PERSONALITY;
        }

        if (systemPersonality != null && !systemPersonality.isEmpty()) {
            if (effectiveSystemPersonality != null) {
                effectiveSystemPersonality = effectiveSystemPersonality + "\n" + systemPersonality;
            } else {
                effectiveSystemPersonality = systemPersonality;
            }
        }

        if (effectiveSystemPersonality != null) {
            final JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", effectiveSystemPersonality);
            messagesForPayload.put(systemMsg);
        }

        if (baseConversationHistory != null) {
            for (int i = 0; i < baseConversationHistory.length(); i++) {
                messagesForPayload.put(baseConversationHistory.getJSONObject(i));
            }
        }

        final JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessageContent);
        messagesForPayload.put(userMsg);

        final JSONObject payload = new JSONObject();
        payload.put("model", Configuration.MODEL);
        payload.put("messages", messagesForPayload);
        payload.put("stream", false);

        final String jsonPayload = payload.toString();
        final String rawResponse = performHttpRequest(jsonPayload);

        if (rawResponse.startsWith(MESSAGE_ERROR)) {
            return rawResponse;
        }

        return parseResponse(rawResponse);
    }
}