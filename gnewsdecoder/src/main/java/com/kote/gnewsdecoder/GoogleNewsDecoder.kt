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
import org.jsoup.nodes.Document
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder

data class DecodingParams(
    val signature: String,
    val timestamp: String,
    val base64Str: String
)

class GoogleNewsDecoder(
    proxyHost: String? = null,
    proxyPort: Int? = null
) {
    private var client : OkHttpClient = if (proxyHost != null && proxyPort != null) {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort))
        OkHttpClient.Builder()
            .proxy(proxy)
            .build()
    } else {
        OkHttpClient()
    }

    private val batchUrl = "https://news.google.com/_/DotsSplashUi/data/batchexecute"
    private val jsonMediaType = "application/x-www-form-urlencoded;charset=UTF-8".toMediaType()

    suspend fun decodeGoogleNewsUrl(link: String, intervalMilli: Long = 0) : Map<String, Any> {
        val base64Str = getBase64(link) ?: return errorResponse("Invalid Google News URL format")

        val decodingParams = getDecodingParams(base64Str)
        if (decodingParams["status"] == false) {
            return decodingParams
        }

        val params = decodingParams["decodingParams"] as? DecodingParams ?: return errorResponse("Invalid parameters format")
        val decodedUrlResponse = decodeUrl(params)

        if (intervalMilli > 0) {
            delay(intervalMilli)
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

//fun mainSingleRequest() = runBlocking {
//    println("=== Single Request Example ===")
//    val googleNewsLink = "https://news.google.com/rss/articles/CBMiygFBVV95cUxOQlZDcndJLW4zUGxxeGVkc0NfYzJacEVNSlZaWG1WN1J1Y0JsSm50QmlROUw4eUpFNFQyRzkzTkZ0WVd0ZXdzUU50bDZmTDBPbW9iSTJxSUcxTWdOWkd0UTU2aWpiUzBkZ2hGcHhGLWY4Q2YzTC1rWk50eEZYZFlCY29NUlhndm9nZnFyME5jc1JmeWZEbUJJZHVJWFRQSUZDWkZRZy1tQ0FDTXRaNGZGeUJEeUR5VWZrY2ZxYmhNSmhlajZ5a0RodWlR0gHgAUFVX3lxTE9FZ2I4bXdmdGRpTmxEaF8tUUZRaWVSNW5oZHZDa0xZd3RndUV1MmhPLXJucnB0VXZOdDdjWkRLc3EyUXRDalRIdEExekRuZG5od2tJdFhPRHVVTFg3Y3BfdDZYNDl0Z0hLcXExZWJGc0xiN3FSV1Y5ZWZCWEZmc1NERHJCVzUwR1ZNQkVHbEcxeldhZVBpaWpHREE0RXEwS2xVR3AtNVN1ZXZNeGNXZjgyaG5qejhoancyTElCOWR5SnNFOEhRV3pUQnptVE1qX3VGTWlvQ2s4c2hldkIzVVRn?oc=5"
//    val decoder = GoogleNewsDecoder()
//
//    val interval = 1500L
//    val response = decoder.decodeGoogleNewsUrl(googleNewsLink, interval)
//    when (response["status"]) {
//        true -> {
//            val decodedUrl = response["decodedUrl"] as? String
//            if (decodedUrl != null) {
//                println("Decoded URL: $decodedUrl")
//            } else {
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
//    println("=== End of Single Request ===")
//}

//fun mainMultipleRequests() = runBlocking {
//    println("=== Multiple Requests Example ===")
//    val decoder = GoogleNewsDecoder()
//
//    val intervals = listOf(500L, 1000L, 1500L)
//    val rssUrls = listOf(
//        "https://news.google.com/rss/articles/CBMiXkFVX3lxTE1qRWRxaFBYVFJvX0pMMWpWOThkVDVSS1cwZ0Q4Tl96VkZIdFBTUnlTMHd4bkF3eDRhRGtFMkhaa2hfbzE3UU5Ba0ZxdTYxWGJpeGhoNFpQRDNkbFdaNGc?oc=5",
//        "https://news.google.com/rss/articles/CBMikgFBVV95cUxPUEhtMHJFOU5YZ2YxaDB6RHVBdFh5VVZYdko5MU5GSkRmZExVV2RGRTBaamVSMFIxZFR1S1ZJeTJaZUYwaVYyendHV2d2dVdpbGhNcWlndWpZMzhpRDJXUnh2RVREeVI5RW0zdXd6N1drc2FLNnVpdnktajZ4R1phLUFmNTJ3WXZtTGpRWTZRZ0tIQQ?oc=5",
//        "https://news.google.com/rss/articles/CBMinwFBVV95cUxPOUpJRVpfUVd0ZDNKY21qUWJ4bUdPNWdJQ3RxQ293a0VaUVppY2huZzlka2pGTDNXWVVLRng4SEJ1RktjU2xWa3VZb1g2bTR3cGtMRWRnYnNjZWxXYUZkNlVOdTRfLVdMYlVzR1VrdEdjTHdfOHozb0VXRGw1dFk5S052U3p0TDZ3MmYzVF9lcm1ldVMwWHpwZG16UnRNMnM?oc=5",
//        "https://news.google.com/rss/articles/CBMiqwFBVV95cUxQejdSeGtyZGdEQnN5b2tlVm9rUlhCdGMyQTM4a1o3LWJYMnB1MlJnT3Itb29DMVpOd3hrdjRfUThvR3F0c19fNzgxZ2kzVlU1VnJRbHh4M1BWeWxlOUNrYWhSaFItY1dKak81UDdDdEhPMFAzTl9BOWtvdzNTdExKYlVsQzNjaTFxT2YyV09vYkIxbzFPcmRnRy1ieER3N0k4UG9sdUtVY0lEWlXSAbABQVVfeXFMUEUyVm45TXA0TkthemRHYmd4bjVGSVF4b2E5TFd1NVktcDlBV09lRzZPV1k4cnVxQUZYUVV0d0k0SnRrbHlRelJVVGRfczhTVFBYMWthSGRyQjZ1NmMyM1YxVDhHRDFtanlmNElYTkZ1NUxPRU1PblZtMmNzSWxqY0FIMUdUU0tLTFpqWUUxNnUxTHRYUG4xd2F5emdia1pKMVJ0b1BzN0xOdm80N0lUNXM?oc=5",
//        "https://news.google.com/rss/articles/CBMiywFBVV95cUxQUzlSdUh1U3c1NmFrN0NFeDltekVoNjd5U0ZzaFhnemhsQVNfaTh6NmdhUEpUOGFxTGc3bzZiSldRTGJta1BsNkUtX1dERE5YMnJ5a2xNeURrX1B0dTJUUGoyWWExY2dPUjVvNEljbDJwa3dMUExEUHNYaTd3bGJRUWRWSHJjSkhHV2ROYUdKR0xEVVdPcUJRUzVaRUxfWVhiajBGeEM2QXZEbGQ4YjJZNk43WVV2YW85ZmdWbm01STcySEdCR1N2TnJSVdIBggJBVV95cUxPUkRldmVwVThWcVFEbGJHeUw2c2pVRTIzQlRLT3RyRldQSkxOR2NWb0RFblpuV3dqbkFOVmF1MHdJYVBLcWIwYV96Qk9lQkY1YmdDeVJFdDNVMWcwTXV1emItQjZ0al9CTjhDaUxEeGNaVHp0UF9oaTJMRDg0STZZMEFkV09kLUNrbTJ2WmlIcTlBOVp0eTF4alpWOEJpQXVFVmh3RWtGM0poZHdNdkVWLXhnQlpTOU5MRzVVR0QtUlZGZ3ZpMjUwS1A0RVA3Z29SYUVEZnptdkJxc0dFenh4S2c4VnZsMnhJNWZlMlNnTmV4QmtMVmJyald5dHVCaGl4Z3c?oc=5",
//        "https://news.google.com/rss/articles/CBMijwFBVV95cUxNVUZsX3BYSzNEb3A2VmYyak13TG5HalRxSFhlQm1GWXJQdlJpdFNBVTB1WW4wM3NvZm9KWU5yNDBTM3dJN3VOWXBCZVZseGdlVzZZX2tuMEpkZGlhR0tVNDVpVVo4LTdyczVJZjBTUi1Uc29vTDBKbkhBS3hSQ2d5LW9KZW1TWWFUVnZ5WTNrSQ?oc=5",
//        "https://news.google.com/rss/articles/CBMiywFBVV95cUxOSmlLWkUzRktONmF6R2ZiTlJ2WlJXaGI2N1lUbkc4S1VnLV9hakdmT1ctT0dyemtnTDJGNlRxd0FmcmpnbFdXZk5nT19WOFRwSHlJMEludm42SGVMRkxlakNINXFjU05OLWVUMmtEWkpHLVh6RDN4RnhXMXliaUVXbFY4T2RDVm5kbXZJd1RKaHFvM2N0cG1hV1ZJeURrUnAyVFdFQXRySzFqLVRJd3g3SWJ2UjRac1BNeEdDQ2JLUDR6MXlHTmdaUjJpRdIB5AFBVV95cUxNTy1rc3lUb001Z0JMaW5jUFAyaFhXNkJjN0xMbXpjN2ZjdEhNZUtFbVVJTTYyLXBaSm5EQWtFTFd6UVgyOU5LZFg3T2VUT1VJM0t0aktZaXFJb0hjUEhUWXdGZHE3Y0Job3hyMFVjU0Z1TWVycGNwMzNCWTJxSXBPeVJhelhWby1mbzFwRkh3QkU0Y3VLRVVUZWFYM1BFZHkzbGZUZV9FX3ZwTXZ4NkFKWmhUaUV5X3E2aTY0MGMxTjBITlZSNE5na1lMckZrNXZ0U2JNdnZNMGg3S2ZjTTdvMUlHa1I?oc=5",
//        "https://news.google.com/rss/articles/CBMiiwFBVV95cUxPcVZQSmN1eDMydDM2NzNUa3NmQnFfRkhWQWtieGFfOFpjMkl1LVJBRTdHMWJRUDNRWGpicDU0WV9kcnA1cFlDMkRGUkZ1UnI4RXlTazFjUWpZUXZYMmdNdUc1ZmkydDhMZk5tMDZFUFQxQ2h5WG1VYUxlV3VVeUxlMnc2c281UVRYZkR30gGGAUFVX3lxTFBxMU9HQTJnWWdWN3RidzByZ281emhCNVkzRk1vQXFuVWxRMW1kR0VEM0liN0F4S3pFMmlXejg0dXZNbjNvYWVKZzQwb29kN3FxWmlNMzl3SnpUTkNBdXZtSFVVenI2eDRWQWJxalE0YmtSV3hIcnp2RHBmUGVZVnRJeXdYdDdn?oc=5",
//        "https://news.google.com/rss/articles/CBMijwFBVV95cUxQZk9NZ0JudkgxWDBRY0VJclhfXzY2Q1E2V0JfOEFMT3pBSDkwdWZERHZnajF0Y0lSR3YwN2RQblFUZU96YW4xWHR1SU83MWljNGU0cEdBaTZJLUFhZmJjdEVLVEhOdkEzNV9fWk8zSXlFRHpkUzd4UlotSGRIaVNTRG5VdXIxR0ZudGc3S0FBa9IBkgFBVV95cUxQTWgyREdtLW0yd3hxbkx5UTRGM3R5SkVZajZBRGVyRkNCcUN3Q1Q1TTk3VG5QWUdxby12OW5BLXB2U2UxMWZ4YVAySmg1em1PaGpwZEk0TUVGeTV0NVROVFVJdzFRR00tbVRtcmhubnhPcHZqTW1BVlJyZVVpTEZYN3llX1Y3bllFV0FJVWt2Z2VVUQ?oc=5"
//    )
//
//    for (interval in intervals) {
//        println("=== interval: $interval ms Started ===")
//        for (url in rssUrls) {
//            val response = decoder.decodeGoogleNewsUrl(url, interval)
//            when (response["status"]) {
//                true -> {
//                    val decodedUrl = response["decodedUrl"] as? String
//                    if (decodedUrl != null) {
//                        println("Decoded URL: $decodedUrl")
//                    } else {
//                        println("Error: Invalid URL format in response: $response")
//                    }
//                }
//                false -> {
//                    val message = response["message"] as? String ?: "Unknown error"
//                    println("Error: $message")
//                    println("=== interval: $interval Fail ===")
//                    return@runBlocking
//                }
//                else -> {
//                    println("Error: Invalid response format: $response")
//                    println("=== interval: $interval Fail ===")
//                    return@runBlocking
//                }
//            }
//        }
//        println("=== interval: $interval Success ===")
//    }
//    println("=== End of Multiple Requests ===")
//}
//
//fun main() = runBlocking {
//    mainSingleRequest()
//    delay(1000L)
//    mainMultipleRequests()
//}