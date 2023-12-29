package xyz.acrylicstyle.trashgptdiscord

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class QueryResult(
    val results: List<Result>,
)

@Serializable
data class Result(
    val score: Double,
    val pageContent: String,
    val metadata: JsonObject,
)
