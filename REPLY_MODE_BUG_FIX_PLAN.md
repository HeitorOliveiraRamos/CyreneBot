Plan to fix "reply-to-bot starts mini chat" bug

Checklist
- [ ] Reproduce the bug locally with a concrete scenario and logs.
- [ ] Add minimal debugging logs to verify code paths (temporary).
- [ ] Update `MentionReplyListener` so replies-to-Cyrene (without an @ mention) are handled.
- [ ] Consider small change to `buildHistory` if replies-to-self need explicit assistant context when chain resolution fails.
- [ ] Add unit/integration test(s) to cover: mention-based ping, reply-without-mention to a bot message, and multi-hop reply chains.
- [ ] Run app locally and verify interactions in a test guild/channel.
- [ ] Create PR with changes and tests; include rationale and manual test steps.

Reproduction steps (what I would do locally)
1. Start the bot locally (or run in a dev/test environment) and point it to a test guild and channel configured in `application.yml` (`bot.testChannelId`).
2. User A: send `@Cyrene como você está?` (mention) — expect a normal bot reply.
3. User A: reply to the bot's reply message using Discord's "Reply" action, but do NOT include `@Cyrene` in the reply message content.
4. Expectation: bot should respond in that reply thread and preserve the prior turn(s) context. Current bug: bot does not react.
5. Tail logs and add debug prints (or increase log level) to observe whether `MentionReplyListener.onMessageReceived` is executed for the reply event.

Quick diagnosis (reading the code)
- The main listener is `src/main/kotlin/com/cyrene/discord/listener/MentionReplyListener.kt`.
- The listener currently early-returns unless the bot is explicitly mentioned in the incoming message:
  - Line around `if (selfUser !in event.message.mentions.users) return` — this will ignore plain reply messages that do not @-mention the bot.
- The listener does later access `event.message.referencedMessage` and even resolves a reply chain via `ReplyChainResolver.resolveChain(...)`, but that code never runs if the mention guard short-circuits.
- `ReplyChainResolver` correctly walks referenced messages via `messageReference` and `referencedMessage` and will reconstruct assistant / user turns if called.
- `DiscordMessageSender.replyLong(...)` sends replies and caches bot messages in `BotReplyCache`, which `ReplyChainResolver` uses as a tier 2 lookup. The caching looks correct.

Root-cause hypothesis
- The listener requires the incoming message to explicitly mention the bot. If a user replies to the bot's earlier message using the reply UI but does not re-mention the bot, `event.message.mentions.users` will not contain the bot and the listener returns early. That prevents the chain resolver and AI invocation from running.
- This matches the behavior described: "pinging the bot and then the user replies to the message the bot sent" — the expected behavior is that replying to the bot message should trigger a mini-chat even if the reply doesn't @-mention the bot, but current code ignores it.

Minimal fix (code-level, do later)
1) Change the mention guard in `MentionReplyListener.onMessageReceived` to also allow processing when the message is a reply to Cyrene's own message. Specifically, replace the current check:

    val selfUser = event.jda.selfUser
    if (selfUser !in event.message.mentions.users) return

with something like (Kotlin):

    val selfUser = event.jda.selfUser
    val referenced = event.message.referencedMessage
    val isMention = selfUser in event.message.mentions.users
    val isReplyToSelf = referenced?.author?.id == selfUser.id
    if (!isMention && !isReplyToSelf) return

Notes:
- This keeps the existing behavior for mentions unchanged and adds the reply-to-self case.
- The listener already has a later check that ignores replies to *other* bots:

    if (referenced != null && referenced.author.isBot && referenced.author.id != selfUser.id) { return }

which remains valid.

2) (Optional) Consider ensuring `buildHistory` captures assistant content when the user replies to Cyrene but `ReplyChainResolver` returned an empty chain due to cache/REST failures. Right now `buildHistory`'s fallback only includes human referenced messages (`if (referenced != null && !referenced.author.isBot) ...`). If you want to preserve the assistant's previous message as context when chain resolution fails, either:
- Pass `selfUser.id` into `buildHistory` so it can include a referenced assistant entry when `referenced.author.id == selfUser.id`, or
- In the caller (in `onMessageReceived`) detect `referenced?.author?.id == selfUser.id` and, if the chain is empty, pre-seed `history` with a ConversationMessage representing the assistant's previous content (using `referenced.contentRaw`).

Files to inspect/test
- `src/main/kotlin/com/cyrene/discord/listener/MentionReplyListener.kt` (primary change)
- `src/main/kotlin/com/cyrene/discord/ReplyChainResolver.kt` (verify chain walk logic)
- `src/main/kotlin/com/cyrene/discord/util/BotReplyCache.kt` (verify cache put/get semantics)
- `src/main/kotlin/com/cyrene/discord/util/DiscordMessageSender.kt` (ensure replyLong queues bot messages and cache callback)
- Any integration tests or local harness that simulates `MessageReceivedEvent` (none present; consider adding tests)

Suggested tests
- Unit test for `MentionReplyListener`:
  - Mock a `MessageReceivedEvent` that is a reply to a message authored by the bot (referencedMessage.author.id == selfUser.id) and where `mentions.users` does NOT contain the bot — assert the listener proceeds to call the AI and `sender.replyLong`.
  - Mock the reply where the referenced author is a different bot — assert listener returns early.
  - Mock a normal mention (existing behavior) — assert unchanged.
- Integration/manual test using a test guild/channel: run the bot and perform the reproduction steps above.

Debugging tips (temporary)
- Add debug logging near the early-return mention guard to record: event id, whether bot is mentioned, referenced message id and referenced author id, and whether the listener will proceed.
- Add a debug log inside `ReplyChainResolver.resolveChain` to show how many hops were collected and whether the chain was empty.

Verification checklist after fix
- [ ] Replying to the bot's message (without re-mentioning) triggers a bot response.
- [ ] The reply includes the previous assistant message(s) as history (multi-hop up to configured `maxHops`).
- [ ] Replies to other bots remain ignored.
- [ ] No regressions for mention-based interactions.
- [ ] Unit tests pass, and integration manual tests succeed in a test channel.

Patch sketch (for later implementation)
- File: `src/main/kotlin/com/cyrene/discord/listener/MentionReplyListener.kt`
- Replace the mention guard with the `isMention || isReplyToSelf` logic shown in the "Minimal fix" section.

PR notes
- Title: "Handle reply-to-bot messages when user doesn't re-mention — preserve reply-chain context"
- Summary: allow users to reply to the bot's message to continue a mini-chat without requiring an @-mention; keep existing behavior for mentions and replies to other bots; add tests and temporary logs.
- Risk: low — small change to listener gating; add tests to prove behavior.

If you want, I can implement the change + tests and run the project's build/tests locally. This first message only created a plan file in the workspace as requested (no code changes were made).
