@file:JvmName("MainKt")
package xyz.acrylicstyle.trashgptdiscord

import dev.kord.core.Kord
import dev.kord.core.behavior.reply
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import dev.kord.gateway.Intent
import dev.kord.gateway.Intents
import dev.kord.gateway.NON_PRIVILEGED
import dev.kord.gateway.PrivilegedIntent
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicReference

private val logger = LoggerFactory.getLogger("TrashGPTDiscord")
private val httpClient = HttpClient(CIO)

@OptIn(PrivilegedIntent::class)
suspend fun main() {
    BotConfig.loadConfig(File("."))

    val client = Kord(BotConfig.instance.token)

    client.on<ReadyEvent> {
        logger.info("Logged in as ${client.getSelf().username}")
    }

    client.on<MessageCreateEvent> {
        if (message.author?.isBot != false) return@on
        if (message.content.isBlank()) return@on
        if (!message.mentionedUserIds.contains(client.selfId) && message.referencedMessage?.author?.id != client.selfId) {
            return@on
        }
        val trimmed = message.content.replace("<@!?${kord.selfId}>".toRegex(), "").trim()
        if (trimmed.isBlank() && message.attachments.isEmpty()) return@on
        if (trimmed.startsWith("$")) {
            val args = trimmed.split(" ")
            if (args[0] == "\$add") {
                if (args.size < 3) {
                    message.reply { content = "Usage: \$add <item> <text>" }
                    return@on
                }
                val item = args[1]
                val text = args.drop(2).joinToString(" ")
                val response = httpClient.post("${BotConfig.instance.vectorSearchHost}/insert") {
                    header("Accept", "application/json")
                    header("Authorization", BotConfig.instance.vectorSearchSecret)
                    header("Content-Type", "application/json")
                    setBody(Json.encodeToString(JsonArray(listOf(JsonObject(mapOf(
                        "pageContent" to JsonPrimitive(text),
                        "metadata" to JsonObject(mapOf(
                            "name" to JsonPrimitive(item)
                        ))
                    ))))))
                }
                message.reply { content = "Added item `$item`\n```\n${response.bodyAsText()}\n```" }
            }
            return@on
        }
        val msg = message.reply { content = "Thinking..." }
        val currentMessage = AtomicReference("")
        Util.generateOpenAI(currentMessage, msg, message)
    }

    client.login {
        intents {
            +Intents.NON_PRIVILEGED
            +Intent.MessageContent
        }
    }
}
