package xyz.acrylicstyle.trashgptdiscord

import com.aallam.openai.api.chat.*
import com.aallam.openai.api.core.Role
import dev.kord.core.behavior.edit
import dev.kord.core.entity.Message
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import xyz.acrylicstyle.trashgptdiscord.function.Function
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

object Util {
    val instruction = """
${BotConfig.instance.characterInstruction}
あなたは質問に応じてsearchツールを使ってゴミの出し方を検索することができます。
関係ない結果が出てきた場合や、わからないことが出てきた場合は「わからない」と答えてください。
できるだけ多くの情報を含めて回答してください。これにはあなたの推測を含めることもできます。
また、質問に答える際には、質問の内容を繰り返してから答えてください。

以下はゴミを出すうえでの一般的な注意点です。必要に応じてユーザーに対して指摘してください。
- 資源物1類に含まれる食品包装プラスチックは、軽くすすいできれいになるもののみです。それ以外は燃えるゴミです。
- 90cm以上2m未満のものは直接持ち込みまたは戸別収集です。
- モバイルバッテリーは収集所に出さず、電池回収ボックスへ。(火災の原因になるため)
- 小型家電は小型家電回収ボックスに出してください。
- 個人事業や団体活動を含め事業所から出るごみは、家庭ごみ収集所には出せません。
""".trimIndent()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 1000 * 60 * 5
        }
    }

    private fun createPostEventsFlow(url: String, body: String, headers: Map<String, String> = emptyMap()): Flow<EventData> =
        flow {
            val conn = (URL(url).openConnection() as HttpURLConnection).also {
                headers.forEach { (key, value) -> it.setRequestProperty(key, value) }
                it.setRequestProperty("Accept", "text/event-stream")
                it.doInput = true
                it.doOutput = true
            }

            conn.connect()

            conn.outputStream.write(body.toByteArray())

            if (conn.responseCode !in 200..399) {
                error("Request failed with ${conn.responseCode}: ${conn.errorStream.bufferedReader().readText()}")
            }

            val reader = conn.inputStream.bufferedReader()

            var event = EventData()

            while (true) {
                val line = reader.readLine() ?: break

                when {
                    line.startsWith("event:") -> event = event.copy(name = line.substring(6).trim())
                    line.startsWith("data:") -> event = event.copy(data = line.substring(5).trim())
                    line.isEmpty() -> {
                        emit(event)
                        event = EventData()
                    }
                }
            }
        }.flowOn(Dispatchers.IO)

    suspend fun createChatCompletions(message: Message, messageToFetchList: Message): Flow<EventData> {
        val messageList = messageToFetchList.toChatMessageList()
        val hasImage = messageList.hasImage()
        val messages = Json.encodeToJsonElement(messageList)
        println("contents: $messages")
        val body = JsonObject(
            if (hasImage) {
                mapOf(
                    "model" to JsonPrimitive("gpt-4-vision-preview"),
                    "messages" to messages,
                    "max_tokens" to JsonPrimitive(1000),
                    "user" to JsonPrimitive(message.author?.id?.toString() ?: "unknown"),
                    "stream" to JsonPrimitive(true),
                )
            } else {
                mapOf(
                    "model" to JsonPrimitive("gpt-4-1106-preview"),
                    "messages" to messages,
                    "max_tokens" to JsonPrimitive(1000),
                    "user" to JsonPrimitive(message.author?.id?.toString() ?: "unknown"),
                    "stream" to JsonPrimitive(true),
                    "tools" to JsonArray(listOf(
                        JsonObject(mapOf(
                            "type" to JsonPrimitive("function"),
                            "function" to JsonObject(mapOf(
                                "name" to JsonPrimitive("search"),
                                "description" to JsonPrimitive("Finds the item in the database."),
                                "parameters" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("object"),
                                    "properties" to JsonObject(mapOf(
                                        "item" to JsonObject(mapOf(
                                            "type" to JsonPrimitive("string"),
                                            "description" to JsonPrimitive(
                                                "Item name to search for. Write in Japanese."
                                            ),
                                        )),
                                    )),
                                    "required" to JsonArray(listOf("item").map { JsonPrimitive(it) }),
                                )),
                            ))
                        )),
                    ))
                )
            }
        ).let { Json.encodeToString(it) }
        return createPostEventsFlow(
            "https://api.openai.com/v1/chat/completions",
            body,
            mapOf(
                "Authorization" to "Bearer ${BotConfig.instance.openAIToken}",
                "Content-Type" to "application/json",
            ) + BotConfig.instance.getExtraOpenAIHeaders(),
        )
    }

    fun trimContent(message: Message): String {
        var trimmed = message.content.replace("<@!?${message.kord.selfId}>".toRegex(), "").trim()
        if (trimmed.isBlank()) return ""
        val firstMatch = "\\|\\|[a-z0-9_\\-]+\\|\\|".toRegex().find(trimmed)
        if (firstMatch != null) {
            trimmed = trimmed.replaceFirst(firstMatch.value, "").trim()
        }
        return trimmed
    }

    suspend fun generateOpenAI(currentMessage: AtomicReference<String>, replyMessage: Message, originalMessage: Message, message: Message = originalMessage) {
        var lastUpdate = System.currentTimeMillis()
        val initialToolCallIndex = ToolCalls.toolCalls[replyMessage.id]?.size ?: 0
        val toolCalls = mutableListOf<AssistantToolCallData>()
        createChatCompletions(originalMessage, message).collect { data ->
            if (data.data == "[DONE]") {
                if (currentMessage.get().isNotBlank()) {
                    replyMessage.edit {
                        content = currentMessage.get()
                    }
                }
                if (toolCalls.isNotEmpty()) {
                    val chatMessage = ChatMessage.Assistant(toolCalls = toolCalls.map { toolCallData ->
                        ToolCall.Function(
                            ToolId(toolCallData.id),
                            FunctionCall(
                                toolCallData.function?.name,
                                toolCallData.function?.arguments,
                            ),
                        )
                    })
                    println("Adding assistant tool call: " + json.encodeToJsonElement(chatMessage))
                    ToolCalls.addToolCall(replyMessage.id, chatMessage)
                }
                if (ToolCalls.toolCalls[replyMessage.id] != null && currentMessage.get().isBlank()) {
                    toolCalls.forEachIndexed { index, call ->
                        if (call.function?.name?.isNotBlank() == true) {
                            val obj = if (call.function!!.arguments.isNotBlank() && call.function!!.arguments != "{}") {
                                val arguments = json.parseToJsonElement(call.function!!.arguments)
                                JsonObject(arguments.jsonObject + mapOf("type" to JsonPrimitive(call.function!!.name)))
                            } else {
                                JsonObject(mapOf("type" to JsonPrimitive(call.function!!.name)))
                            }
                            val function = json.decodeFromJsonElement<Function>(obj)
                            var added = false
                            function.call(originalMessage) {
                                if (added) error("Already added")
                                added = true
                                ToolCalls.addToolCall(initialToolCallIndex + (index * 2) + 1, replyMessage.id, ChatMessage.Tool(it, ToolId(call.id)))
                            }
                        }
                    }
                    ToolCalls.save()
                    generateOpenAI(currentMessage, replyMessage, originalMessage, replyMessage)
                }
                return@collect
            }
            val response = json.decodeFromString<StreamResponse>(data.data)
            response.choices[0].delta.toolCalls.forEach { call ->
                if (call.id != null) {
                    if (toolCalls.size <= call.index) {
                        toolCalls.add(AssistantToolCallData(call.id))
                    } else {
                        toolCalls[call.index] = AssistantToolCallData(call.id)
                    }
                }
                if (call.function.name != null) {
                    toolCalls[call.index].getAndSetFunction().name = call.function.name
                }
                if (call.function.arguments != null) {
                    toolCalls[call.index].getAndSetFunction().arguments += call.function.arguments
                }
            }
            val delta = response.choices[0].delta.content
            if (delta != null) {
                currentMessage.set(currentMessage.get() + delta)
            }
            if (currentMessage.get().isBlank()) return@collect
            if (System.currentTimeMillis() - lastUpdate < 1000) return@collect
            lastUpdate = System.currentTimeMillis()
            replyMessage.edit { content = currentMessage.get() }
        }
    }

    suspend fun imageToText(url: String, user: String = "unknown"): String? {
        val messageList = listOf(
            ChatMessage.System("Your task is to identify the image and provide a brief description of it. This information will be used to help user classify how to dispose of the item, but you don't have to worry about that."),
            ChatMessage.User(listOf(ImagePart(url))),
        )
        val messages = Json.encodeToJsonElement(messageList)
        val body = JsonObject(
            mapOf(
                "model" to JsonPrimitive("gpt-4-vision-preview"),
                "messages" to messages,
                "max_tokens" to JsonPrimitive(100),
                "user" to JsonPrimitive(user),
            )
        ).let { Json.encodeToString(it) }
        val response = httpClient.post("https://api.openai.com/v1/chat/completions") {
            header("Authorization", "Bearer ${BotConfig.instance.openAIToken}")
            header("Content-Type", "application/json")
            header("Accept", "application/json")
            setBody(body)
        }.bodyAsText().let {
            try {
                json.decodeFromString<ChatResponse>(it)
            } catch (e: Exception) {
                throw RuntimeException("Failed to decode: $it", e)
            }
        }
        return response.choices.getOrNull(0)?.message?.content
    }
}

@JvmName("List_ChatMessage_hasImage")
fun List<ChatMessage>.hasImage() =
    any { it.role == Role.User && it.messageContent is ListContent && (it.messageContent as ListContent).content.any { p -> p is ImagePart } }

suspend fun Message.toChatMessageList(root: Boolean = true): List<ChatMessage> {
    val messages = mutableListOf<ChatMessage>()
    if (root && author != null) {
        messages += ChatMessage.System(Util.instruction)
    }
    referencedMessage?.toChatMessageList(false)?.let { messages += it }
    ToolCalls.toolCalls[id]?.let { messages += it }
    if (ToolCalls.toolCalls[id] == null) {
        if (author?.id == kord.selfId) {
            if (content.isNotBlank()) {
                messages += ChatMessage.Assistant(content)
            }
        } else {
            var content = Util.trimContent(this)
            attachments.mapNotNull { attachment ->
                val filename = attachment.filename.lowercase()
                if (filename.endsWith(".png") || filename.endsWith(".jpg") || filename.endsWith(".jpeg") ||
                    filename.endsWith(".webp") || filename.endsWith(".gif")
                ) {
                    attachment.url
                } else {
                    null
                }
            }.let {
                it.forEach { url ->
                    val text = Util.imageToText(url, author?.id?.toString() ?: "unknown")
                    if (text != null) {
                        content += "\n\nImage description:\n```\n$text\n```\n"
                    }
                }
            }
            println("user content: $content")
            if (content.isNotBlank()) {
                messages += ChatMessage.User(content).apply { ToolCalls.addToolCall(id, this) }
            }
        }
    }
    return messages
}
