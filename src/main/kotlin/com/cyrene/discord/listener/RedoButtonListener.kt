package com.cyrene.discord.listener

import com.cyrene.ai.InferenceGate
import com.cyrene.ai.OllamaAiService
import com.cyrene.ai.VisionService
import com.cyrene.discord.ReplyChainResolver
import com.cyrene.discord.util.BotMessages
import com.cyrene.discord.util.DiscordMessageSender
import com.cyrene.discord.util.MessagePaginator
import com.cyrene.discord.util.ProgressStatus
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.components.buttons.Button
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Handles the 🔄 button under the bot's answers: rebuilds the answer from scratch via
 * [OllamaAiService.redoAnswer] (tool-less, so a redo can never repeat a moderation
 * action), then replaces the old message with a fresh [DiscordMessageSender.replyLong]
 * — which re-handles pagination/splitting, so a redo that grows past one page still
 * renders right. The old answer stays untouched until the new one is ready, so a
 * cancelled or failed redo loses nothing.
 *
 * Context comes from the reply chain of the message that asked (same walk as a mention).
 * Only the user who asked may redo. The asking message is resolved from the answer's raw
 * reply-reference id via REST (button payloads never carry the resolved message — Discord
 * only ships `referenced_message` on MESSAGE_CREATE/UPDATE), so the whole flow runs on the
 * AI executor. No server-side state, so the button keeps working across restarts.
 */
@Component
class RedoButtonListener(
    private val ai: OllamaAiService,
    private val sender: DiscordMessageSender,
    private val replyChainResolver: ReplyChainResolver,
    private val paginator: MessagePaginator,
    private val inferenceGate: InferenceGate,
    private val visionService: VisionService,
    private val executor: Executor,
) : ListenerAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Answer-message ids with a redo in flight, so double clicks don't race a delete. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        if (event.componentId != REDO_ID) return
        val answer = event.message
        // Button payloads never include the resolved reply, only its raw reference id —
        // the actual message is fetched off-thread in [redo]. No id at all means the
        // answer isn't a reply (shouldn't happen) and redo is simply unavailable.
        val askedId = answer.messageReference?.messageId
        if (askedId == null) {
            event.reply(BotMessages.REDO_ORIGINAL_GONE).setEphemeral(true).queue()
            return
        }
        if (!inFlight.add(answer.id)) {
            event.reply(BotMessages.REDO_IN_PROGRESS).setEphemeral(true).queue()
            return
        }
        // Same concurrency ceiling as mentions/sessions — a redo is a full pipeline run.
        if (!inferenceGate.tryAcquire()) {
            inFlight.remove(answer.id)
            event.reply(BotMessages.busy(event.user.effectiveName)).setEphemeral(true).queue()
            return
        }

        event.deferEdit().queue()
        val selfId = event.jda.selfUser.id

        try {
            executor.execute { redo(event, answer, askedId, selfId) }
        } catch (e: Exception) {
            // execute can reject synchronously if the executor is saturated.
            inferenceGate.release()
            inFlight.remove(answer.id)
            log.error("Redo could not submit work for message {}", answer.id, e)
            event.hook.sendMessage(BotMessages.ERROR).setEphemeral(true).queue()
        }
    }

    /**
     * Runs on the AI executor (the REST fetch and the pipeline both block): resolves the
     * asking message, checks ownership, rebuilds the answer and swaps it in. The gate
     * permit taken in [onButtonInteraction] is released here exactly once.
     */
    private fun redo(event: ButtonInteractionEvent, answer: Message, askedId: String, selfId: String) {
        var progress: ProgressStatus? = null
        try {
            val asked = answer.referencedMessage
                ?: runCatching { answer.channel.retrieveMessageById(askedId).complete() }.getOrNull()
            if (asked == null) {
                event.hook.sendMessage(BotMessages.REDO_ORIGINAL_GONE).setEphemeral(true).queue()
                return
            }
            if (event.user.id != asked.author.id) {
                event.hook.sendMessage(BotMessages.NOT_YOUR_BUTTON).setEphemeral(true).queue()
                return
            }

            // Same live status line (with its cancel button) as a fresh mention.
            val status = ProgressStatus(asked)
            progress = status
            status(BotMessages.STATUS_REDO)
            val chain = replyChainResolver.resolveChain(asked, selfId)
            val content = VisionService.augmentContent(
                asked.contentRaw.replace("<@$selfId>", "").trim(),
                visionService.describeFirstImage(asked),
            )
            val history = MentionReplyListener.historyFromChain(chain, asked.author.effectiveName, content)
            val reply = ai.redoAnswer(history, previousAnswerText(answer), asked.author.effectiveName, status)
            if (!status.isCancelled) {
                sender.replyLong(asked, reply)
                runCatching { answer.delete().queue() }
            }
        } catch (e: CancellationException) {
            // Cancelled via the status button: keep the old answer, the status line says so.
        } catch (e: Exception) {
            log.error("Redo failed for message {}", answer.id, e)
            event.hook.sendMessage(BotMessages.ERROR).setEphemeral(true).queue()
        } finally {
            progress?.close()
            inferenceGate.release()
            inFlight.remove(answer.id)
        }
    }

    /** A paginated answer's visible content is one page; the redo must rethink the full text. */
    private fun previousAnswerText(answer: Message): String =
        if (MessagePaginator.PAGE_FOOTER.containsMatchIn(answer.contentRaw)) {
            paginator.fullTextByMessageId(answer.id) ?: answer.contentRaw
        } else {
            answer.contentRaw
        }

    companion object {
        const val REDO_ID = "redo"

        /** The 🔄 button [DiscordMessageSender] attaches to redo-able answers. */
        fun redoButton(): Button = Button.secondary(REDO_ID, "🔄")
    }
}
