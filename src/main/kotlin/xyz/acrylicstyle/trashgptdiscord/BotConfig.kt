package xyz.acrylicstyle.trashgptdiscord

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger(BotConfig::class.java)!!

@Serializable
data class BotConfig(
    val token: String = System.getenv("TOKEN") ?: "BOT_TOKEN_HERE",
    val openAIToken: String = System.getenv("OPENAI_TOKEN") ?: "sk-xxx",
    val openAIOrganization: String? = System.getenv("OPENAI_ORGANIZATION") ?: null,
    val characterInstruction: String = System.getenv("CHARACTER_INSTRUCTION") ?: "あなたは埼玉県さいたま市の環境キャラクター「さいちゃん」というキャラクターのように話してください。さいちゃんは幼い女の子で、無邪気な性格をしており、口調は強気であり、「〜のだ」「〜なのだ」を語尾につけます。",
    val vectorSearchHost: String = System.getenv("VECTOR_SEARCH_HOST") ?: "http://localhost:8080",
    val vectorSearchSecret: String = System.getenv("VECTOR_SEARCH_SECRET") ?: "SECRET",
) {
    companion object {
        lateinit var instance: BotConfig

        fun loadConfig(dataFolder: File) {
            val configFile = File(dataFolder, "config.yml")
            logger.info("Loading config from $configFile (absolute path: ${configFile.absolutePath})")
            if (!configFile.exists()) {
                logger.info("Config file not found. Creating new one.")
                configFile.writeText(Yaml.default.encodeToString(serializer(), BotConfig()) + "\n")
            }
            instance = Yaml.default.decodeFromStream(serializer(), configFile.inputStream())
            logger.info("Saving config to $configFile (absolute path: ${configFile.absolutePath})")
            configFile.writeText(Yaml.default.encodeToString(serializer(), instance) + "\n")
        }
    }

    fun getExtraOpenAIHeaders(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (openAIOrganization != null) map["OpenAI-Organization"] = openAIOrganization
        return map
    }
}
