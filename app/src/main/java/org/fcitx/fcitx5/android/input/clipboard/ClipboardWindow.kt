/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.annotation.SuppressLint
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.annotation.Keep
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.snackbar.BaseTransientBottomBar.BaseCallback
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.SnackbarContentLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.NumberKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.BooleanKey.ClipboardDbEmpty
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.BooleanKey.ClipboardListeningEnabled
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.AddMore
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.EnableListening
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.Normal
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.TransitionEvent.ClipboardDbUpdated
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.TransitionEvent.ClipboardListeningUpdated
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.EventStateMachine
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.dsl.core.withTheme

class ClipboardWindow : InputWindow.ExtendedInputWindow<ClipboardWindow>(),
    InputBroadcastReceiver {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()
    private val fcitx by manager.fcitx()
    private val inputView by manager.inputView()
    private val bar: KawaiiBarComponent by manager.must()
    private val popup: PopupComponent by manager.must()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val punctuation: PunctuationComponent by manager.must()

    private val snackbarCtx by lazy {
        context.withTheme(R.style.InputViewSnackbarTheme)
    }
    private var snackbarInstance: Snackbar? = null

    private lateinit var stateMachine: EventStateMachine<ClipboardStateMachine.State, ClipboardStateMachine.TransitionEvent, ClipboardStateMachine.BooleanKey>

    @Keep
    private val clipboardEnabledListener = ManagedPreference.OnChangeListener<Boolean> { _, it ->
        stateMachine.push(
            ClipboardListeningUpdated, ClipboardListeningEnabled to it
        )
    }

    private val prefs = AppPrefs.getInstance().clipboard

    private val clipboardEnabledPref = prefs.clipboardListening
    private val clipboardReturnAfterPaste by prefs.clipboardReturnAfterPaste
    private val clipboardMaskSensitive by prefs.clipboardMaskSensitive

    private val clipboardEntryRadius by ThemeManager.prefs.clipboardEntryRadius

    private var adapterSubmitJob: Job? = null

    private fun submitEntries() {
        val q = query
        val pager = Pager(PagingConfig(pageSize = 16)) {
            if (q.isEmpty()) ClipboardManager.allEntries() else ClipboardManager.searchEntries(q)
        }
        adapterSubmitJob?.cancel()
        adapterSubmitJob = service.lifecycleScope.launch {
            pager.flow.collect { adapter.submitData(it) }
        }
    }

    // ---------------- search ----------------

    private var query = ""

    /** search bar shown and list filtered */
    private var searching = false

    /** embedded keyboard shown, keyboard output redirected into the query */
    private var typing = false

    private var querySubmitJob: Job? = null

    private fun setQuery(q: String) {
        if (q == query) return
        query = q
        ui.searchText.text = q
        // debounce: don't hit the database on every keystroke
        querySubmitJob?.cancel()
        querySubmitJob = service.lifecycleScope.launch {
            delay(150L)
            submitEntries()
        }
    }

    private val searchRedirect = object : FcitxInputMethodService.TextRedirect {
        override fun commit(text: String) = setQuery(query + text)
        override fun backspace() {
            if (query.isEmpty()) return
            setQuery(query.substring(0, query.offsetByCodePoints(query.length, -1)))
        }
        override fun clear() = setQuery("")
        override fun enter() = stopTyping()
    }

    private val textKeyboard by lazy { TextKeyboard(context, theme) }
    private val numberKeyboard by lazy { NumberKeyboard(context, theme) }
    private var embeddedKeyboard: BaseKeyboard? = null

    private val embeddedKeyActionListener = KeyActionListener { action, source ->
        if (action is KeyAction.LayoutSwitchAction) {
            when (action.act) {
                TextKeyboard.Name -> attachEmbeddedKeyboard(textKeyboard)
                "", NumberKeyboard.Name -> attachEmbeddedKeyboard(numberKeyboard)
                // symbol/emoji pickers would replace this window; not supported here
                else -> {}
            }
        } else {
            commonKeyActionListener.listener.onKeyAction(action, source)
        }
    }

    private fun detachEmbeddedKeyboard() {
        embeddedKeyboard?.let {
            it.onDetach()
            ui.keyboardContainer.removeView(it)
            it.keyActionListener = null
            it.popupActionListener = null
        }
        embeddedKeyboard = null
        popup.dismissAll()
    }

    private fun attachEmbeddedKeyboard(kb: BaseKeyboard) {
        if (kb === embeddedKeyboard) return
        detachEmbeddedKeyboard()
        embeddedKeyboard = kb
        kb.keyActionListener = embeddedKeyActionListener
        kb.popupActionListener = popup.listener
        ui.keyboardContainer.addView(
            kb, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        kb.onAttach()
        kb.onReturnDrawableUpdate(R.drawable.ic_baseline_search_24)
        kb.onInputMethodUpdate(fcitx.runImmediately { inputMethodEntryCached })
        kb.onPunctuationUpdate(punctuation.mapping)
    }

    private fun startTyping() {
        if (typing) return
        searching = true
        typing = true
        ui.searchBar.visibility = View.VISIBLE
        ui.setKeyboardHeight(inputView.keyboardHeightPx)
        ui.keyboardContainer.visibility = View.VISIBLE
        // room for the search bar + a few rows of results above the keyboard
        inputView.extraWindowHeight = context.dp(SEARCH_EXTRA_HEIGHT)
        attachEmbeddedKeyboard(textKeyboard)
        service.textRedirect = searchRedirect
        bar.titleCandidatesOverlay = true
    }

    /** hide the embedded keyboard, keep the filtered list */
    private fun stopTyping() {
        if (!typing) return
        typing = false
        service.textRedirect = null
        bar.titleCandidatesOverlay = false
        detachEmbeddedKeyboard()
        ui.keyboardContainer.visibility = View.GONE
        inputView.extraWindowHeight = 0
        if (query.isEmpty()) exitSearch()
    }

    private fun exitSearch() {
        stopTyping()
        if (!searching) return
        searching = false
        ui.searchBar.visibility = View.GONE
        querySubmitJob?.cancel()
        query = ""
        ui.searchText.text = ""
        submitEntries()
        stateMachine.push(ClipboardDbUpdated, ClipboardDbEmpty to (ClipboardManager.itemCount == 0))
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        embeddedKeyboard?.onInputMethodUpdate(ime)
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        embeddedKeyboard?.onPunctuationUpdate(mapping)
    }

    private val adapter: ClipboardAdapter by lazy {
        object : ClipboardAdapter(
            theme,
            context.dp(clipboardEntryRadius.toFloat()),
            clipboardMaskSensitive
        ) {
            override fun onPin(id: Int) {
                service.lifecycleScope.launch { ClipboardManager.pin(id) }
            }

            override fun onUnpin(id: Int) {
                service.lifecycleScope.launch { ClipboardManager.unpin(id) }
            }

            override fun onEdit(id: Int) {
                AppUtil.launchClipboardEdit(context, id)
            }

            override fun onShare(entry: ClipboardEntry) {
                val target = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, entry.text)
                }
                val chooser = Intent.createChooser(target, null).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(chooser)
            }

            override fun onDelete(id: Int) {
                service.lifecycleScope.launch {
                    ClipboardManager.delete(id)
                    showUndoSnackbar(id)
                }
            }

            override fun onPaste(entry: ClipboardEntry) {
                // keep the filtered results, but the paste itself must reach the app
                stopTyping()
                service.commitTextToEditor(entry.text)
                if (clipboardReturnAfterPaste) windowManager.attachWindow(KeyboardWindow)
            }
        }
    }

    private val ui by lazy {
        ClipboardUi(context, theme).apply {
            recyclerView.apply {
                layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
                adapter = this@ClipboardWindow.adapter
            }
            ItemTouchHelper(object : ItemTouchHelper.Callback() {
                override fun getMovementFlags(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ): Int {
                    return makeMovementFlags(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    return false
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val entry = adapter.getEntryAt(viewHolder.bindingAdapterPosition) ?: return
                    service.lifecycleScope.launch {
                        ClipboardManager.delete(entry.id)
                        showUndoSnackbar(entry.id)
                    }
                }
            }).attachToRecyclerView(recyclerView)
            enableUi.enableButton.setOnClickListener {
                clipboardEnabledPref.setValue(true)
            }
            searchButton.setOnClickListener {
                if (searching) exitSearch() else startTyping()
            }
            searchText.setOnClickListener { startTyping() }
            searchClearButton.setOnClickListener {
                if (typing && query.isNotEmpty()) setQuery("") else exitSearch()
            }
            deleteAllButton.setOnClickListener {
                service.lifecycleScope.launch {
                    promptDeleteAll(ClipboardManager.haveUnpinned())
                }
            }
        }
    }

    override fun onCreateView(): View = ui.root

    private var promptMenu: PopupMenu? = null

    private fun promptDeleteAll(skipPinned: Boolean) {
        promptMenu?.dismiss()
        promptMenu = PopupMenu(context, ui.deleteAllButton).apply {
            menu.add(buildSpannedString {
                bold {
                    color(context.styledColorOrDefault(android.R.attr.colorAccent, theme.genericActiveForegroundColor)) {
                        append(context.getString(if (skipPinned) R.string.delete_all_except_pinned else R.string.delete_all_pinned_items))
                    }
                }
            }).isEnabled = false
            menu.add(android.R.string.cancel)
            menu.item(android.R.string.ok) {
                service.lifecycleScope.launch {
                    val ids = ClipboardManager.deleteAll(skipPinned)
                    showUndoSnackbar(*ids)
                }
            }
            setOnDismissListener {
                if (it === promptMenu) promptMenu = null
            }
            show()
        }
    }

    private val pendingDeleteIds = arrayListOf<Int>()

    @SuppressLint("RestrictedApi")
    private fun showUndoSnackbar(vararg id: Int) {
        id.forEach { pendingDeleteIds.add(it) }
        val str = context.resources.getString(R.string.num_items_deleted, pendingDeleteIds.size)
        snackbarInstance = Snackbar.make(snackbarCtx, ui.root, str, Snackbar.LENGTH_LONG)
            .setBackgroundTint(theme.popupBackgroundColor)
            .setTextColor(theme.popupTextColor)
            .setActionTextColor(theme.genericActiveBackgroundColor)
            .setAction(R.string.undo) {
                service.lifecycleScope.launch {
                    ClipboardManager.undoDelete(*pendingDeleteIds.toIntArray())
                    pendingDeleteIds.clear()
                }
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                    if (snackbarInstance === transientBottomBar) {
                        snackbarInstance = null
                    }
                    when (event) {
                        BaseCallback.DISMISS_EVENT_SWIPE,
                        BaseCallback.DISMISS_EVENT_MANUAL,
                        BaseCallback.DISMISS_EVENT_TIMEOUT -> {
                            service.lifecycleScope.launch {
                                ClipboardManager.realDelete()
                                pendingDeleteIds.clear()
                            }
                        }
                        BaseCallback.DISMISS_EVENT_ACTION,
                        BaseCallback.DISMISS_EVENT_CONSECUTIVE -> {
                            // user clicked "undo" or deleted more items which makes a new snackbar
                        }
                    }
                }
            }).apply {
                val hMargin = snackbarCtx.dp(24)
                val vMargin = snackbarCtx.dp(16)
                view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    leftMargin = hMargin
                    rightMargin = hMargin
                    bottomMargin = vMargin
                }
                ((view as FrameLayout).getChildAt(0) as SnackbarContentLayout).apply {
                    messageView.letterSpacing = 0f
                    actionView.letterSpacing = 0f
                }
                show()
            }
    }

    override fun onAttached() {
        val isEmpty = ClipboardManager.itemCount == 0
        val isListening = clipboardEnabledPref.getValue()
        val initialState = when {
            !isListening -> EnableListening
            isEmpty -> AddMore
            else -> Normal
        }
        stateMachine = ClipboardStateMachine.new(initialState, isEmpty, isListening) {
            ui.switchUiByState(it)
        }
        // manually switch to initial ui
        ui.switchUiByState(initialState)
        adapter.addLoadStateListener {
            // an empty search result is not an empty clipboard
            if (searching) return@addLoadStateListener
            val empty = it.append.endOfPaginationReached && adapter.itemCount < 1
            stateMachine.push(ClipboardDbUpdated, ClipboardDbEmpty to empty)
        }
        submitEntries()
        clipboardEnabledPref.registerOnChangeListener(clipboardEnabledListener)
    }

    override fun onDetached() {
        // must run before the bar reacts to the window detaching
        typing = false
        searching = false
        service.textRedirect = null
        bar.titleCandidatesOverlay = false
        detachEmbeddedKeyboard()
        inputView.extraWindowHeight = 0
        querySubmitJob?.cancel()
        clipboardEnabledPref.unregisterOnChangeListener(clipboardEnabledListener)
        adapter.onDetached()
        adapterSubmitJob?.cancel()
        promptMenu?.dismiss()
        snackbarInstance?.dismiss()
    }

    override val title: String by lazy {
        context.getString(R.string.clipboard)
    }

    override fun onCreateBarExtension(): View = ui.extension

    companion object {
        /** search bar (40dp) + results area (180dp) */
        private const val SEARCH_EXTRA_HEIGHT = 220
    }
}