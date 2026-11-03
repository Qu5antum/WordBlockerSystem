package com.example.wordblocker

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

class NoPasteEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    init {
        // Отключаем Autofill 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }

        // Отключаем подсказки и автопредложения клавиатуры
        inputType = inputType or EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    // Блокируем вставку через контекстное меню
    override fun onTextContextMenuItem(id: Int): Boolean {
        when (id) {
            android.R.id.paste,
            android.R.id.pasteAsPlainText -> {
                return true
            }
        }

        return super.onTextContextMenuItem(id)
    }

    // Перехватываем ввод через InputConnection
    override fun onCreateInputConnection(
        outAttrs: EditorInfo
    ): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
            ?: return null

        return object : InputConnectionWrapper(connection, true) {
            override fun commitText(
                text: CharSequence?,
                newCursorPosition: Int
            ): Boolean {
                if (text != null && text.length > 3) {
                    return false
                }

                return super.commitText(text, newCursorPosition)
            }
        }
    }
}
