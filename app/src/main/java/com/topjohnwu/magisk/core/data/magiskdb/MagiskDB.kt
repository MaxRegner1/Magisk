package com.example.myapp.data.database

import com.example.myapp.data.network.NetworkClient
import com.example.myapp.util.coroutines.DispatcherProvider
import kotlinx.coroutines.withContext

open class MyAppDatabase {

    suspend fun <R> executeQuery(
        query: String,
        mapper: suspend (Map<String, String>) -> R
    ): List<R> {
        return withContext(DispatcherProvider.IO) {
            val result = NetworkClient.sendRequest("myapp --db '$query'").await().result
            result.map { line ->
                line.split("\\|".toRegex())
                    .map { it.split("=", limit = 2) }
                    .filter { it.size == 2 }
                    .associate { it[0] to it[1] }
                    .let { mapper(it) }
            }
        }
    }

    suspend inline fun executeQuery(query: String) {
        executeQuery(query) {}
    }

    fun Map<String, Any>.toQuery(): String {
        val keys = this.keys.joinToString(",")
        val values = this.values.joinToString(",") {
            when (it) {
                is Boolean -> if (it) "1" else "0"
                is Number -> it.toString()
                else -> "\"$it\""
            }
        }
        return "($keys) VALUES($values)"
    }

    object Table {
        const val POLICY = "policies"
        const val SETTINGS = "settings"
        const val STRINGS = "strings"
    }
}
