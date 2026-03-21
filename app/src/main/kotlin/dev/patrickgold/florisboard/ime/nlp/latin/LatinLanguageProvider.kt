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
import dev.patrickgold.florisboard.ime.dictionary.DictionaryManager
import dev.patrickgold.florisboard.ime.dictionary.FREQUENCY_DEFAULT
import dev.patrickgold.florisboard.ime.dictionary.FREQUENCY_MAX
import dev.patrickgold.florisboard.ime.dictionary.UserDictionaryEntry
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
// Trie — usado solo para el diccionario base
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

    override val providerId = ProviderId

    override suspend fun create() {}

    override suspend fun preload(subtype: Subtype) = withContext(Dispatchers.IO) {
        DictionaryManager.default().loadUserDictionariesIfNecessary()

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
        val locale = subtype.primaryLocale

        if (inputText.isBlank()) return emptyList()

        // Leer estado de mayúsculas directamente del teclado
        val capMode = when (keyboardManager.activeState.inputShiftState) {
            InputShiftState.CAPS_LOCK -> CapMode.ALL
            InputShiftState.SHIFTED_MANUAL,
            InputShiftState.SHIFTED_AUTOMATIC -> CapMode.FIRST
            else -> {
            if (rawInput.isNotEmpty() && rawInput.first().isUpperCase()) CapMode.FIRST
            else CapMode.NONE
        }
        }

        // 1. Buscar en diccionario de usuario nativo
        val userMatches = withContext(Dispatchers.IO) {
            DictionaryManager.default().queryUserDictionary(inputText, locale)
        }

        // 2. Buscar en diccionario base (Trie)
        val baseMatches = baseTrie.searchByPrefix(inputText, maxCandidateCount)

        // 3. Combinar: usuario tiene prioridad, sin duplicados
        val seen = mutableSetOf<String>()
        val combined = mutableListOf<Pair<String, Int>>()

        for (candidate in userMatches) {
            val word = candidate.text.toString()
            if (seen.add(word)) combined.add(word to (candidate.confidence * 255).toInt())
        }
        for ((word, freq) in baseMatches) {
            if (seen.add(word)) combined.add(word to freq)
            if (combined.size >= maxCandidateCount) break
        }

        // 4. Sin sugerencias: mostrar palabra actual tal cual
        if (combined.isEmpty()) {
            return listOf(
                WordSuggestionCandidate(
                    text = inputText,
                    confidence = 0.0,
                    isEligibleForAutoCommit = false,
                    isEligibleForUserRemoval = false,
                    sourceProvider = this@LatinLanguageProvider,
                )
            )
        }

        // 5. Reordenar: centro=1, izquierda=2, derecha=3
        val top = combined.take(maxCandidateCount)
        val reordered = if (top.size >= 2) listOf(top[1], top[0]) + top.drop(2) else top

        // 6. Palabra desconocida al centro si no hay match exacto
        val hasExactMatch = reordered.any { it.first == inputText }
        val finalList = if (!hasExactMatch && reordered.isNotEmpty()) {
            listOf(reordered[0], inputText to 0) + reordered.drop(1)
        } else {
            reordered
        }

        return finalList.take(maxCandidateCount).map { (word, freq) ->
            WordSuggestionCandidate(
                text = word,
                confidence = freq / 255.0,
                isEligibleForAutoCommit = false,
                isEligibleForUserRemoval = false,
                sourceProvider = this@LatinLanguageProvider,
            )
        }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        val word = candidate.text.toString().lowercase().trim()
        if (word.length < 2) return
        withContext(Dispatchers.IO) {
            val dao = DictionaryManager.default().florisUserDictionaryDao() ?: return@withContext
            val locale = subtype.primaryLocale.localeTag()
            val existing = dao.queryExactFuzzyLocale(word, subtype.primaryLocale)
            if (existing.isNotEmpty()) {
                val entry = existing.first()
                val newFreq = minOf(entry.freq + 10, FREQUENCY_MAX)
                dao.update(entry.copy(freq = newFreq))
            } else {
                dao.insert(UserDictionaryEntry(
                    id = 0,
                    word = word,
                    freq = FREQUENCY_DEFAULT,
                    locale = locale,
                    shortcut = null,
                ))
            }
        }
        flogDebug { "Word accepted and recorded: $word" }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        val word = candidate.text.toString().lowercase().trim()
        return withContext(Dispatchers.IO) {
            val dao = DictionaryManager.default().florisUserDictionaryDao() ?: return@withContext false
            val existing = dao.queryExactFuzzyLocale(word, subtype.primaryLocale)
            if (existing.isNotEmpty()) {
                dao.delete(existing.first())
                flogDebug { "Word removed from user dict: $word" }
                true
            } else {
                false
            }
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
