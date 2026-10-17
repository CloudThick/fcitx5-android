/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.annotation.Keep
import androidx.core.view.allViews
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.popup.PopupAction
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class TextKeyboard(
    context: Context,
    theme: Theme
) : BaseKeyboard(context, theme, Layout) {

    enum class CapsState { None, Once, Lock }

    companion object {
        const val Name = "Text"

        /**
         * WeChat-style layout. Each letter carries an English and a Chinese swipe symbol;
         * the Chinese one is used (and displayed) while Chinese punctuation is active.
         */
        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                DualSymbolAlphabetKey("Q", "1"),
                DualSymbolAlphabetKey("W", "2"),
                DualSymbolAlphabetKey("E", "3"),
                DualSymbolAlphabetKey("R", "4"),
                DualSymbolAlphabetKey("T", "5"),
                DualSymbolAlphabetKey("Y", "6"),
                DualSymbolAlphabetKey("U", "7"),
                DualSymbolAlphabetKey("I", "8"),
                DualSymbolAlphabetKey("O", "9"),
                DualSymbolAlphabetKey("P", "0")
            ),
            listOf(
                DualSymbolAlphabetKey("A", "-", "-"),
                DualSymbolAlphabetKey("S", "/", "/"),
                DualSymbolAlphabetKey("D", ":", "："),
                DualSymbolAlphabetKey("F", ";", "；"),
                DualSymbolAlphabetKey("G", "(", "（"),
                DualSymbolAlphabetKey("H", ")", "）"),
                // like WeChat: full-width ～ in Chinese mode
                DualSymbolAlphabetKey("J", "~", "～"),
                DualSymbolAlphabetKey("K", "\"", "“"),
                DualSymbolAlphabetKey("L", "'", "”")
            ),
            listOf(
                CapsKey(),
                DualSymbolAlphabetKey("Z", "@", "@"),
                DualSymbolAlphabetKey("X", ".", "。"),
                DualSymbolAlphabetKey("C", "#", "#"),
                DualSymbolAlphabetKey("V", "&", "、"),
                DualSymbolAlphabetKey("B", "?", "？"),
                DualSymbolAlphabetKey("N", "!", "！"),
                DualSymbolAlphabetKey("M", "_", "…"),
                BackspaceKey()
            ),
            listOf(
                LayoutSwitchKey("123", "", 0.15f),
                WeChatCommaKey(),
                SpaceKey(),
                LangToggleKey(),
                TextReturnKey()
            )
        )

        /** English swipe symbol -> Chinese swipe symbol, derived from [Layout]. */
        private val ZhSymbols: Map<String, String> = buildMap {
            // comma key's main label
            put(",", "，")
            Layout.flatten().forEach { def ->
                def.behaviors.forEach { b ->
                    if (b is KeyDef.Behavior.Swipe && b.action is KeyAction.ModeSymbolAction) {
                        put(b.action.en, b.action.zh)
                    }
                }
            }
        }
    }

    val caps: ImageKeyView by lazy { findViewById(R.id.button_caps) }
    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val lang: AltTextKeyView by lazy { findViewById(R.id.button_lang) }
    val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    val `return`: TextKeyView by lazy { findViewById(R.id.button_return) }

    /**
     * Chinese punctuation is active iff fcitx reports a non-empty punctuation mapping,
     * i.e. a Chinese input method with the punctuation addon switched on (中标).
     */
    private val chineseSymbols: Boolean
        get() = punctuationMapping.isNotEmpty() || rimeChinese

    /**
     * RIME handles punctuation itself, so fcitx reports no mapping for it.
     * Treat RIME outside of ascii mode as Chinese punctuation.
     */
    private var rimeChinese = false

    private fun modeSymbol(en: String): String =
        if (chineseSymbols) ZhSymbols[en] ?: transformPunctuation(en) else en

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        updateLangSwitchKey(v)
    }

    private val keepLettersUppercase by AppPrefs.getInstance().keyboard.keepLettersUppercase

    init {
        updateLangSwitchKey(showLangSwitchKey.getValue())
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
    }

    private val textKeys: List<TextKeyView> by lazy {
        allViews.filterIsInstance(TextKeyView::class.java).toList()
    }

    private var capsState: CapsState = CapsState.None

    private fun transformAlphabet(c: String): String {
        return when (capsState) {
            CapsState.None -> c.lowercase()
            else -> c.uppercase()
        }
    }

    private var punctuationMapping: Map<String, String> = mapOf()
    private fun transformPunctuation(p: String) = punctuationMapping.getOrDefault(p, p)

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        if (action is KeyAction.ModeSymbolAction) {
            // Chinese symbols are committed as-is; English ones go through fcitx
            val resolved = if (chineseSymbols) KeyAction.CommitAction(action.zh)
            else symbolAction(action.en)
            super.onAction(resolved, source)
            return
        }
        var transformed = action
        when (action) {
            is KeyAction.FcitxKeyAction -> when (source) {
                KeyActionListener.Source.Keyboard -> {
                    when (capsState) {
                        CapsState.None -> {
                            transformed = action.copy(act = action.act.lowercase())
                        }
                        CapsState.Once -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.Shift)
                            )
                            switchCapsState()
                        }
                        CapsState.Lock -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.CapsLock)
                            )
                        }
                    }
                }
                KeyActionListener.Source.Popup -> {
                    if (capsState == CapsState.Once) {
                        switchCapsState()
                    }
                }
            }
            is KeyAction.CapsAction -> switchCapsState(action.lock)
            else -> {}
        }
        super.onAction(transformed, source)
    }

    override fun onAttach() {
        capsState = CapsState.None
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.mainText.setText(
            when (returnDrawable) {
                R.drawable.ic_baseline_arrow_forward_24 -> R.string.return_key_go
                R.drawable.ic_baseline_search_24 -> R.string.return_key_search
                R.drawable.ic_baseline_send_24 -> R.string.return_key_send
                R.drawable.ic_baseline_keyboard_tab_24 -> R.string.return_key_next
                R.drawable.ic_baseline_keyboard_tab_reverse_24 -> R.string.return_key_previous
                R.drawable.ic_baseline_done_24 -> R.string.return_key_done
                else -> R.string.return_key_newline
            }
        )
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        updatePunctuationKeys()
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        space.mainText.text = buildString {
            append(ime.displayName)
            ime.subMode.run { label.ifEmpty { name.ifEmpty { null } } }?.let { append(" ($it)") }
        }
        updateLangToggleKey(ime)
        if (capsState != CapsState.None) {
            switchCapsState()
        }
    }

    /**
     * Big label = current mode, small label = mode after tapping.
     * English when the active IM is a plain keyboard layout, or RIME is in ascii mode
     * (fcitx5-rime reports sub mode label "A" then).
     */
    private fun updateLangToggleKey(ime: InputMethodEntry) {
        val english = ime.uniqueName.startsWith("keyboard-") || ime.subMode.label == "A"
        val newRimeChinese = ime.addon == "rime" && !english
        if (newRimeChinese != rimeChinese) {
            rimeChinese = newRimeChinese
            updatePunctuationKeys()
        }
        if (english) {
            lang.mainText.text = "英"
            lang.altText.text = "中"
        } else {
            // e.g. "双" for Shuangpin, "拼" for Pinyin; fall back to "中"
            val label = ime.subMode.label.ifEmpty { ime.label }
            val first = label.firstOrNull()
            lang.mainText.text =
                if (first != null && Character.UnicodeScript.of(first.code) == Character.UnicodeScript.HAN)
                    first.toString() else "中"
            lang.altText.text = "英"
        }
    }

    private fun transformPopupPreview(c: String): String {
        if (c.length != 1) return c
        if (c[0].isLetter()) return transformAlphabet(c)
        return modeSymbol(c)
    }

    override fun onPopupAction(action: PopupAction) {
        val newAction = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = transformPopupPreview(action.content))
            is PopupAction.PreviewUpdateAction -> action.copy(content = transformPopupPreview(action.content))
            is PopupAction.ShowKeyboardAction -> {
                when (action.keyboard) {
                    is KeyDef.Popup.Keyboard.Preset -> {
                        val label = action.keyboard.label
                        if (label.length == 1 && label[0].isLetter())
                            action.copy(
                                keyboard = action.keyboard.copy(label = transformAlphabet(label))
                            )
                        else action
                    }
                    is KeyDef.Popup.Keyboard.Explicit -> action
                }
            }
            else -> action
        }
        super.onPopupAction(newAction)
    }

    private fun switchCapsState(lock: Boolean = false) {
        capsState =
            if (lock) {
                when (capsState) {
                    CapsState.Lock -> CapsState.None
                    else -> CapsState.Lock
                }
            } else {
                when (capsState) {
                    CapsState.None -> CapsState.Once
                    else -> CapsState.None
                }
            }
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    private fun updateCapsButtonIcon() {
        caps.img.apply {
            imageResource = when (capsState) {
                CapsState.None -> R.drawable.ic_capslock_none
                CapsState.Once -> R.drawable.ic_capslock_once
                CapsState.Lock -> R.drawable.ic_capslock_lock
            }
        }
    }

    private fun updateLangSwitchKey(visible: Boolean) {
        lang.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateAlphabetKeys() {
        textKeys.forEach {
            if (it.def !is KeyDef.Appearance.AltText) return@forEach
            // 中/英 key labels are managed by updateLangToggleKey()
            if (it.id == R.id.button_lang) return@forEach
            it.mainText.text = it.def.displayText.let { str ->
                if (str.length != 1 || !str[0].isLetter()) return@forEach
                if (keepLettersUppercase) str.uppercase() else transformAlphabet(str)
            }
        }
    }

    private fun updatePunctuationKeys() {
        textKeys.forEach {
            if (it is AltTextKeyView) {
                if (it.id == R.id.button_lang) return@forEach
                it.def as KeyDef.Appearance.AltText
                it.altText.text = modeSymbol(it.def.altText)
                // punctuation main labels (the comma key) follow the mapping too
                val main = it.def.displayText
                if (main.isNotEmpty() && !main[0].isLetter()) {
                    it.mainText.text = modeSymbol(main)
                }
            } else {
                it.def as KeyDef.Appearance.Text
                it.mainText.text = it.def.displayText.let { str ->
                    // empty: labels set at runtime (e.g. the text return key)
                    if (str.isEmpty() || str[0].run { isLetter() || isWhitespace() }) return@forEach
                    transformPunctuation(str)
                }
            }
        }
    }

}