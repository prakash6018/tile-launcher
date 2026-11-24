package com.tilelauncher.live

import android.content.Context
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.summarization.SummarizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Summarises the Me tile's digest with on-device Gemini Nano (ML Kit GenAI). Nothing leaves
 * the phone. Returns null whenever the model isn't usable (unsupported device, still
 * downloading, non-English), and the tile shows the plain digest instead.
 */
class MeSummarizer(private val context: Context) {

    private val client: Summarizer? by lazy {
        runCatching {
            Summarization.getClient(
                SummarizerOptions.builder(context)
                    .setInputType(SummarizerOptions.InputType.ARTICLE)
                    .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
                    .setLanguage(SummarizerOptions.Language.ENGLISH)
                    .build()
            )
        }.getOrNull()
    }

    @Volatile private var status: Int? = null
    @Volatile private var downloading = false
    private var lastInput: String? = null
    private var lastSummary: List<String>? = null

    suspend fun summarize(text: String): List<String>? = withContext(Dispatchers.IO) {
        if (Locale.getDefault().language != "en" || text.isBlank()) return@withContext null
        if (text == lastInput) return@withContext lastSummary
        val summarizer = client ?: return@withContext null
        val current = status ?: runCatching { summarizer.checkFeatureStatus().get() }.getOrNull()?.also { status = it }
        when (current) {
            FeatureStatus.AVAILABLE -> Unit
            FeatureStatus.DOWNLOADABLE -> {
                download(summarizer)
                return@withContext null
            }
            else -> return@withContext null
        }
        val summary = runCatching { summarizer.runInference(SummarizationRequest.builder(text).build()).get().summary }
            .getOrNull() ?: return@withContext null
        val bullets = summary.lines()
            .map { it.trim().trimStart('*', '•', '-', ' ').trim() }
            .filter { it.isNotEmpty() }
            .take(3)
        lastInput = text
        lastSummary = bullets
        bullets
    }

    private fun download(summarizer: Summarizer) {
        if (downloading) return
        downloading = true
        summarizer.downloadFeature(object : DownloadCallback {
            override fun onDownloadStarted(bytesToDownload: Long) = Unit
            override fun onDownloadProgress(totalBytesDownloaded: Long) = Unit
            override fun onDownloadCompleted() {
                status = FeatureStatus.AVAILABLE
                downloading = false
            }
            override fun onDownloadFailed(e: GenAiException) {
                status = FeatureStatus.UNAVAILABLE
                downloading = false
            }
        })
    }

    fun close() {
        runCatching { client?.close() }
    }
}
