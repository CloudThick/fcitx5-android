/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.view.View
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.horizontalPadding

class PreeditComponent : UniqueComponent<PreeditComponent>(), Dependent, InputBroadcastReceiver,
    ManagedHandler by managedHandler() {

    private val context by manager.context()
    private val theme by manager.theme()
    private val fcitx by manager.fcitx()

    val ui by lazy {
        val keyBorder = ThemeManager.prefs.keyBorder.getValue()
        val bkgColor =
            if (!keyBorder && theme is Theme.Builtin) theme.barColor else theme.backgroundColor
        PreeditUi(context, theme, setupTextView = {
            backgroundColor = bkgColor
            horizontalPadding = dp(8)
        }).apply {
            // TODO make it customizable
            root.alpha = 0.8f
            root.visibility = View.INVISIBLE
            onPreeditTap = ::moveCursorTo
        }
    }

    /*
     * Tap-to-position inside the preedit, so a mistyped letter can be fixed in place.
     *
     * The displayed preedit does not map 1:1 to raw input (separators are inserted,
     * and Shuangpin shows full pinyin for 2 keystrokes), so we can't compute how many
     * Left/Right presses are needed. Instead, press one key at a time and re-check
     * the reported cursor after every input panel update until the target is reached
     * or passed.
     */
    private var currentCursor = -1
    private var targetCursor = -1
    private var direction = 0
    private var steps = 0
    private var unchangedUpdates = 0

    private fun stopMoving() {
        targetCursor = -1
        direction = 0
    }

    private fun moveCursorTo(target: Int) {
        if (currentCursor < 0) return
        targetCursor = target
        direction = 0
        steps = 0
        step()
    }

    private fun step() {
        if (targetCursor < 0) return
        val dir = targetCursor.compareTo(currentCursor)
        // reached, overshot (direction flipped), or runaway safeguard
        if (dir == 0 || (direction != 0 && dir != direction) || steps >= MaxSteps) {
            stopMoving()
            return
        }
        direction = dir
        steps++
        unchangedUpdates = 0
        val sym = if (dir < 0) FcitxKeyMapping.FcitxKey_Left else FcitxKeyMapping.FcitxKey_Right
        fcitx.runIfReady { sendKey(KeySym(sym), KeyStates.Virtual) }
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        val newCursor = data.preedit.cursor
        val changed = newCursor != currentCursor
        currentCursor = newCursor
        ui.update(data)
        ui.root.visibility = if (ui.visible) View.VISIBLE else View.INVISIBLE
        if (targetCursor < 0) return
        if (newCursor < 0) {
            stopMoving()
        } else if (changed) {
            step()
        } else if (++unchangedUpdates >= MaxUnchangedUpdates) {
            // cursor can't move any further (e.g. at an edge)
            stopMoving()
        }
    }

    companion object {
        private const val MaxSteps = 64
        private const val MaxUnchangedUpdates = 3
    }
}
