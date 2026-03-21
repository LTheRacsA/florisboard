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
// Trie: búsqueda instantánea por prefijo
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
// UserDictionary: aprendizaje personalizado
// ──────────────────────────────────────────────
private class UserDictionary(context: Context) {
    data class Entry(val freq: Int, val validated: Boolean)

    private val file = File(
        android.os.Environment.getExternalStorageDirectory(),
        "FlorisBoard/dict/user_dict.json"
    )
    private val entries = mutableMapOf<String, Entry>()
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
            entries.clear()
            map.forEach { (word, freq) ->
                entries[word] = Entry(freq, baseWords.contains(word))
            }
        } catch (e: Exception) {
            flogDebug { "UserDictionary load error: ${e.message}" }
        }
    }

    fun save() {
        try {
            file.parentFile?.mkdirs()
            val map = entries.mapValues { it.value.freq }
            file.writeText(json.encodeToString(serializer, map), Charsets.UTF_8)
        } catch (e: Exception) {
            flogDebug { "UserDictionary save error: ${e.message}" }
        }
    }

    fun recordWord(word: String) {
        val normalized = word.trim().lowercase()
        if (normalized.length < 2) return
        val existing = entries[normalized]
        val isValidated = baseWords.contains(normalized)
        val increment = if (isValidated) 10 else 5
        val currentFreq = existing?.freq ?: 0
        entries[normalized] = Entry(currentFreq + increment, isValidated)
        save()
    }

    fun getFrequency(word: String): Int {
        return entries[word]?.freq ?: 0
    }

    fun getUserTrie(maxResults: Int = 500): Trie {
        val trie = Trie()
        entries.forEach { (word, entry) -> trie.insert(word, entry.freq) }
        return trie
    }
}

// ──────────────────────────────────────────────
// LatinLanguageProvider principal
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
                // 1. Cargar diccionario base
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

                // 2. Construir Trie base (ordenado por frecuencia desc)
                data.entries
                    .sortedByDescending { it.value }
                    .forEach { baseTrie.insert(it.key, it.value) }

                // 3. Cargar diccionario de usuario
                userDictionary.loadBaseWords(data.keys)
                userDictionary.load()
                userTrie = userDictionary.getUserTrie()
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
        val inputText = content.composingText.trim().lowercase()

        // Sin texto: mostrar palabras más frecuentes del usuario o del base
        if (inputText.isBlank()) {
            val defaults = userTrie.searchByPrefix("", maxCandidateCount).ifEmpty {
                baseTrie.searchByPrefix("de", maxCandidateCount)
            }
            return defaults.map { (word, freq) ->
                WordSuggestionCandidate(
                    text = word,
                    confidence = freq / 255.0,
                    isEligibleForAutoCommit = false,
                    sourceProvider = this@LatinLanguageProvider,
                )
            }
        }

        // Buscar en Trie de usuario primero, luego en base
        val userMatches = userTrie.searchByPrefix(inputText, maxCandidateCount)
        val baseMatches = baseTrie.searchByPrefix(inputText, maxCandidateCount)

        // Combinar: usuario tiene prioridad, sin duplicados
        val seen = mutableSetOf<String>()
        val combined = mutableListOf<Pair<String, Int>>()

        for ((word, freq) in userMatches) {
            if (seen.add(word)) combined.add(word to freq)
        }
        for ((word, freq) in baseMatches) {
            if (seen.add(word)) combined.add(word to freq)
            if (combined.size >= maxCandidateCount) break
        }

        return combined.take(maxCandidateCount).map { (word, freq) ->
            WordSuggestionCandidate(
                text = word,
                confidence = freq / 255.0,
                isEligibleForAutoCommit = false,
                sourceProvider = this@LatinLanguageProvider,
            )
        }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // Registrar palabra aceptada en diccionario de usuario
        val word = candidate.text.toString()
        withContext(Dispatchers.IO) {
            userDictionary.recordWord(word)
            userTrie = userDictionary.getUserTrie()
        }
        flogDebug { "Word accepted and recorded: $word" }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        flogDebug { candidate.toString() }
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return wordData.withLock { it.keys.toList() }
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return wordData.withLock { it.getOrDefault(word, 0) / 255.0 }
    }

    override suspend fun destroy() {}
}
