package com.example.wordblocker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.min
import com.google.mlkit.nl.languageid.LanguageIdentification

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
        "com.google.android.googlequicksearchbox",
        "org.telegram.messenger"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // Всегда проверяем защиту настроек при любых изменениях окон
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            checkNodeInfoOfWordBlocker(packageName, event)
        }

        // Проверяем, разрешен ли пакет (браузер или поиск Google)
        if (!allowedBrowsers.contains(packageName)) {
            return
        }

        // Логика обработки ввода в зависимости от типа события
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val node = event.source
                if (node != null && node.isEditable) {
                    val text = node.text?.toString() ?: ""
                    if (text.isNotBlank()) {
                        // Проверяем на запрещенные слова
                        if (containsForbiddenWord(text)) {
                            blockBadSite(node)
                            return
                        }
                        // Проверяем язык через ML Kit
                        isAllowedLanguage(text) { isAllowed ->
                            if (!isAllowed) {
                                blockBadSite(node)
                            }
                        }
                    }
                }

                val root = rootInActiveWindow
                if (root != null && checkChromeInputFields(root)) {
                    return
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Тоже делаем общим для всех разрешенных браузеров
                val root = rootInActiveWindow
                if (root != null && checkChromeInputFields(root)) {
                    return
                }
            }
        }
    }

    private fun isAllowedLanguage(text: String, onResult: (Boolean) -> Unit) {
        if (text.length < 3) {
            onResult(true) // Слишком короткий текст (1-2 буквы) пропускаем
            return
        }

        val languageIdentifier = LanguageIdentification.getClient()
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { languageCode ->
                if (languageCode == "und") {
                    onResult(true) // Если язык не удалось распознать, на всякий случай не блокируем
                    return@addOnSuccessListener
                }

                // Список строго разрешенных языков
                val allowedLanguages = setOf("en", "ru", "tr")

                // Если язык НЕ входит в разрешенные (например, "es" - испанский, "zh" - китайский), то блокируем
                val isAllowed = allowedLanguages.contains(languageCode)
                onResult(isAllowed)
            }
            .addOnFailureListener {
                onResult(true) // При ошибке не блокируем
            }
    }

    // Безопасный обход дерева Chrome: ищет только видимые поля ввода (адресная строка и формы поиска)
    private fun checkChromeInputFields(
        node: AccessibilityNodeInfo?,
        depth: Int = 0
    ): Boolean {
        if (node == null || depth > 15) return false

        if (node.isVisibleToUser && (node.isEditable || node.className?.toString()?.contains("EditText") == true)) {
            val text = node.text?.toString() ?: ""

            if (text.isNotBlank()) {
                if (containsForbiddenWord(text)) {
                    blockBadSite(node)
                    return true
                }

                // Проверка языка в рекурсивном обходе
                isAllowedLanguage(text) { isAllowed ->
                    if (!isAllowed) {
                        blockBadSite(node)
                    }
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

    // Функция жесткой блокировки при обнаружении запрещенного сайта
    private fun blockBadSite(node: AccessibilityNodeInfo?) {
        // Если узел адресной строки доступен для редактирования — очищаем его
        if (node != null && node.isEditable) {
            clearTextField(node)
        }
        // Возвращаем пользователя назад из истории браузера
        performGlobalAction(GLOBAL_ACTION_BACK)
        // Сворачиваем браузер на рабочий стол
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // Нормализация текста: полностью удаляем пробелы, спецсимволы и заменяем похожие знаки
    private fun normalize(text: String): String {
        return text.lowercase()
            .replace('0', 'o')
            .replace('1', 'i')
            .replace('3', 'e')
            .replace('4', 'a')
            .replace('5', 's')
            .replace('7', 't')
            .replace('@', 'a')
            .replace('$', 's')
            .replace(Regex("[^a-zа-яё0-9]"), "") // Удаляем все, включая пробелы и точки
    }

    // Расстояние Левенштейна для поиска опечаток
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

    // Проверка с учетом удаления пробелов и защиты от ложных срабатываний
    private fun containsForbiddenWord(text: String): Boolean {
        val normalizedText = normalize(text)
        if (normalizedText.isEmpty()) return false

        for (word in forbiddenWords) {
            val normalizedWord = normalize(word)
            if (normalizedWord.isEmpty()) continue

            if (normalizedText.contains(normalizedWord)) {
                if (normalizedWord.length <= 3) {
                    // Защита для коротких слов: проверяем, было ли это слово самостоятельным в оригинале
                    val wordsOriginal = text.lowercase().split(Regex("[^a-zа-яё0-9]+"))
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
                    if (i >= endIndex) continue
                    val sub = normalizedText.substring(i, endIndex)

                    if (levenshtein(sub, normalizedWord) <= maxErrors) {
                        return true
                    }
                }
            }
        }
        return false
    }

    // Функция для программной очистки текста в поле ввода
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

            // Вылет на главный экран если зайти на удаления или отключения приложения для устройстве на турецком или английском языке
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