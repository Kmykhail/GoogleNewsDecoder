# GoogleNewsDecoder

This library allows you to decode Google News URLs, which typically look like `https://news.google.com/rss/articles/...`. 
It is based on the original Python script by [SSujitX](https://github.com/SSujitX/google-news-url-decoder), rewritten in Kotlin for Android

---

## Features

- Decode Google News URLs into original links
- Written in Kotlin, fully compatible with Android
- Simple API with a single primary method
- Requires Java 11
- Uses Kotlin Coroutines for safe background operations

---

## Installation

Add the JitPack repository to your **root** `settings.gradle.kts` or `build.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}
```
Then add the dependency in your module build.gradle.kts:
```kotlin
// TAG - please check the last tag version
dependencies {
    implementation("com.github.Kmykhail:GoogleNewsDecoder:$TAG")
}
```

## Usage

This library uses suspend functions. You must call it from within a coroutine (e.g., using lifecycleScope, viewModelScope, or LaunchedEffect).
```kotlin
import com.kote.gnewsdecoder.GoogleNewsDecoder

lifecycleScope.launch {
    val decoder = GoogleNewsDecoder()
    val result = decoder.decodeGoogleNewsUrl("https://news.google.com/rss/articles/CBMi...")

    if (result["status"] == true) {
        val decodedUrl = result["decodedUrl"] as String
        println(decodedUrl)
    } else {
        val errorMessage = result["message"] as String
        println(errorMessage)
    }
}
```
The function decodeGoogleNewsUrl(...) returns a Map<String, Any> with:

 status: Boolean - indicates success

 decodedUrl: String - the original article URL

 message: String - error description (if failed)

# Testing locally
You can test the decoder in a simple main() function like:
```kotlin
fun main() = runBlocking {
    println("=== Running Library Test ===")
    val decoder = GoogleNewsDecoder()
    val result = decoder.decodeGoogleNewsUrl("https://news.google.com/rss/articles/CBMi...")

    println(result)
}
```
