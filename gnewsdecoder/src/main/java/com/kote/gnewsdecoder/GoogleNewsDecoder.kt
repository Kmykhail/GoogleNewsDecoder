package com.kote.gnewsdecoder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URL
import java.net.URLEncoder

data class DecodingParams(
    val signature: String,
    val timestamp: String,
    val base64Str: String
)

class GoogleNewsDecoder {
    private val client: OkHttpClient = OkHttpClient()
    private val batchUrl = "https://news.google.com/_/DotsSplashUi/data/batchexecute"
    private val jsonMediaType = "application/x-www-form-urlencoded;charset=UTF-8".toMediaType()

    suspend fun decodeGoogleNewsUrl(link: String, interval: Int = 0) : Map<String, Any> {
        val base64Str = getBase64(link) ?: return errorResponse("Invalid Google News URL format")

        val decodingParams = getDecodingParams(base64Str)
        if (decodingParams["status"] == false) {
            return decodingParams
        }

        val params = decodingParams["decodingParams"] as? DecodingParams ?: return errorResponse("Invalid parameters format")
        val decodedUrlResponse = decodeUrl(params)

        if (interval > 0) {
            delay(interval * 1000L)
        }

        return decodedUrlResponse
    }

    private fun errorResponse(message: String) : Map<String, Any> {
        return mapOf("status" to false, "message" to message)
    }

    private fun getBase64(link: String) : String? {
        val url = URL(link)
        val path = url.path.split("/")
        if (
            url.host == "news.google.com" &&
            path.size > 1 &&
            path[path.size - 2] in listOf("articles", "rss")
        ) {
            return path.last()
        }
        return  null
    }

    private suspend fun getDecodingParams(base64Str: String): Map<String, Any> {
        return withContext(Dispatchers.IO) {
            try {
                val doc = Jsoup.connect("https://news.google.com/articles/$base64Str").get()
                val dataElements = doc.select("c-wiz > div[jscontroller]").first()
                if (dataElements == null)  {
                    return@withContext mapOf("status" to false, "message" to "Failed to fetch data attributes from Google News with the articles URL")
                }
                mapOf("status" to true, "decodingParams" to DecodingParams(
                    signature = dataElements.attr("data-n-a-sg"),
                    timestamp = dataElements.attr("data-n-a-ts"),
                    base64Str = base64Str)
                )
            } catch (e: Exception) {
                mapOf("status" to false, "message" to "Unexpected error in get_decoding_params ${e.toString()}")
            }
        }
    }

    private suspend fun decodeUrl(decodingParams: DecodingParams): Map<String, Any> {
        return withContext(Dispatchers.IO) {
            try {
                val payload = buildJsonPayload(decodingParams)
                val request = buildRequest(payload)
                val response = client.newCall(request).execute()

                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    println("response status: ${response.code}")
                    return@withContext mapOf(
                        "status" to false,
                        "message" to "HTTP code ${response.code}",
                        "response" to responseBody,
                        "request" to payload
                    )
                }
                parseResponse(responseBody)
            } catch (e: Exception) {
                mapOf("status" to false, "message" to e.toString())
            }
        }
    }

    private fun buildRequest(payload: String) : Request {
        val requestData = "[[$payload]]"
        val encodedPayload = URLEncoder.encode(requestData, "UTF-8")
        val body = "f.req=$encodedPayload".toRequestBody(jsonMediaType)

        return Request.Builder()
            .url(batchUrl)
            .post(body)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36")
            .build()
    }

    private fun buildJsonPayload(decodingParams: DecodingParams): String {
        val innerArray = """
            ["garturlreq",[["X","X",["X","X"],null,null,1,1,"US:en",null,1,null,null,null,null,null,0,1],"X","X",1,[1,1,1],1,1,null,0,0,null,0],"${decodingParams.base64Str}",${decodingParams.timestamp},"${decodingParams.signature}"]
        """.trimIndent()

        val escapedInnerArray = innerArray.replace("\"", "\\\"")

        return """["Fbv4je", "$escapedInnerArray"]"""
    }

    private fun parseResponse(responseBody: String) : Map<String, Any> {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val parts = responseBody.split("\n\n", limit = 2)
            if (parts.size < 2) {
                return mapOf("status" to false, "message" to "URL array too short")
            }
            val jsonText = parts[1].removePrefix(")]}'").trim()
            val mainArray = json.parseToJsonElement(jsonText).jsonArray

            val responseData = mainArray
                .dropLast(2)
                .firstOrNull() as? JsonArray
                ?: return mapOf("status" to false, "message" to "Missing response data array")

            if (responseData.size < 3) {
                return mapOf("status" to false, "message" to "Response array too short")
            }

            val innerJson = responseData[2].jsonPrimitive.content
            val urlArray = json.parseToJsonElement(innerJson).jsonArray

            if (urlArray.size < 2) {
                return mapOf("status" to false, "message" to "URL array too short")
            }

            mapOf("status" to true, "decodedUrl" to urlArray[1].jsonPrimitive.content)
        } catch (e: Exception) {
            mapOf("status" to false, "message" to "Parsing failed: ${e.message}")
        }
    }
}

//fun main() = runBlocking {
//    println("=== Running Library Test ===")
//    val googleNewsLink = "https://news.google.com/rss/articles/CBMiX0FVX3lxTFAyWFBUVGdDNG14bDJRRk1jZlBjRE5ZRDJoMF9HYVZReVZZbkV4bElkU21NWnNwek0wRE9FVFZaeV9jWUtvaVFXdVIxSk5odEVUeS1fRUhYRzZKQ09fZHFn0gFrQVVfeXFMTXM2NXY0bE5hZVV3eWdUZG1NSUw3N004V0ZXZVVzZU5uel9qdUtNYXJoeF9icGVkd3JkVHAtRHE2ZVBOLXZ4RlBJLVhmVWE4dlliMjBra25NT0pKeHg3RlUxSU5WZ1Vsc3FuMjg?oc=5"
//    val decoder = GoogleNewsDecoder()
//
//    var response = decoder.decodeGoogleNewsUrl(googleNewsLink, 2)
//    when(response["status"]) {
//        true -> {
//            val url = response["decodedUrl"] as? String
//            url?.let {
//                println("Decoded URL: $url")
//            } ?: {
//                println("Error: Invalid URL format in response: $response")
//            }
//        }
//        false -> {
//            val message = response["message"] as? String ?: "Unknown error"
//            println("Error: $message")
//        }
//        else -> {
//            println("Error: Invalid response format: $response")
//        }
//    }
//    println("=== End Library Test ===")
//}
