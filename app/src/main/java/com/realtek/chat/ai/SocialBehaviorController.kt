package com.realtek.chat.ai

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.realtek.chat.settings.AppSettings
import com.realtek.chat.storage.AppDb
import com.realtek.chat.storage.Contact
import kotlin.math.max
import kotlin.math.min

data class SocialActionDecision(
    val continueSpeaking: Boolean,
    val mode: String,
    val direction: String,
    val question: Boolean,
    val delayClass: String,
    val momentum: String
)

data class ConversationPressure(
    val score: Double,
    val summary: String
)

data class GroupSystemDecision(
    val speak: Boolean,
    val speakerContactId: Int?,
    val mode: String,
    val direction: String,
    val targetLabel: String,
    val delayClass: String
)

class SocialBehaviorController(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDb.get(appContext)
    private val settings = AppSettings(appContext)
    private val gateway = RunApiGateway(appContext)
    private val socialEngine = SocialEngine(appContext)
    private val personaEvolution =
        PersonaEvolutionEngine(
            appContext
        )

    fun shouldConsiderContinuation(
        userText: String
    ): Boolean {
        val clean = userText.trim()

        if (isExplicitContinueRequest(clean)) {
            return true
        }

        if (isClosingSignal(clean)) {
            return false
        }

        // 文字聊天允许消息交叉。
        // 用户可能继续发送，不构成自动停止理由。
        return true
    }

    suspend fun decideNextExtraMessage(
        contact: Contact,
        originalUserText: String,
        explicitContinue: Boolean
    ): SocialActionDecision {
        val empty = SocialActionDecision(
            continueSpeaking = false,
            mode = "stop",
            direction = "",
            question = false,
            delayClass = "normal",
            momentum = "closed"
        )

        if (
            !settings.decisionConfigured()
        ) {
            return empty
        }

        val pressure = conversationPressure(
            contact.id
        )

        val recent = db.getRecentMessages(
            contact.id,
            12
        )

        val transcript = recent.joinToString("\n") {
            "${if (it.role == "user") "用户" else contact.name}：" +
                if (it.type == "image") "[图片]" else it.text
        }

        val state = socialEngine.snapshot(
            contact.id
        )

        val adaptivePersona =
            personaEvolution
                .effectiveRules(
                    contact.id
                )

        val raw = runCatching {
            gateway.chat(
                provider =
                    settings.decisionProvider,
                model =
                    settings.decisionModel,
                systemPrompt = """
                    你是 Realtek 的统一社交行为判断模型。
                    你不负责写“${contact.name}”最终要发送的聊天内容，
                    只负责判断这个联系人现在是否还应该继续说，以及下一条属于什么行为。

                    你现在只判断：联系人刚发完一条以后，是否值得“再单独发一条”。

                    联系人初始核心性格：
                    ${contact.corePersona}

                    联系人初始说话方式：
                    ${contact.styleRules}

                    与当前用户相处后形成的动态规则：
                    $adaptivePersona

                    动态规则优先于初始表达习惯。
                    初始人物卡中的“固定几条、每次必须、固定加表情、总要追问”
                    不能作为机械行为规则。

                    最重要的规则：
                    1. “还有话可说”不等于“现在还会再单独发一条”。判断的是现实聊天中的残余表达冲动。
                    2. 只有最新上下文真正产生了一个新的、具体的反应点，才 continue=true。
                       例如：第二反应、必要补充、刚想到的联想、自然调侃、观点推进、确实想问的一件事。
                    3. 如果下一条只是换个说法、解释刚才已经完整表达的内容、礼貌补一句、为了不断线而找话题，continue=false。
                    4. 一条消息已经把意思说完整时，默认允许它自然落地。不要因为“这个话题还能展开”就继续。
                    5. 连续发送越多，越要区分“我还能说”与“我真的会再按一次发送键”。
                       这只是自然疲劳，不是固定条数上限。
                    6. 如果上一条已经抛出了一个明显等用户回应的问题，通常应让这句话落地；
                       除非此刻确实又冒出一个独立的补充，不是因为“用户可能还在输入”而停。
                    7. conversation pressure 是软信号：短而有内容的连发可以自然；
                       但连续内容开始稀薄、重复、解释过度时，momentum 应从 active → fading → closed。
                    8. social_drive、人物性格和关系只影响倾向，不能成为一直说下去的理由。
                    9. 不要为了显得克制而过早结束，也不要为了保持热度而无限续。
                    10. 一个很实用的判断：如果只是“可以再说一句”，选 stop；
                        只有“此刻真的还想再发这一句”，才 continue。
                """.trimIndent(),
                userPrompt = """
                    联系人：${contact.name}

                    最初触发当前表达的用户消息：
                    $originalUserText

                    注意：之后用户可能又发了新消息。
                    “最近聊天”才是当前最权威上下文；不要因为用户继续发消息就自动停止。

                    当前联系人已经连续表达过若干消息。
                    不要按“这是第几条”决定停不停，只判断此刻聊天动量。

                    用户是否明确要求继续说：
                    $explicitContinue

                    当前连续聊天状态：
                    mood=${state.mood}
                    topic=${state.currentTopic}
                    social_drive=${"%.2f".format(state.socialDrive)}

                    双方关系直接根据人物设定、动态人格和最近聊天理解，
                    不根据创建时间或消息数量自动改变。

                    对话压力：
                    score=${"%.2f".format(pressure.score)}
                    ${pressure.summary}

                    最近聊天：
                    $transcript

                    最近联系人自我话题：
                    ${socialEngine.recentTopicSummary(contact.id)}

                    只输出 JSON：
                    {
                      "continue": true,
                      "momentum": "active",
                      "mode": "afterthought",
                      "direction": "刚才那句话之后又自然冒出的具体补充",
                      "question": false,
                      "delay_class": "normal"
                    }

                    mode 只能是：
                    reaction
                    afterthought
                    clarification
                    self_share
                    followup_question
                    topic_extension

                    momentum 只能是：
                    active
                    fading
                    closed

                    delay_class 只能是：
                    quick
                    normal
                    thoughtful

                    momentum=active：此刻存在一个具体、自然、会真的再次发送的表达冲动。
                    momentum=fading：话题仍可展开，但更像“还能说”而不是“真想再发”；应停止。
                    momentum=closed：这轮表达已经自然落地；应停止。

                    continue=true 只应和 momentum=active 一起出现。
                    fading / closed 必须 continue=false。
                """.trimIndent(),
                maxTokens = 360
            )
        }.getOrNull() ?: return empty

        val obj = parseJson(raw) ?: return empty

        val decision = SocialActionDecision(
            continueSpeaking =
                obj["continue"]
                    ?.takeIf {
                        it.isJsonPrimitive
                    }
                    ?.asBoolean
                    ?: false,
            mode =
                obj["mode"]
                    ?.takeIf {
                        it.isJsonPrimitive
                    }
                    ?.asString
                    ?.trim()
                    ?.takeIf {
                        it in setOf(
                            "reaction",
                            "afterthought",
                            "clarification",
                            "self_share",
                            "followup_question",
                            "topic_extension"
                        )
                    }
                    ?: "afterthought",
            direction =
                obj["direction"]
                    ?.takeIf {
                        it.isJsonPrimitive
                    }
                    ?.asString
                    ?.trim()
                    .orEmpty()
                    .take(220),
            question =
                obj["question"]
                    ?.takeIf {
                        it.isJsonPrimitive
                    }
                    ?.asBoolean
                    ?: false,
            delayClass =
                obj["delay_class"]
                    ?.takeIf {
                        it.isJsonPrimitive
                    }
                    ?.asString
                    ?.trim()
                    ?.takeIf {
                        it in setOf(
                            "quick",
                            "normal",
                            "thoughtful"
                        )
                    }
                    ?: "normal",
            momentum =
                obj["momentum"]
                    ?.takeIf {
                        it.isJsonPrimitive
                    }
                    ?.asString
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf {
                        it in setOf(
                            "active",
                            "fading",
                            "closed"
                        )
                    }
                    ?: "fading"
        )

        // 不按第几条设置硬上限，但系统判断必须明确认为当前仍有“真实续发冲动”。
        // 这样避免 v8.5 那种只要话题还能展开就一直继续。
        return if (
            decision.continueSpeaking &&
            decision.momentum ==
                "active" &&
            decision.direction
                .isNotBlank()
        ) {
            decision
        } else {
            empty
        }
    }

suspend fun decideGroupNextSpeaker(
    groupId: Int,
    members: List<Contact>,
    aiTurnsSinceUser: Int
): GroupSystemDecision {
    val stop =
        GroupSystemDecision(
            speak = false,
            speakerContactId = null,
            mode = "wait",
            direction = "",
            targetLabel = "",
            delayClass = "normal"
        )

    if (
        members.isEmpty() ||
        !settings.decisionConfigured()
    ) {
        return stop
    }

    val recent =
        db.getRecentGroupMessages(
            groupId,
            20
        )

    if (
        recent.isEmpty()
    ) {
        return stop
    }

    val roster =
        members.joinToString(
            "\n\n"
        ) {
            contact ->
            val adaptive =
                personaEvolution
                    .effectiveRules(
                        contact.id
                    )
                    .take(
                        700
                    )

            val state =
                socialEngine.snapshot(
                    contact.id
                )

            """
            [CONTACT:${contact.id}|${contact.name}]
            初始人格：${contact.corePersona.take(600)}
            初始表达：${contact.styleRules.take(450)}
            动态相处规则：$adaptive
            social_drive=${"%.2f".format(state.socialDrive)}
            """.trimIndent()
        }

    val transcript =
        recent.joinToString(
            "\n"
        ) {
            message ->
            val speaker =
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

            "$speaker：${message.text}"
        }

    val raw =
        runCatching {
            gateway.chat(
                provider =
                    settings.decisionProvider,
                model =
                    settings.decisionModel,
                systemPrompt = """
                    你是 Realtek 群聊唯一的社交行为判断模型。
                    每一步只由你判断：
                    1. 群聊现在应该停止，还是应该有人继续说；
                    2. 如果继续，只选择一个最自然的联系人；
                    3. 指定他的行为类型、回应方向、目标对象和等待类型。

                    你不负责写最终聊天台词。
                    被选中的联系人之后会使用他自己的聊天模型生成真正内容。

                    【身份规则】
                    USER:user 是用户本人。
                    每个 CONTACT:id 都是独立成员。
                    A说的话不能算成B说的，也不能算成用户说的。
                    不要把不同联系人合并成一个人。

                    【群成员】
                    $roster

                    判断原则：
                    - 先判断当前这轮群聊是 active / fading / closed，再决定是否有人继续说。
                    - 不要求所有联系人都回应用户，也绝对不要为了公平而轮流每个人说一句。
                    - A和B可以自然来回很多轮，C也可以一直不说；但“可以聊很久”不等于“必须一直聊”。
                    - 每一次继续都必须由最新一条消息产生一个具体的新触发：
                      反驳、补充、追问、玩笑、误解澄清、观点推进、明显的情绪反应等。
                    - 纯附和、换句话说、重复解释、礼貌收尾、为了不冷场找话题，都不构成新的触发，应 stop。
                    - 一轮里 AI 已经连续说了较多消息时，把它视为“自然疲劳”的软信号：
                      不是固定上限，但后续必须有更清楚的因果触发，而不是只因为话题还能继续。
                    - 如果最后一条已经像一个自然落点、笑点落下、意见说完、问题抛给用户，允许群聊安静下来。
                    - 不因为“用户可能正在输入”而 stop；停止只能因为这轮内容本身已经落地。
                    - 不要为了热闹强行制造内容，也不要为了克制而过早停掉真正正在发展的争论/玩笑。
                    - 人物动态规则优先于初始表达习惯。
                """.trimIndent(),
                userPrompt = """
                    最近群聊：
                    $transcript

                    自从用户最后一条消息后，
                    已连续出现 AI 消息：
                    $aiTurnsSinceUser 条

                    这个数字不是硬上限，但它是自然疲劳的软信号。
                    连续越久，越不能仅凭“还有话题可聊”继续，必须有最新消息带来的具体新触发。

                    只输出 JSON。

                    如果继续：
                    {
                      "action": "speak",
                      "momentum": "active",
                      "speaker_id": 3,
                      "speaker_name": "B",
                      "mode": "reply",
                      "direction": "最新一句话具体触发了B的什么新反应",
                      "target": "[CONTACT:7|B]",
                      "delay_class": "normal"
                    }

                    如果该停：
                    {
                      "action": "stop",
                      "momentum": "fading",
                      "speaker_id": null,
                      "speaker_name": null,
                      "mode": "wait",
                      "direction": "",
                      "target": "",
                      "delay_class": "normal"
                    }

                    momentum 只能是：
                    active
                    fading
                    closed

                    只有 momentum=active 才能 action=speak。
                    fading 表示“还能聊，但没有新的自然发送冲动”，此时应 action=stop。
                    closed 表示这轮已经明显落地，也应 action=stop。

                    speaker_id 必须是群成员 CONTACT id。
                    mode 可以是：
                    reaction
                    reply
                    clarify
                    self_share
                    topic_extend
                    question
                    wait

                    delay_class 只能是：
                    quick
                    normal
                    thoughtful
                """.trimIndent(),
                maxTokens = 420
            )
        }.getOrElse {
            throw IllegalStateException(
                "系统行为判断模型调用失败：${it.message ?: "未知错误"}",
                it
            )
        }

    val obj =
        parseJson(
            raw
        ) ?: throw IllegalStateException(
            "系统行为判断模型已经返回内容，但不是可解析的 JSON。请换一个更适合结构化输出的判断模型。"
        )

    val action =
        obj["action"]
            ?.takeIf {
                it.isJsonPrimitive
            }
            ?.asString
            ?.trim()
            ?.lowercase()
            .orEmpty()

    val momentum =
        obj["momentum"]
            ?.takeIf {
                it.isJsonPrimitive
            }
            ?.asString
            ?.trim()
            ?.lowercase()
            ?.takeIf {
                it in setOf(
                    "active",
                    "fading",
                    "closed"
                )
            }
            ?: "fading"

    if (
        action == "stop" ||
        momentum != "active"
    ) {
        return stop
    }

    if (
        action.isNotBlank() &&
        action != "speak"
    ) {
        throw IllegalStateException(
            "系统行为判断模型返回了未知 action：$action"
        )
    }

    val idFromJson =
        obj["speaker_id"]
            ?.takeIf {
                it.isJsonPrimitive
            }
            ?.let {
                runCatching {
                    it.asInt
                }.getOrNull()
            }

    val nameFromJson =
        obj["speaker_name"]
            ?.takeIf {
                it.isJsonPrimitive
            }
            ?.asString
            ?.trim()
            .orEmpty()

    val speakerId =
        idFromJson
            ?.takeIf {
                id ->
                members.any {
                    it.id == id
                }
            }
            ?: members
                .firstOrNull {
                    it.name ==
                        nameFromJson
                }
                ?.id
            ?: run {
                // Final tolerant fallback: recover CONTACT:id from raw text if present.
                Regex(
                    """CONTACT\s*[:：]\s*(\d+)"""
                )
                    .find(
                        raw
                    )
                    ?.groupValues
                    ?.getOrNull(
                        1
                    )
                    ?.toIntOrNull()
                    ?.takeIf {
                        id ->
                        members.any {
                            it.id == id
                        }
                    }
            }

    if (
        speakerId == null
    ) {
        throw IllegalStateException(
            "系统行为判断模型决定继续说，但没有返回有效的群成员 speaker_id / speaker_name。"
        )
    }

    val directionText =
        obj["direction"]
            ?.takeIf {
                it.isJsonPrimitive
            }
            ?.asString
            ?.trim()
            .orEmpty()

    if (
        directionText.isBlank()
    ) {
        return stop
    }

    return GroupSystemDecision(
        speak = true,
        speakerContactId =
            speakerId,
        mode =
            obj["mode"]
                ?.takeIf {
                    it.isJsonPrimitive
                }
                ?.asString
                ?.trim()
                .orEmpty()
                .ifBlank {
                    "reply"
                },
        direction =
            directionText.take(
                220
            ),
        targetLabel =
            obj["target"]
                ?.takeIf {
                    it.isJsonPrimitive
                }
                ?.asString
                ?.trim()
                .orEmpty()
                .take(
                    120
                ),
        delayClass =
            obj["delay_class"]
                ?.takeIf {
                    it.isJsonPrimitive
                }
                ?.asString
                ?.trim()
                ?.takeIf {
                    it in setOf(
                        "quick",
                        "normal",
                        "thoughtful"
                    )
                }
                ?: "normal"
    )
}

    fun delayFor(
        contact: Contact,
        decision: SocialActionDecision,
        previousText: String
    ): Long {
        var base =
            when (
                decision.delayClass
            ) {
                "quick" -> 650L
                "thoughtful" -> 2_350L
                else ->
                    when (
                        decision.mode
                    ) {
                        "reaction" -> 800L
                        "afterthought" -> 1_350L
                        "clarification" -> 1_650L
                        "followup_question" -> 1_750L
                        "topic_extension" -> 1_950L
                        "self_share" -> 2_150L
                        else -> 1_350L
                    }
            }

        base +=
            when {
                previousText.length > 180 -> 700L
                previousText.length > 80 -> 380L
                previousText.length > 30 -> 160L
                else -> 0L
            }

        val style =
            (
                contact.corePersona +
                    " " +
                    contact.styleRules
                ).lowercase()

        if (
            listOf(
                "反应快",
                "活泼",
                "健谈"
            ).any {
                style.contains(it)
            }
        ) {
            base -= 160L
        }

        if (
            listOf(
                "慢热",
                "克制",
                "沉稳"
            ).any {
                style.contains(it)
            }
        ) {
            base += 220L
        }

        return base.coerceIn(
            450L,
            4_800L
        )
    }

    fun conversationPressure(
        contactId: Int
    ): ConversationPressure {
        val recent = db.getRecentMessages(
            contactId,
            14
        )

        if (recent.isEmpty()) {
            return ConversationPressure(
                0.0,
                "暂无足够历史。"
            )
        }

        val user = recent.filter {
            it.role == "user"
        }

        val assistant = recent.filter {
            it.role == "assistant"
        }

        val userChars = user.sumOf {
            it.text.length
        }

        val assistantChars = assistant.sumOf {
            it.text.length
        }

        val totalChars = max(
            1,
            userChars + assistantChars
        )

        val assistantCharShare =
            assistantChars.toDouble() /
                totalChars.toDouble()

        val consecutiveAssistant =
            recent.asReversed()
                .takeWhile {
                    it.role == "assistant"
                }
                .size

        val assistantQuestions =
            assistant.count {
                it.text.contains("?") ||
                    it.text.contains("？")
            }

        val lastAssistantLength =
            recent.asReversed()
                .firstOrNull {
                    it.role == "assistant"
                }
                ?.text
                ?.length
                ?: 0

        val latestUser =
            recent.asReversed()
                .firstOrNull {
                    it.role == "user"
                }

        val latestUserIndex =
            latestUser?.let {
                recent.indexOf(it)
            } ?: -1

        val assistantBeforeLatestUser =
            if (latestUserIndex > 0) {
                recent.subList(
                    0,
                    latestUserIndex
                )
                    .asReversed()
                    .firstOrNull {
                        it.role == "assistant"
                    }
            } else {
                null
            }

        val terseAfterHeavy =
            latestUser != null &&
                latestUser.text.length <= 5 &&
                (
                    assistantBeforeLatestUser
                        ?.text
                        ?.length
                        ?: 0
                    ) >= 90

        var score =
            assistantCharShare * 0.38 +
                min(
                    1.0,
                    consecutiveAssistant / 3.0
                ) * 0.25 +
                min(
                    1.0,
                    assistantQuestions / 4.0
                ) * 0.14 +
                min(
                    1.0,
                    lastAssistantLength / 350.0
                ) * 0.13

        if (terseAfterHeavy) {
            score += 0.10
        }

        score = score.coerceIn(
            0.0,
            1.0
        )

        val summary =
            "最近${recent.size}条：" +
                "用户${user.size}条/${userChars}字；" +
                "联系人${assistant.size}条/${assistantChars}字；" +
                "联系人连续发言${consecutiveAssistant}条；" +
                "问句${assistantQuestions}条；" +
                "最近联系人消息${lastAssistantLength}字；" +
                "用户是否在长回复后变短=${terseAfterHeavy}。"

        return ConversationPressure(
            score,
            summary
        )
    }

    private fun isClosingSignal(
        text: String
    ): Boolean {
        val signals = listOf(
            "晚安",
            "拜拜",
            "先这样",
            "不聊了",
            "回头说",
            "等会聊",
            "一会再说",
            "我先忙了",
            "先忙了",
            "我开车去了",
            "开车去了",
            "我上课去了",
            "上课去了",
            "我要睡了",
            "我睡了",
            "先睡了",
            "我去忙了",
            "先不说了",
            "先挂了",
            "改天聊"
        )

        return signals.any {
            text.contains(it)
        }
    }

    private fun isExplicitContinueRequest(
        text: String
    ): Boolean {
        val signals = listOf(
            "继续说",
            "继续讲",
            "接着说",
            "接着讲",
            "然后呢",
            "后来呢",
            "多说点",
            "多聊点",
            "你说吧",
            "你继续",
            "再说点",
            "讲下去",
            "我听着",
            "你多讲一会",
            "陪我聊会儿",
            "你随便说"
        )

        return signals.any {
            text.contains(it)
        }
    }

    private fun parseJson(
        raw: String
    ): JsonObject? {
        val clean = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        runCatching {
            JsonParser.parseString(
                clean
            ).asJsonObject
        }.getOrNull()?.let {
            return it
        }

        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')

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
