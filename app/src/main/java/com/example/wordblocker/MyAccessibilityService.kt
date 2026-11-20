package com.example.wordblocker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.min
import com.google.mlkit.nl.languageid.LanguageIdentification
import java.util.Locale

class MyAccessibilityService : AccessibilityService() {
    // Список запрещенных слов и фраз
    private val forbiddenWords = hashSetOf(
        "bad_word1", "bad_word2"
    )

    // Множество пакетов разрешенных браузеров и поиска
    private val allowedBrowsers = setOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.yandex.browser",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.brave.browser",
        "com.google.android.googlequicksearchbox",
        "org.telegram.messenger",
        "com.google.android.apps.translate",
        "ru.yandex.translate"
    )

    // Разрешенные языки на уровне класса
    private val allowedLanguages = setOf("en", "ru", "tr")

    // Переменная для задержки (debounce) проверки языка
    private var languageCheckRunnable: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            checkNodeInfoOfWordBlocker(packageName, event)
        }

        if (!allowedBrowsers.contains(packageName)) {
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val node = event.source
                if (node != null && node.isEditable) {
                    val text = node.text?.toString() ?: ""
                    if (text.isNotBlank()) {
                        // 1. Сразу проверяем запрещенные слова по свежему тексту
                        if (containsForbiddenWord(text)) {
                            blockBadSite(node)
                            return
                        }

                        // 2. Отложенная комбинированная проверка языка
                        languageCheckRunnable?.let { handler.removeCallbacks(it) }
                        languageCheckRunnable = Runnable {
                            isAllowedLanguage(text) { isAllowed ->
                                if (!isAllowed) {
                                    val freshRoot = rootInActiveWindow
                                    val activeNode = if (freshRoot != null) {
                                        findCurrentEditableNode(freshRoot)
                                    } else {
                                        null
                                    }

                                    blockBadSite(activeNode ?: node)
                                }
                            }
                        }
                        handler.postDelayed(languageCheckRunnable!!, 350)
                    }
                }

                val root = rootInActiveWindow
                if (root != null && checkChromeInputFields(root)) {
                    return
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val root = rootInActiveWindow
                if (root != null && checkChromeInputFields(root)) {
                    return
                }
            }
        }
    }

    // Рекурсивный поиск текущего активного поля ввода на экране
    private fun findCurrentEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && node.isEditable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findCurrentEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    // Проверка языка (ML Kit с пробелами)
    private fun isAllowedLanguage(
        text: String,
        onResult: (Boolean) -> Unit
    ) {
        val cleanText = text.trim()

        // Текст без букв не нуждается в определении языка.
        val lettersCount = cleanText.count { it.isLetter() }

        if (lettersCount == 0) {
            onResult(true)
            return
        }

        // Короткие фразы без совпадений со словарём пока не передаём в ML Kit.
        if (lettersCount < 5) {
            onResult(true)
            return
        }

        val languageIdentifier = LanguageIdentification.getClient()

        // Важно: сохраняем пробелы между словами.
        languageIdentifier.identifyPossibleLanguages(cleanText)
            .addOnSuccessListener { languages ->

                if (languages.isEmpty()) {
                    onResult(true)
                    return@addOnSuccessListener
                }

                val bestMatch = languages.maxByOrNull { it.confidence }

                if (bestMatch == null) {
                    onResult(true)
                    return@addOnSuccessListener
                }

                val languageCode = bestMatch.languageTag
                val confidence = bestMatch.confidence

                android.util.Log.d(
                    "WordBlocker",
                    "ML Kit: language=$languageCode, confidence=$confidence"
                )

                // Если ML Kit не уверен, используем безопасный режим.
                if (languageCode == "und" || confidence < 0.5f) {
                    onResult(true)
                    return@addOnSuccessListener
                }

                // Разрешаем только английский, русский и турецкий.
                val isAllowed = languageCode in allowedLanguages

                onResult(isAllowed)
            }
            .addOnFailureListener { exception ->
                android.util.Log.e(
                    "WordBlocker",
                    "Ошибка определения языка",
                    exception
                )

                onResult(true)
            }
    }

    // Безопасный обход дерева элементов
    private fun checkChromeInputFields(
        node: AccessibilityNodeInfo?,
        depth: Int = 0
    ): Boolean {
        if (node == null || depth > 15) return false

        if (node.isVisibleToUser && (node.isEditable || node.className?.toString()?.contains("EditText") == true)) {
            val text = node.text?.toString() ?: ""
            val description = node.contentDescription?.toString() ?: ""

            if (text.isNotBlank()) {
                if (containsForbiddenWord(text)) {
                    blockBadSite(node)
                    return true
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = checkChromeInputFields(child, depth + 1)
            if (found) return true
        }
        return false
    }

    // Функция жесткой блокировки
    private fun blockBadSite(node: AccessibilityNodeInfo?) {
        if (node != null && node.isEditable) {
            clearTextField(node)
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // Нормализация текста для поиска запрещенных слов
    private fun normalize(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace('ı', 'i')
            .replace('@', 'a')
            .replace('$', 's')
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
    }

    // Расстояние Левенштейна   
    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }

        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1

                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }

        return dp[a.length][b.length]
    }

    // Проверка запрещенных слов
    private fun containsForbiddenWord(text: String): Boolean {
        val normalizedText = normalize(text)
        if (normalizedText.isEmpty()) return false

        for (word in forbiddenWords) {
            val normalizedWord = normalize(word)
            if (normalizedWord.isEmpty()) continue

            if (normalizedText.contains(normalizedWord)) {
                if (normalizedWord.length <= 3) {
                    val wordsOriginal = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
                    if (wordsOriginal.any { normalize(it) == normalizedWord }) {
                        return true
                    }
                } else {
                    return true
                }
            }

            // Нечеткий поиск (опечатки) для длинных слов
            if (normalizedWord.length > 4 && normalizedText.length >= normalizedWord.length) {
                val maxErrors = if (normalizedWord.length <= 7) 1 else 2
                val windowSize = normalizedWord.length

                val limit = (normalizedText.length - windowSize + maxErrors).coerceAtLeast(0)
                for (i in 0..limit) {
                    val endIndex = min(i + windowSize + maxErrors, normalizedText.length)
                    if (i >= endIndex || i < 0) continue
                    val sub = normalizedText.substring(i, endIndex)

                    if (levenshtein(sub, normalizedWord) <= maxErrors) {
                        return true
                    }
                }
            }
        }
        return false
    }

    // Очистка поля ввода
    private fun clearTextField(node: AccessibilityNodeInfo) {
        if (node.isEditable) {
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
    }

    private fun checkNodeInfoOfWordBlocker(packageName: String, event: AccessibilityEvent) {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val isProtectionDisabled = prefs.getBoolean("disable_settings_protection", false)

        if (isProtectionDisabled) return

        if (packageName == "com.android.settings") {
            val rootNode = rootInActiveWindow ?: event.source ?: return

            val isTargetAppPresent = findTextInNode(rootNode, "WordBlockerSystemCore") ||
                    findTextInNode(rootNode, "System Android Core")

            val isProtectedScreen = findTextInNode(rootNode, "shortcut") ||
                    findTextInNode(rootNode, "kısayolu") ||
                    findTextInNode(rootNode, "Admin") ||
                    findTextInNode(rootNode, "yönetimi") ||
                    findTextInNode(rootNode,"devre") ||
                    findTextInNode(rootNode, "kilitleme")

            if (isTargetAppPresent && isProtectedScreen) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    private fun findTextInNode(node: AccessibilityNodeInfo?, targetText: String): Boolean {
        if (node == null) return false

        val text = node.text?.toString() ?: ""
        if (text.contains(targetText, ignoreCase = true)) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findTextInNode(child, targetText)) {
                return true
            }
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
    }
}