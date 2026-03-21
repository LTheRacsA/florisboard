/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import android.content.Context
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText
import org.florisboard.lib.kotlin.guardedByLock
import java.io.File

// ──────────────────────────────────────────────
// Capitalización
// ──────────────────────────────────────────────
private enum class CapMode { NONE, FIRST, ALL }

private fun applyCapMode(word: String, mode: CapMode): String {
    return when (mode) {
        CapMode.NONE -> word
        CapMode.FIRST -> word.replaceFirstChar { it.uppercase() }
        CapMode.ALL -> word.uppercase()
    }
}

private fun detectCapMode(composingText: String): CapMode {
    if (composingText.isBlank()) return CapMode.NONE
    val allUpper = composingText.all { !it.isLetter() || it.isUpperCase() }
    val firstUpper = composingText.first().isUpperCase()
    return when {
        allUpper && composingText.length > 1 -> CapMode.ALL
        firstUpper -> CapMode.FIRST
        else -> CapMode.NONE
    }
}

// ──────────────────────────────────────────────
// Trie
// ──────────────────────────────────────────────
private class TrieNode {
    val children = HashMap<Char, TrieNode>()
    var frequency: Int = 0
    var isWord: Boolean = false
}

private class Trie {
    private val root = TrieNode()

    fun insert(word: String, frequency: Int) {
        var node = root
        for (ch in word) {
            node = node.children.getOrPut(ch) { TrieNode() }
        }
        node.isWord = true
        node.frequency = frequency
    }

    fun remove(word: String) {
        fun removeHelper(node: TrieNode, word: String, depth: Int): Boolean {
            if (depth == word.length) {
                if (!node.isWord) return false
                node.isWord = false
                node.frequency = 0
                return node.children.isEmpty()
            }
            val ch = word[depth]
            val child = node.children[ch] ?: return false
            val shouldDelete = removeHelper(child, word, depth + 1)
            if (shouldDelete) node.children.remove(ch)
            return !node.isWord && node.children.isEmpty()
        }
        removeHelper(root, word, 0)
    }

    fun searchByPrefix(prefix: String, maxResults: Int): List<Pair<String, Int>> {
        var node = root
        for (ch in prefix) {
            node = node.children[ch] ?: return emptyList()
        }
        val results = mutableListOf<Pair<String, Int>>()
        collectWords(node, prefix, results, maxResults)
        return results.sortedByDescending { it.second }
    }

    private fun collectWords(
        node: TrieNode,
        current: String,
        results: MutableList<Pair<String, Int>>,
        maxResults: Int,
    ) {
        if (results.size >= maxResults) return
        if (node.isWord) results.add(current to node.frequency)
        for ((ch, child) in node.children) {
            if (results.size >= maxResults) return
            collectWords(child, current + ch, results, maxResults)
        }
    }
}

// ──────────────────────────────────────────────
// UserDictionary
// ──────────────────────────────────────────────
private class UserDictionary(private val context: Context) {
    private val file = File(context.filesDir, "user_dict.json")
    private val wordFreqs = mutableMapOf<String, Int>()
    private val baseWords = mutableSetOf<String>()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Int.serializer())

    fun loadBaseWords(words: Set<String>) {
        baseWords.addAll(words)
    }

    fun load() {
        if (!file.exists()) return
        try {
            val raw = file.readText(Charsets.UTF_8)
            val map = json.decodeFromString(serializer, raw)
            wordFreqs.clear()
            wordFreqs.putAll(map)
        } catch (e: Exception) {
            flogDebug { "UserDictionary load error: ${e.message}" }
        }
    }

    fun save() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer, wordFreqs), Charsets.UTF_8)
        } catch (e: Exception) {
            flogDebug { "UserDictionary save error: ${e.message}" }
        }
    }

    fun recordWord(word: String) {
        val normalized = word.trim().lowercase()
        if (normalized.length < 2) return
        val isValidated = baseWords.contains(normalized)
        val increment = if (isValidated) 10 else 5
        val current = wordFreqs.getOrDefault(normalized, 0)
        wordFreqs[normalized] = current + increment
        save()
    }

    fun getFrequency(word: String): Int {
        return wordFreqs.getOrDefault(word, 0)
    }

    fun removeWord(word: String): Boolean {
        val normalized = word.trim().lowercase()
        if (!wordFreqs.containsKey(normalized)) return false
        wordFreqs.remove(normalized)
        save()
        return true
    }

    fun buildTrie(): Trie {
        val trie = Trie()
        for ((word, freq) in wordFreqs) {
            trie.insert(word, freq)
        }
        return trie
    }
}

// ──────────────────────────────────────────────
// LatinLanguageProvider
// ──────────────────────────────────────────────
class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        const val ProviderId = "org.florisboard.nlp.providers.latin"
    }

    private val appContext by context.appContext()
    private val wordData = guardedByLock { mutableMapOf<String, Int>() }
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())
    private val baseTrie = Trie()
    private val userDictionary = UserDictionary(context)
    private var userTrie = Trie()

    override val providerId = ProviderId

    override suspend fun create() {}

    override suspend fun preload(subtype: Subtype) = withContext(Dispatchers.IO) {
        wordData.withLock { data ->
            if (data.isEmpty()) {
                val externalFile = File(
                    android.os.Environment.getExternalStorageDirectory(),
                    "FlorisBoard/dict/data.json"
                )
                val rawData = if (externalFile.exists()) {
                    externalFile.readText(Charsets.UTF_8)
                } else {
                    appContext.assets.readText("ime/dict/data.json")
                }
                val jsonData = Json.decodeFromString(wordDataSerializer, rawData)
                data.putAll(jsonData)

                data.entries
                    .sortedByDescending { it.value }
                    .forEach { baseTrie.insert(it.key, it.value) }

                userDictionary.loadBaseWords(data.keys)
                userDictionary.load()
                userTrie = userDictionary.buildTrie()
            }
        }
    }

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        return when (word.lowercase()) {
            "typo" -> SpellingResult.typo(arrayOf("typo1", "typo2", "typo3"))
            "gerror" -> SpellingResult.grammarError(arrayOf("grammar1", "grammar2", "grammar3"))
            else -> SpellingResult.validWord()
        }
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        val rawInput = content.composingText.trim()
        val inputText = rawInput.lowercase()
        val capMode = detectCapMode(rawInput.toString())

        // Sin texto: mostrar palabras frecuentes del usuario o del base
        if (inputText.isBlank()) {
            val defaults = userTrie.searchByPrefix("", maxCandidateCount).ifEmpty {
                baseTrie.searchByPrefix("de", maxCandidateCount)
            }
            val reordered = if (defaults.size >= 2) listOf(defaults[1], defaults[0]) + defaults.drop(2) else defaults
            return reordered.map { (word, freq) ->
                WordSuggestionCandidate(
                    text = applyCapMode(word, capMode),
                    confidence = freq / 255.0,
                    isEligibleForAutoCommit = false,
                    isEligibleForUserRemoval = false,
                    sourceProvider = this@LatinLanguageProvider,
                )
            }
        }

        val userMatches = userTrie.searchByPrefix(inputText, maxCandidateCount)
        val baseMatches = baseTrie.searchByPrefix(inputText, maxCandidateCount)

        val seen = mutableSetOf<String>()
        val combined = mutableListOf<Pair<String, Int>>()

        for ((word, freq) in userMatches) {
            if (seen.add(word)) combined.add(word to freq)
        }
        for ((word, freq) in baseMatches) {
            if (seen.add(word)) combined.add(word to freq)
            if (combined.size >= maxCandidateCount) break
        }

        // Sin sugerencias: mostrar la palabra actual tal cual
        if (combined.isEmpty()) {
            return listOf(
                WordSuggestionCandidate(
                    text = applyCapMode(inputText, capMode),
                    confidence = 0.0,
                    isEligibleForAutoCommit = false,
                    isEligibleForUserRemoval = false,
                    sourceProvider = this@LatinLanguageProvider,
                )
            )
        }

        // Reordenar: centro=1, izquierda=2, derecha=3
        val top = combined.take(maxCandidateCount)
        val reordered = if (top.size >= 2) listOf(top[1], top[0]) + top.drop(2) else top

        // Palabra desconocida al centro si no hay match exacto
        val hasExactMatch = reordered.any { it.first == inputText }
        val finalList = if (!hasExactMatch && reordered.isNotEmpty()) {
            val unknown = inputText to 0
            listOf(reordered[0], unknown) + reordered.drop(1)
        } else {
            reordered
        }

        return finalList.take(maxCandidateCount).map { (word, freq) ->
            val isUserWord = userDictionary.getFrequency(word.trim().lowercase()) > 0
            WordSuggestionCandidate(
                text = applyCapMode(word, capMode),
                confidence = freq / 255.0,
                isEligibleForAutoCommit = false,
                isEligibleForUserRemoval = isUserWord,
                sourceProvider = this@LatinLanguageProvider,
            )
        }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        val word = candidate.text.toString().lowercase()
        withContext(Dispatchers.IO) {
            userDictionary.recordWord(word)
            userTrie = userDictionary.buildTrie()
        }
        flogDebug { "Word accepted and recorded: $word" }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        val word = candidate.text.toString().lowercase()
        return withContext(Dispatchers.IO) {
            val removed = userDictionary.removeWord(word)
            if (removed) {
                userTrie = userDictionary.buildTrie()
                flogDebug { "Word removed from user dict: $word" }
            }
            removed
        }
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return wordData.withLock { it.keys.toList() }
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return wordData.withLock { it.getOrDefault(word, 0) / 255.0 }
    }

    override suspend fun destroy() {}
}
