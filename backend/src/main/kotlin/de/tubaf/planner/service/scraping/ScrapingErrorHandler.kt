package de.tubaf.planner.service.scraping

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ScrapingErrorHandler {

    private val logger = LoggerFactory.getLogger(ScrapingErrorHandler::class.java)

    /** Behandelt Scraping-Fehler mit Retry-Logik */
    fun <T> handleWithRetry(
        operation: () -> T,
        maxRetries: Int = 3,
        retryDelay: Long = 1000,
        operationName: String = "Scraping operation",
    ): T {
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                logger.debug("Attempting $operationName (attempt ${attempt + 1}/$maxRetries)")
                return operation()
            } catch (e: Exception) {
                lastException = e
                logger.warn("$operationName failed on attempt ${attempt + 1}: ${e.message}")

                if (attempt < maxRetries - 1) {
                    try {
                        Thread.sleep(retryDelay * (attempt + 1)) // Exponential backoff
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw ie
                    }
                }
            }
        }

        throw ScrapingException("$operationName failed after $maxRetries attempts", lastException)
    }

    /** Prüft ob eine Exception als wiederholbar eingestuft werden kann */
    fun isRetryableException(exception: Exception): Boolean = when {
        exception.message?.contains("timeout", ignoreCase = true) == true -> true
        exception.message?.contains("connection", ignoreCase = true) == true -> true
        exception.message?.contains("network", ignoreCase = true) == true -> true
        exception is java.net.SocketTimeoutException -> true
        exception is java.net.ConnectException -> true
        else -> false
    }
}

/** Custom Exception für Scraping-Fehler */
class ScrapingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
