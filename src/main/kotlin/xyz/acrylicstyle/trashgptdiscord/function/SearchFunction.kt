package xyz.acrylicstyle.trashgptdiscord.function

import dev.kord.core.entity.Message
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import xyz.acrylicstyle.trashgptdiscord.BotConfig
import xyz.acrylicstyle.trashgptdiscord.QueryResult
import xyz.acrylicstyle.trashgptdiscord.Util
import java.net.URLEncoder

@Serializable
@SerialName("search")
data class SearchFunction(val item: String) : Function {
    companion object {
        private val client = HttpClient(CIO)
    }

    override suspend fun call(originalMessage: Message, addToolCallOutput: (String) -> Unit) {
        val encodedQuery = item.trim('\n', ' ').ifBlank {
            Util.trimContent(originalMessage)
        }.let { URLEncoder.encode(it, "UTF-8") }
        val responseText = withContext(Dispatchers.IO) {
            client.get("${BotConfig.instance.vectorSearchHost}/query?query=$encodedQuery&top_k=10&enforce_ja=true") {
                header("Accept", "application/json")
                header("Authorization", BotConfig.instance.vectorSearchSecret)
            }.bodyAsText()
        }
        val result = Json.decodeFromString<QueryResult>(responseText)
        if (result.results.isEmpty()) {
            addToolCallOutput("No results found.")
            return
        }
        var text = "Results:\n"
        result.results.sortedByDescending { it.score }.forEach { res ->
            text += "${res.metadata["name"]?.jsonPrimitive?.content}: ${res.pageContent}\n\n"
        }
        println(text)
        addToolCallOutput(text)
    }
}
