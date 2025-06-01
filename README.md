# GoogleNewsDecoder

This library allows you to decode Google News URLs, which typically look like `https://news.google.com/rss/articles/...`. It is based on the original Python script by https://github.com/SSujitX/google-news-url-decoder.

---

## Features

- Decode Google News URLs into original links
- Written in Kotlin, fully compatible with Android
- Simple API with a single primary method
- Requires Java 11

---

## Usage

```kotlin
import com.kote.gnewsdecoder.GoogleNewsDecoder

val decoder = GoogleNewsDecoder()
val result = decoder.decodeGoogleNewsUrl("https://news.google.com/rss/articles/CBMi...")

if (result["status"] == true) {
    val decodedUrl = result["decodedUrl"] as String
    // Use the decoded URL
    println(decodedUrl)
} else {
    val errorMessage = result["message"] as String
    // Handle the error
    println(errorMessage)
}
```
