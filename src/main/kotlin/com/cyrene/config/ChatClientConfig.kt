package com.cyrene.config

import com.cyrene.discord.tools.DiscordTools
import com.cyrene.knowledge.GameKnowledgeTools
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Builds a [ChatClient] with all `@Tool`-annotated methods on [DiscordTools] and
 * [GameKnowledgeTools] pre-registered as default callbacks. This is the entry point used
 * for tool-aware chats (mentions and `/iniciar-conversa` sessions), driven by the brain
 * pass in [com.cyrene.ai.OllamaAiService]. The legacy single-pass [OllamaAiService.chat]
 * path used by `/contexto-do-canal` calls the raw [OllamaChatModel] directly to avoid tool
 * overhead.
 */
@Configuration
class ChatClientConfig {

    @Bean
    fun discordToolCallbacks(
        discordTools: DiscordTools,
        knowledgeTools: GameKnowledgeTools,
    ): MethodToolCallbackProvider =
        MethodToolCallbackProvider.builder()
            .toolObjects(discordTools, knowledgeTools)
            .build()

    @Bean
    fun chatClient(
        chatModel: OllamaChatModel,
        toolCallbacks: MethodToolCallbackProvider,
    ): ChatClient = ChatClient.builder(chatModel)
        .defaultToolCallbacks(toolCallbacks)
        .build()
}
