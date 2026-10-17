/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ViewAnimator
import androidx.transition.Fade
import androidx.transition.TransitionManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.coordinatorlayout.coordinatorLayout
import splitties.views.dsl.coordinatorlayout.defaultLParams
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.view
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.wrapContent
import splitties.views.horizontalPadding
import splitties.views.dsl.recyclerview.recyclerView
import timber.log.Timber

class ClipboardUi(override val ctx: Context, private val theme: Theme) : Ui {

    val recyclerView = recyclerView {
        addItemDecoration(SpacesItemDecoration(dp(4)))
    }

    val enableUi = ClipboardInstructionUi.Enable(ctx, theme)

    val emptyUi = ClipboardInstructionUi.Empty(ctx, theme)

    val viewAnimator =  view(::ViewAnimator) {
        add(recyclerView, lParams(matchParent, matchParent))
        add(emptyUi.root, lParams(matchParent, matchParent))
        add(enableUi.root, lParams(matchParent, matchParent))
    }

    private val keyBorder by ThemeManager.prefs.keyBorder
    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    // ---- search ----

    val searchClearButton = ToolButton(ctx, R.drawable.ic_baseline_close_24, theme).apply {
        contentDescription = ctx.getString(R.string.clipboard_search_clear)
    }

    /** shows the query; tap to (re)open the embedded keyboard */
    val searchText = textView {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.START
        gravity = Gravity.CENTER_VERTICAL
        textSize = 16f
        horizontalPadding = dp(12)
        hint = ctx.getString(R.string.clipboard_search_hint)
        setTextColor(theme.keyTextColor)
        setHintTextColor(theme.altKeyTextColor)
    }

    val searchBar = horizontalLayout {
        visibility = View.GONE
        gravity = Gravity.CENTER_VERTICAL
        backgroundColor = theme.keyBackgroundColor
        add(searchText, LinearLayout.LayoutParams(0, matchParent, 1f))
        add(searchClearButton, lParams(dp(SEARCH_BAR_HEIGHT), dp(SEARCH_BAR_HEIGHT)))
    }

    /** holds the embedded keyboard while typing a search query */
    val keyboardContainer = frameLayout {
        visibility = View.GONE
    }

    private val content = verticalLayout {
        add(searchBar, lParams(matchParent, dp(SEARCH_BAR_HEIGHT)))
        add(viewAnimator, LinearLayout.LayoutParams(matchParent, 0, 1f))
        add(keyboardContainer, lParams(matchParent, wrapContent))
    }

    override val root = coordinatorLayout {
        if (!keyBorder) {
            backgroundColor = theme.barColor
        }
        add(content, defaultLParams(matchParent, matchParent))
    }

    val searchButton = ToolButton(ctx, R.drawable.ic_baseline_search_24, theme).apply {
        contentDescription = ctx.getString(R.string.clipboard_search)
    }

    fun setKeyboardHeight(px: Int) {
        keyboardContainer.layoutParams = keyboardContainer.layoutParams.apply { height = px }
    }

    val deleteAllButton = ToolButton(ctx, R.drawable.ic_baseline_delete_sweep_24, theme).apply {
        contentDescription = ctx.getString(R.string.delete_all)
    }

    val extension = horizontalLayout {
        add(searchButton, lParams(dp(40), dp(40)))
        add(deleteAllButton, lParams(dp(40), dp(40)))
    }

    companion object {
        const val SEARCH_BAR_HEIGHT = 40
    }

    private fun setDeleteButtonShown(enabled: Boolean) {
        deleteAllButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
        searchButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    fun switchUiByState(state: ClipboardStateMachine.State) {
        Timber.d("Switch clipboard to $state")
        if (!disableAnimation)
            TransitionManager.beginDelayedTransition(root, Fade().apply { duration = 100L })
        when (state) {
            ClipboardStateMachine.State.Normal -> {
                viewAnimator.displayedChild = 0
                setDeleteButtonShown(true)
            }
            ClipboardStateMachine.State.AddMore -> {
                viewAnimator.displayedChild = 1
                setDeleteButtonShown(false)
            }
            ClipboardStateMachine.State.EnableListening -> {
                viewAnimator.displayedChild = 2
                setDeleteButtonShown(false)
            }
        }
    }
}