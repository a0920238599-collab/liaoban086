package com.realtek.chat.ai

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.realtek.chat.storage.AppDb
import com.realtek.chat.storage.Contact
import com.realtek.chat.storage.GroupMessage
import com.realtek.chat.storage.ProfileStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

class GroupChatEngine(context: Context) {
    private val appContext =
        context.applicationContext

    private val db =
        AppDb.get(appContext)

    private val gateway =
        RunApiGateway(appContext)

    private val personaEvolution =
        PersonaEvolutionEngine(
            appContext
        )

    private val behaviorController =
        SocialBehaviorController(
            appContext
        )

    private val profile =
        ProfileStore(appContext)

    private val episodeMutex =
        Mutex()

    suspend fun sendUserText(
        groupId: Int,
        text: String,
        onChanged: () -> Unit = {},
        onTyping: (String?) -> Unit = {}
    ) {
        val clean = text.trim()
        if (clean.isBlank()) return

        db.addGroupMessage(
            groupId = groupId,
            senderKind = "user",
            senderContactId = null,
            senderName =
                profile.nickname
                    .ifBlank {
                        "我"
                    },
            type = "text",
            text = clean
        )

        onChanged()

        runCatching {
            personaEvolution
                .maybeLearnBeforeGroupReply(
                    groupId = groupId,
                    latestUserText = clean
                )
        }

        // 同一个群同一时刻只保留一条群聊行为链。
        // 新用户消息直接进入数据库，由正在运行的行为链读取最新上下文；
        // 不再为每条用户消息排队一个新的 episode，避免重复判断 API 调用。
        if (
            episodeMutex.tryLock()
        ) {
            try {
                runGroupEpisode(
                    groupId = groupId,
                    onChanged = onChanged,
                    onTyping = onTyping
                )
            } finally {
                episodeMutex.unlock()
            }
        }
    }

    suspend fun processPersonaEvolution(
        groupId: Int
    ) = coroutineScope {
        db.getGroupMembers(groupId)
            .map {
                contact ->
                async {
                    runCatching {
                        personaEvolution
                            .learnFromGroupChat(
                                groupId,
                                contact
                            )
                    }
                }
            }
            .awaitAll()
    }

private suspend fun runGroupEpisode(
    groupId: Int,
    onChanged: () -> Unit,
    onTyping: (String?) -> Unit
) {
    var safetyIterations = 0
    val safetyLimit = 30

    // 某个联系人模型在本次 episode 中明确失败后，暂时排除，
    // 避免系统判断模型不断选中同一个坏 model ID 造成“API一直调但没人说话”。
    val failedContactIds =
        mutableSetOf<Int>()

    var lastGenerationError:
        Throwable? = null

    var sentAnyInEpisode =
        false

    while (
        safetyIterations <
            safetyLimit
    ) {
        val latest =
            db.getLastGroupMessage(
                groupId
            )
                ?: return

        val allMembers =
            db.getGroupMembers(
                groupId
            )

        if (
            allMembers.size < 2
        ) {
            return
        }

        val members =
            allMembers.filterNot {
                it.id in
                    failedContactIds
            }

        if (
            members.isEmpty()
        ) {
            throw IllegalStateException(
                "群聊里的联系人模型都未能返回可用消息。请检查各联系人的模型 ID 是否能通过当前 API 路线调用。",
                lastGenerationError
            )
        }

        val recent =
            db.getRecentGroupMessages(
                groupId,
                20
            )

        val aiTurnsSinceUser =
            recent.asReversed()
                .takeWhile {
                    it.senderKind ==
                        "contact"
                }
                .size

        // 每一步只调用一次统一系统判断模型。
        val decision =
            behaviorController
                .decideGroupNextSpeaker(
                    groupId =
                        groupId,
                    members =
                        members,
                    aiTurnsSinceUser =
                        aiTurnsSinceUser
                )

        if (
            !decision.speak ||
            decision.speakerContactId ==
                null
        ) {
            // 如果系统模型判断期间用户又发了新消息，
            // 这个 stop 已经过期，直接基于最新消息重新判断。
            val nowLast =
                db.getLastGroupMessage(
                    groupId
                )

            if (
                nowLast != null &&
                nowLast.id !=
                    latest.id
            ) {
                safetyIterations += 1
                continue
            }

            if (
                !sentAnyInEpisode &&
                lastGenerationError != null
            ) {
                throw IllegalStateException(
                    "群聊判断模型已经运行，但被选中的联系人模型没有成功生成消息。请检查该联系人的模型 ID。",
                    lastGenerationError
                )
            }

            onTyping(null)
            return
        }

        val speaker =
            members.firstOrNull {
                it.id ==
                    decision.speakerContactId
            }

        if (
            speaker == null
        ) {
            safetyIterations += 1
            continue
        }

        delay(
            delayFor(
                decision
            )
        )

        val beforeGenerate =
            db.getLastGroupMessage(
                groupId
            )
                ?: return

        if (
            beforeGenerate.id !=
                latest.id
        ) {
            // 等待期间出现新消息，只重新判断，不额外排队 episode。
            safetyIterations += 1
            continue
        }

        onTyping(
            speaker.name
        )

        val replyResult =
            runCatching {
                generateForMember(
                    groupId =
                        groupId,
                    contact =
                        speaker,
                    decision =
                        decision
                )
            }

        onTyping(null)

        val reply =
            replyResult
                .getOrNull()
                ?.trim()
                .orEmpty()

        if (
            reply.isBlank()
        ) {
            lastGenerationError =
                replyResult.exceptionOrNull()
                    ?: IllegalStateException(
                        "${speaker.name} 的模型返回了空消息"
                    )

            failedContactIds +=
                speaker.id

            safetyIterations += 1
            continue
        }

        db.addGroupMessage(
            groupId =
                groupId,
            senderKind =
                "contact",
            senderContactId =
                speaker.id,
            senderName =
                speaker.name,
            type = "text",
            text =
                reply.take(
                    1200
                )
        )

        sentAnyInEpisode =
            true

        onChanged()
        safetyIterations += 1
    }

    onTyping(null)
}

    private suspend fun generateForMember(
        groupId: Int,
        contact: Contact,
        decision: GroupSystemDecision
    ): String? {
        val members =
            db.getGroupMembers(
                groupId
            )

        val recent =
            db.getRecentGroupMessages(
                groupId,
                20
            )

        val roster =
            buildRoster(
                members
            )

        val transcript =
            buildTranscript(
                recent
            )

        val adaptive =
            personaEvolution
                .effectiveRules(
                    contact.id
                )

        val text =
            gateway.chat(
                provider =
                    contact.provider,
                model =
                    contact.model,
                systemPrompt = """
                    你是群聊成员“${contact.name}”。

                    【群成员身份表】
                    $roster

                    【严格身份规则】
                    - [USER:user|...] 是用户本人。
                    - [CONTACT:${contact.id}|${contact.name}] 是你自己。
                    - 其他 [CONTACT:id|name] 是其他独立联系人。
                    - A说的话只能算A说的，B说的话只能算B说的。
                    - 不要把多个成员的话合并成“用户说的”。
                    - 不要把群聊消息写成私聊上下文。
                    - 只以“${contact.name}”自己的身份发言。

                    【初始人格】
                    ${contact.corePersona}

                    【初始说话方式】
                    ${contact.styleRules}

                    【相处过程中学到的动态规则，优先于初始表达习惯】
                    $adaptive

                    初始人物卡中“每次必须、固定几条、固定结尾表情、每次追问”等动作不能机械执行；
                    用户后来的明确反馈和动态相处规则优先。

                    ${SocialConstitution.runtimeRules}

                    【本次行为决策】
                    mode=${decision.mode}
                    target=${decision.targetLabel}
                    direction=${decision.direction}

                    这是群聊，不要求所有话都围着用户。
                    你可以自然回应另一个联系人，
                    也可以对用户说话。

                    只完成本次 direction 指定的这一件事。
                    不要为了让群聊继续而额外制造新的问题、新话题或新的钩子。
                    如果 mode 不是 question，就不要习惯性在结尾再抛一个问题。
                    下一位是否继续说，会由独立行为判断层重新决定。

                    只发“${contact.name}”这一条消息本身。
                    不要在正文前写名字或 speaker ID。
                """.trimIndent(),
                userPrompt = """
                    最近群聊：
                    $transcript

                    现在按上面的行为决策自然说这一条。
                """.trimIndent(),
                maxTokens = 700
            ).trim()

        if (
            text.isBlank()
        ) {
            error(
                "${contact.name} 的模型返回了空消息"
            )
        }

        return text
    }

    private fun buildRoster(
        members: List<Contact>
    ): String {
        val contacts =
            members.joinToString("\n") {
                "[CONTACT:${it.id}|${it.name}]"
            }

        return """
            [USER:user|${profile.nickname.ifBlank { "我" }}]
            $contacts
        """.trimIndent()
    }

    private fun buildTranscript(
        messages: List<GroupMessage>
    ): String =
        messages.joinToString("\n") {
            "${speakerLabel(it)}：${it.text}"
        }
            .ifBlank {
                "暂无"
            }

    private fun speakerLabel(
        message: GroupMessage
    ): String =
        if (
            message.senderKind ==
                "user"
        ) {
            "[USER:user|${message.senderName}]"
        } else {
            val stableId =
                message.senderKey
                    .removePrefix(
                        "contact:"
                    )

            "[CONTACT:$stableId|${message.senderName}]"
        }

    private fun delayFor(
        decision: GroupSystemDecision
    ): Long {
        return when (
            decision.delayClass
        ) {
            "quick" -> 650L
            "thoughtful" -> 2_300L
            else -> 1_250L
        }
    }

    private fun parseJson(
        raw: String
    ): JsonObject? {
        val clean =
            raw.trim()
                .removePrefix(
                    "```json"
                )
                .removePrefix(
                    "```JSON"
                )
                .removePrefix(
                    "```"
                )
                .removeSuffix(
                    "```"
                )
                .trim()

        runCatching {
            JsonParser.parseString(
                clean
            ).asJsonObject
        }.getOrNull()?.let {
            return it
        }

        val start =
            clean.indexOf('{')

        val end =
            clean.lastIndexOf('}')

        if (
            start >= 0 &&
            end > start
        ) {
            return runCatching {
                JsonParser.parseString(
                    clean.substring(
                        start,
                        end + 1
                    )
                ).asJsonObject
            }.getOrNull()
        }

        return null
    }
}
