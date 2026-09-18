package com.realtek.chat.ai

import android.content.Context
import android.util.Base64
import com.google.gson.*
import com.realtek.chat.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit

class RunApiGateway(context: Context) {
    private val settings = AppSettings(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .build()

    suspend fun chat(
        provider: String = settings.systemProvider,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imageBytes: ByteArray? = null,
        imageMime: String = "image/jpeg",
        maxTokens: Int? = null
    ): String = withContext(Dispatchers.IO) {
        val normalizedProvider =
            settings.normalizeProvider(provider)

        requireConfigured(
            normalizedProvider,
            model
        )

        val payload = makePayload(
            provider = normalizedProvider,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imageBytes = imageBytes,
            imageMime = imageMime,
            maxTokens = maxTokens,
            stream = false
        )

        executeJson(
            provider = normalizedProvider,
            payload = payload
        )
    }

suspend fun streamChat(
    provider: String = settings.systemProvider,
    model: String,
    systemPrompt: String,
    userPrompt: String,
    maxTokens: Int? = null,
    onPartialText: (String) -> Unit
): String = withContext(Dispatchers.IO) {
    val normalizedProvider =
        settings.normalizeProvider(provider)

    requireConfigured(
        normalizedProvider,
        model
    )

    val payload = makePayload(
        provider = normalizedProvider,
        model = model,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        imageBytes = null,
        imageMime = "image/jpeg",
        maxTokens = maxTokens,
        stream = true
    )

    val key =
        settings.apiKeyFor(normalizedProvider)

    val request = Request.Builder()
        .url(
            settings.endpointFor(
                normalizedProvider
            )
        )
        .header(
            "Authorization",
            "Bearer $key"
        )
        .header(
            "Accept",
            "text/event-stream"
        )
        .header(
            "Content-Type",
            "application/json"
        )
        .post(
            payload.toString()
                .toRequestBody(
                    "application/json; charset=utf-8"
                        .toMediaType()
                )
        )
        .build()

    val call = client.newCall(request)

    try {
        call.execute().use { response ->
            val body = response.body
            val contentType =
                response.header(
                    "Content-Type"
                ).orEmpty()

            if (!response.isSuccessful) {
                val raw =
                    body?.string().orEmpty()

                if (
                    response.code in
                    listOf(
                        400,
                        404,
                        405,
                        415,
                        422,
                        501
                    )
                ) {
                    fallbackNormal(
                        provider =
                            normalizedProvider,
                        model = model,
                        systemPrompt =
                            systemPrompt,
                        userPrompt =
                            userPrompt,
                        maxTokens =
                            maxTokens,
                        onPartialText =
                            onPartialText
                    )
                } else {
                    error(
                        providerErrorPrefix(
                            normalizedProvider
                        ) +
                            " HTTP " +
                            response.code +
                            ": " +
                            raw
                                .replace(
                                    key,
                                    "***"
                                )
                                .take(900)
                    )
                }
            } else if (body == null) {
                fallbackNormal(
                    provider =
                        normalizedProvider,
                    model = model,
                    systemPrompt =
                        systemPrompt,
                    userPrompt =
                        userPrompt,
                    maxTokens =
                        maxTokens,
                    onPartialText =
                        onPartialText
                )
            } else if (
                !contentType.contains(
                    "text/event-stream",
                    ignoreCase = true
                )
            ) {
                val raw = body.string()
                val parsedText =
                    extractResponseText(
                        raw = raw,
                        contentType =
                            contentType
                    )

                if (parsedText.isNotBlank()) {
                    withContext(
                        Dispatchers.Main.immediate
                    ) {
                        onPartialText(
                            parsedText
                        )
                    }

                    parsedText
                } else {
                    fallbackNormal(
                        provider =
                            normalizedProvider,
                        model = model,
                        systemPrompt =
                            systemPrompt,
                        userPrompt =
                            userPrompt,
                        maxTokens =
                            maxTokens,
                        onPartialText =
                            onPartialText
                    )
                }
            } else {
                val full =
                    StringBuilder()

                readSse(
                    body.source()
                ) { delta ->
                    currentCoroutineContext()
                        .ensureActive()

                    if (delta.isNotEmpty()) {
                        full.append(delta)

                        val snapshot =
                            full.toString()

                        withContext(
                            Dispatchers.Main.immediate
                        ) {
                            onPartialText(
                                snapshot
                            )
                        }
                    }
                }

                val result =
                    full.toString()
                        .trim()

                if (result.isNotBlank()) {
                    result
                } else {
                    fallbackNormal(
                        provider =
                            normalizedProvider,
                        model = model,
                        systemPrompt =
                            systemPrompt,
                        userPrompt =
                            userPrompt,
                        maxTokens =
                            maxTokens,
                        onPartialText =
                            onPartialText
                    )
                }
            }
        }
    } catch (
        t: Throwable
    ) {
        if (
            t is
            kotlinx.coroutines.CancellationException
        ) {
            call.cancel()
            throw t
        }

        fallbackNormal(
            provider =
                normalizedProvider,
            model = model,
            systemPrompt =
                systemPrompt,
            userPrompt =
                userPrompt,
            maxTokens =
                maxTokens,
            onPartialText =
                onPartialText
        )
    }
}

    private suspend fun fallbackNormal(
        provider: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int?,
        onPartialText: (String) -> Unit
    ): String {
        val text = chat(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            maxTokens = maxTokens
        )

        withContext(
            Dispatchers.Main.immediate
        ) {
            onPartialText(text)
        }

        return text
    }

    private fun makePayload(
        provider: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imageBytes: ByteArray?,
        imageMime: String,
        maxTokens: Int?,
        stream: Boolean
    ): JsonObject {
        return JsonObject().apply {
            addProperty(
                "model",
                model
            )

            addProperty(
                "stream",
                stream
            )

            maxTokens
                ?.takeIf { it > 0 }
                ?.let {
                    addProperty(
                        "max_tokens",
                        it
                    )
                }

            // Official DeepSeek currently supports thinking mode.
            // For everyday chat we explicitly disable it to reduce latency.
            if (
                provider == "deepseek"
            ) {
                add(
                    "thinking",
                    JsonObject().apply {
                        addProperty(
                            "type",
                            "disabled"
                        )
                    }
                )
            }

            add(
                "messages",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty(
                                "role",
                                "system"
                            )
                            addProperty(
                                "content",
                                systemPrompt
                            )
                        }
                    )

                    add(
                        JsonObject().apply {
                            addProperty(
                                "role",
                                "user"
                            )

                            if (
                                imageBytes == null
                            ) {
                                addProperty(
                                    "content",
                                    userPrompt
                                )
                            } else {
                                add(
                                    "content",
                                    JsonArray().apply {
                                        add(
                                            JsonObject().apply {
                                                addProperty(
                                                    "type",
                                                    "text"
                                                )
                                                addProperty(
                                                    "text",
                                                    userPrompt
                                                )
                                            }
                                        )

                                        add(
                                            JsonObject().apply {
                                                addProperty(
                                                    "type",
                                                    "image_url"
                                                )

                                                add(
                                                    "image_url",
                                                    JsonObject().apply {
                                                        addProperty(
                                                            "url",
                                                            "data:" +
                                                                imageMime +
                                                                ";base64," +
                                                                Base64.encodeToString(
                                                                    imageBytes,
                                                                    Base64.NO_WRAP
                                                                )
                                                        )
                                                    }
                                                )
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    )
                }
            )
        }
    }

private fun executeJson(
    provider: String,
    payload: JsonObject
): String {
    val endpoints =
        endpointCandidates(
            provider
        )

    var lastError:
        Throwable? = null

    for (
        endpoint in endpoints
    ) {
        for (
            attempt in 0..1
        ) {
            try {
                return executeJsonOnce(
                    provider =
                        provider,
                    endpoint =
                        endpoint,
                    payload =
                        payload
                )
            } catch (
                t: Throwable
            ) {
                if (
                    t is
                    kotlinx.coroutines
                        .CancellationException
                ) {
                    throw t
                }

                if (
                    t is GatewayHttpException
                ) {
                    if (
                        !isTransientHttp(
                            t.code
                        )
                    ) {
                        throw t
                    }
                } else if (
                    t !is IOException
                ) {
                    // 200 成功但正文格式无法解析/正文为空，不属于网络瞬时错误。
                    // 继续重试只会造成“API 被调用好几次但没有任何可见消息”。
                    throw t
                }

                lastError = t

                if (
                    attempt == 0
                ) {
                    Thread.sleep(
                        450L
                    )
                }
            }
        }
    }

    throw (
        lastError
            ?: IllegalStateException(
                providerLabel(
                    provider
                ) +
                    " 请求失败"
            )
        )
}

private fun executeJsonOnce(
    provider: String,
    endpoint: String,
    payload: JsonObject
): String {
    val key =
        settings.apiKeyFor(
            provider
        )

    val request =
        Request.Builder()
            .url(
                endpoint
            )
            .header(
                "Authorization",
                "Bearer $key"
            )
            .header(
                "Accept",
                "application/json, text/event-stream"
            )
            .header(
                "Content-Type",
                "application/json"
            )
            .post(
                payload.toString()
                    .toRequestBody(
                        "application/json; charset=utf-8"
                            .toMediaType()
                    )
            )
            .build()

    client.newCall(
        request
    )
        .execute()
        .use {
            response ->
            val contentType =
                response.header(
                    "Content-Type"
                ).orEmpty()

            val raw =
                response.body
                    ?.string()
                    .orEmpty()

            if (
                !response.isSuccessful
            ) {
                throw GatewayHttpException(
                    code =
                        response.code,
                    message =
                        providerErrorPrefix(
                            provider
                        ) +
                            " HTTP " +
                            response.code +
                            ": " +
                            raw
                                .replace(
                                    key,
                                    "***"
                                )
                                .take(
                                    900
                                )
                )
            }

            val text =
                extractResponseText(
                    raw =
                        raw,
                    contentType =
                        contentType
                )

            if (
                text.isNotBlank()
            ) {
                return text
            }

            error(
                providerLabel(
                    provider
                ) +
                    " 返回成功，但没有找到回复文本。"
            )
        }
}

private fun extractResponseText(
    raw: String,
    contentType: String
): String {
    val looksLikeSse =
        contentType.contains(
            "text/event-stream",
            ignoreCase = true
        ) ||
            raw.trimStart()
                .startsWith(
                    "data:"
                )

    if (
        looksLikeSse
    ) {
        val streamed =
            extractSseText(
                raw
            )

        if (
            streamed.isNotBlank()
        ) {
            return streamed
        }
    }

    val normal =
        extractNormalText(
            raw
        )

    if (
        normal.isNotBlank()
    ) {
        return normal
    }

    // 有些兼容网关即使请求 stream=false，
    // 也可能返回 SSE 文本但 Content-Type 不标准。
    return extractSseText(
        raw
    )
}

private fun extractSseText(
    raw: String
): String {
    val full =
        StringBuilder()

    raw.lineSequence()
        .forEach {
            line ->
            if (
                !line.startsWith(
                    "data:"
                )
            ) {
                return@forEach
            }

            val data =
                line.removePrefix(
                    "data:"
                ).trim()

            if (
                data.isBlank() ||
                data == "[DONE]"
            ) {
                return@forEach
            }

            val json =
                runCatching {
                    JsonParser
                        .parseString(
                            data
                        )
                        .asJsonObject
                }.getOrNull()
                    ?: return@forEach

            val choices =
                json.getAsJsonArray(
                    "choices"
                )

            if (
                choices == null ||
                choices.size() == 0
            ) {
                val rootText =
                    json.get(
                        "output_text"
                    )
                        ?.let(
                            ::jsonText
                        )
                        .orEmpty()

                if (
                    rootText.isNotBlank()
                ) {
                    full.append(
                        rootText
                    )
                }

                return@forEach
            }

            val choice =
                choices[0]
                    .takeIf {
                        it.isJsonObject
                    }
                    ?.asJsonObject
                    ?: return@forEach

            val candidates =
                listOfNotNull(
                    choice
                        .getAsJsonObject(
                            "delta"
                        )
                        ?.get(
                            "content"
                        ),
                    choice
                        .getAsJsonObject(
                            "message"
                        )
                        ?.get(
                            "content"
                        ),
                    choice.get(
                        "text"
                    )
                )

            val text =
                candidates
                    .asSequence()
                    .map(
                        ::jsonText
                    )
                    .firstOrNull {
                        it.isNotBlank()
                    }
                    .orEmpty()

            if (
                text.isNotBlank()
            ) {
                full.append(
                    text
                )
            }
        }

    return full.toString()
        .trim()
}

private fun jsonText(
    value: JsonElement?
): String {
    if (
        value == null
    ) {
        return ""
    }

    return when {
        value.isJsonPrimitive ->
            value.asString

        value.isJsonArray ->
            value.asJsonArray
                .map(
                    ::jsonText
                )
                .joinToString(
                    ""
                )

        value.isJsonObject -> {
            val obj =
                value.asJsonObject

            val direct =
                obj.get(
                    "text"
                )
                    ?.let(
                        ::jsonText
                    )
                    .orEmpty()

            if (
                direct.isNotBlank()
            ) {
                direct
            } else {
                obj.get(
                    "content"
                )
                    ?.let(
                        ::jsonText
                    )
                    .orEmpty()
            }
        }

        else -> ""
    }
}

private fun endpointCandidates(
    provider: String
): List<String> {
    val primary =
        settings.endpointFor(
            provider
        )

    return if (
        provider ==
            "haijing"
    ) {
        listOf(
            primary,
            "https://api.atalk-ai.com/v2/chat/completions"
        ).distinct()
    } else {
        listOf(
            primary
        )
    }
}

private fun isTransientHttp(
    code: Int
): Boolean =
    code in setOf(
        408,
        409,
        425,
        429,
        500,
        502,
        503,
        504
    )

private class GatewayHttpException(
    val code: Int,
    message: String
) : IOException(
    message
)

private fun extractNormalText(
    raw: String
): String {
    val root =
        runCatching {
            JsonParser
                .parseString(
                    raw
                )
                .asJsonObject
        }.getOrNull()
            ?: return ""

    // OpenAI Responses-like shortcut.
    root.get(
        "output_text"
    )
        ?.let(
            ::jsonText
        )
        ?.takeIf {
            it.isNotBlank()
        }
        ?.let {
            return it
        }

    // Some compatible gateways put content directly at root.
    root.get(
        "content"
    )
        ?.let(
            ::jsonText
        )
        ?.takeIf {
            it.isNotBlank()
        }
        ?.let {
            return it
        }

    val choices =
        root.getAsJsonArray(
            "choices"
        )

    if (
        choices != null &&
        choices.size() > 0
    ) {
        val first =
            choices[0]
                .takeIf {
                    it.isJsonObject
                }
                ?.asJsonObject

        if (
            first != null
        ) {
            val message =
                first.getAsJsonObject(
                    "message"
                )

            val candidates =
                listOfNotNull(
                    message?.get(
                        "content"
                    ),
                    message?.get(
                        "output_text"
                    ),
                    first.get(
                        "text"
                    ),
                    first.getAsJsonObject(
                        "delta"
                    )?.get(
                        "content"
                    )
                )

            candidates.forEach {
                value ->
                val text =
                    jsonText(
                        value
                    )

                if (
                    text.isNotBlank()
                ) {
                    return text
                }
            }
        }
    }

    // Responses-style output array:
    // output:[{content:[{type:"output_text",text:"..."}]}]
    val output =
        root.getAsJsonArray(
            "output"
        )

    if (
        output != null
    ) {
        val pieces =
            mutableListOf<String>()

        output.forEach {
            item ->
            if (
                !item.isJsonObject
            ) {
                return@forEach
            }

            val obj =
                item.asJsonObject

            obj.get(
                "text"
            )
                ?.let(
                    ::jsonText
                )
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let(
                    pieces::add
                )

            val content =
                obj.getAsJsonArray(
                    "content"
                )

            content?.forEach {
                part ->
                if (
                    !part.isJsonObject
                ) {
                    return@forEach
                }

                part.asJsonObject
                    .get(
                        "text"
                    )
                    ?.let(
                        ::jsonText
                    )
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.let(
                        pieces::add
                    )
            }
        }

        if (
            pieces.isNotEmpty()
        ) {
            return pieces.joinToString(
                ""
            )
        }
    }

    return ""
}

private suspend fun readSse(
    source: BufferedSource,
    onDelta: suspend (String) -> Unit
) {
    while (
        !source.exhausted()
    ) {
        val line =
            source.readUtf8Line()
                ?: break

        if (
            !line.startsWith(
                "data:"
            )
        ) {
            continue
        }

        val data =
            line.removePrefix(
                "data:"
            ).trim()

        if (
            data == "[DONE]"
        ) {
            break
        }

        if (
            data.isBlank()
        ) {
            continue
        }

        val json =
            runCatching {
                JsonParser
                    .parseString(
                        data
                    )
                    .asJsonObject
            }.getOrNull()
                ?: continue

        val choices =
            json.getAsJsonArray(
                "choices"
            )

        if (
            choices == null ||
            choices.size() == 0
        ) {
            // Some compatible SSE gateways emit root-level output_text.
            val rootText =
                json.get(
                    "output_text"
                )
                    ?.let(
                        ::jsonText
                    )
                    .orEmpty()

            if (
                rootText.isNotBlank()
            ) {
                onDelta(
                    rootText
                )
            }

            continue
        }

        val first =
            choices[0]
                .takeIf {
                    it.isJsonObject
                }
                ?.asJsonObject
                ?: continue

        val candidates =
            listOfNotNull(
                first.getAsJsonObject(
                    "delta"
                )?.get(
                    "content"
                ),
                first.getAsJsonObject(
                    "message"
                )?.get(
                    "content"
                ),
                first.get(
                    "text"
                )
            )

        val text =
            candidates
                .asSequence()
                .map(
                    ::jsonText
                )
                .firstOrNull {
                    it.isNotBlank()
                }
                .orEmpty()

        // Intentionally do NOT surface reasoning_content / chain-of-thought.
        if (
            text.isNotBlank()
        ) {
            onDelta(
                text
            )
        }
    }
}

    private fun requireConfigured(
        provider: String,
        model: String
    ) {
        require(
            settings.providerConfigured(
                provider
            )
        ) {
            "请先到“我 → 设置 → AI 服务”配置 " +
                providerLabel(provider) +
                " API Key。"
        }

        require(
            model.isNotBlank()
        ) {
            "当前联系人的模型 ID 为空。"
        }
    }

    private fun providerLabel(
        provider: String
    ): String =
        if (
            provider == "deepseek"
        ) {
            "DeepSeek 官方"
        } else {
            "海鲸AI"
        }

    private fun providerErrorPrefix(
        provider: String
    ): String =
        providerLabel(provider) +
            " 请求失败"
}
