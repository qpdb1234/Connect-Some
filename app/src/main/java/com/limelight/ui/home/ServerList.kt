/*
 * Copyright (c) 2021  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.home

import android.content.Context
import android.util.AttributeSet
import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.limelight.R
import com.limelight.databinding.ServerListItemBinding
import com.limelight.model.ServerProfile
import com.limelight.util.debugCheck
import com.limelight.util.debugCheckNotNull


typealias ServerListItemAction = (profile: ServerProfile) -> Unit

/**
 *  Server list widget
 */
class ServerList(context: Context, attrs: AttributeSet?, defStyleAttr: Int)
    : RecyclerView(context, attrs, defStyleAttr) {
    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    /**
     * Action available on individual items
     */
    var onServerClick: ServerListItemAction? = null
    var onWakeServer: ServerListItemAction? = null
    var onSaveServer: ServerListItemAction? = null
    var onEditServer: ServerListItemAction? = null
    var onDuplicateServer: ServerListItemAction? = null
    var onDeleteServer: ServerListItemAction? = null
    var onCopyServerName: ServerListItemAction? = null
    var onCopyServerHost: ServerListItemAction? = null

    /**
     * Bulk deletion. Setting it is what enables multi-select: the long-press menu grows a
     * `Select` entry, and from then on tapping a card marks it instead of connecting to it.
     * Left null by the URL picker, whose rows are somebody else's saved servers - there is
     * nothing there to delete.
     */
    var onDeleteSelected: ((profiles: List<ServerProfile>) -> Unit)? = null

    /**
     * Ids of the marked cards. Ids rather than profiles, because [serverProfiles] re-emits a
     * brand new object graph on every unrelated database write; a `Set<ServerProfile>` would
     * quietly lose every mark as soon as somebody renamed a server.
     */
    private val selectedIds = linkedSetOf<Long>()

    private val _selectionActive = MutableLiveData(false)

    /** Whether cards are being marked. Drives the host's action bar. */
    val selectionActive: LiveData<Boolean> get() = _selectionActive

    private val _selectionCount = MutableLiveData(0)
    val selectionCount: LiveData<Int> get() = _selectionCount

    var lifecycleOwner: LifecycleOwner? = null; private set
    var indicatorSource: LiveData<List<ServerProfile>>? = null; private set
    private val serverListAdapter = ServerListAdapter(this)

    init {
        setHasFixedSize(true)
        layoutManager = LinearLayoutManager(context)
        adapter = serverListAdapter
    }

    /**
     * Set data source for this server list.
     */
    fun setSource(owner: LifecycleOwner, source: LiveData<List<ServerProfile>>) {
        lifecycleOwner = owner
        source.observe(owner) { serverListAdapter.submitList(it) }
    }

    /**
     * Set data source for this server list.
     * [indicatorSource] is used to show blinking indicator for rediscovered servers.
     */
    fun setSource(owner: LifecycleOwner, source: LiveData<List<ServerProfile>>, indicatorSource: LiveData<List<ServerProfile>>) {
        this.indicatorSource = indicatorSource
        setSource(owner, source)
    }

    /**
     * Enter multi-select with the card the user just long-pressed already marked - the point
     * of entering from a card is that this one is the thing they want gone.
     */
    fun beginSelection(profile: ServerProfile) {
        if (onDeleteSelected == null) return
        // Flip into selection mode first. `toggleSelection` pushes the state onto the cards
        // already on screen, and those cards read the checkbox visibility from
        // [isInSelectionMode] - if the flag is still down at that moment, every visible card
        // hides its mark and only the ones that rebind later (on scroll) ever show one.
        if (!_selectionActive.value.orFalse()) _selectionActive.value = true
        toggleSelection(profile)
    }

    fun clearSelection() {
        selectedIds.clear()
        _selectionCount.value = 0
        _selectionActive.value = false
        refreshVisibleSelection()
    }

    /**
     * Hand the marked servers over and drop out of multi-select. The list itself shrinks on
     * its own, through the same LiveData that fed it - nothing is removed from here.
     */
    fun deleteSelection() {
        if (selectedIds.isEmpty()) return
        val profiles = serverListAdapter.currentList.filter { selectedIds.contains(it.ID) }
        clearSelection()
        if (profiles.isNotEmpty()) onDeleteSelected?.invoke(profiles)
    }

    internal fun isInSelectionMode() = _selectionActive.value.orFalse()

    internal fun isSelected(profile: ServerProfile) = selectedIds.contains(profile.ID)

    /**
     * Mark or unmark one card. An unsaved card cannot be marked: its id is what the deletion
     * goes by, and a card that was never saved does not exist in the database yet.
     */
    internal fun toggleSelection(profile: ServerProfile) {
        if (profile.ID == 0L) return
        if (!selectedIds.remove(profile.ID)) selectedIds.add(profile.ID)
        _selectionCount.value = selectedIds.size
        refreshVisibleSelection()
    }

    /**
     * Push the current mark onto the cards that are actually on screen. Going through the
     * holders instead of the adapter matters: [ListAdapter.submitList] would compare the
     * unchanged rows against each other and rebind nothing, and
     * [RecyclerView.Adapter.notifyDataSetChanged] throws away the item animations.
     */
    private fun refreshVisibleSelection() {
        for (i in 0 until childCount) {
            (getChildViewHolder(getChildAt(i)) as? ServerListViewHolder)?.refreshSelectionState()
        }
    }
}

private fun Boolean?.orFalse() = this == true

/**
 * List adapter
 */
private class ServerListAdapter(private val serverList: ServerList) : ListAdapter<ServerProfile, ServerListViewHolder>(Differ) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerListViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ServerListItemBinding.inflate(inflater, parent, false)

        debugCheckNotNull(serverList.lifecycleOwner)
        binding.lifecycleOwner = serverList.lifecycleOwner
        return ServerListViewHolder(serverList, binding)
    }

    override fun onBindViewHolder(holder: ServerListViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

/**
 * List item
 */
private class ServerListViewHolder(val serverList: ServerList, val binding: ServerListItemBinding)
    : RecyclerView.ViewHolder(binding.root) {

    init {
        binding.root.setOnCreateContextMenuListener { menu, _, _ -> prepareContextMenu(menu) }
        binding.root.setOnClickListener {
            // While the user is marking cards, the card stops being a connect button.
            val profile = binding.profile
            if (serverList.isInSelectionMode()) profile?.let { serverList.toggleSelection(it) }
            else invokeAction(serverList.onServerClick)
        }
        binding.wakeBtn.setOnClickListener { invokeAction(serverList.onWakeServer) }
        binding.saveBtn.setOnClickListener { invokeAction(serverList.onSaveServer) }
    }

    fun bind(profile: ServerProfile) {
        binding.profile = profile
        refreshSelectionState()
        serverList.indicatorSource?.let { binding.indicator.setup(profile, it) }
    }

    fun refreshSelectionState() {
        val profile = binding.profile ?: return
        binding.selectionMode = serverList.isInSelectionMode()
        binding.selected = serverList.isSelected(profile)
    }

    private fun prepareContextMenu(menu: ContextMenu) {
        prepareContextMenuItem(menu, serverList.onEditServer, R.string.title_edit)
        prepareContextMenuItem(menu, serverList.onDuplicateServer, R.string.title_duplicate)
        prepareContextMenuItem(menu, serverList.onCopyServerName, R.string.title_copy_name)
        prepareContextMenuItem(menu, serverList.onCopyServerHost, R.string.title_copy_host)
        prepareContextMenuItem(menu, serverList.onDeleteServer, R.string.title_delete)

        // Entered from a card because that is where the user is already looking at one server
        // they want gone; marking the rest happens on the list itself.
        if (serverList.onDeleteSelected != null) {
            menu.add(R.string.title_select_servers).setOnMenuItemClickListener {
                val profile = binding.profile
                profile?.let { serverList.beginSelection(it) }
                profile != null
            }
        }
    }

    private fun prepareContextMenuItem(menu: ContextMenu, action: ServerListItemAction?, titleRes: Int) {
        if (action != null) // Add menu item only if the action is configured
            menu.add(titleRes).setOnMenuItemClickListener { invokeAction(action) }
    }

    private fun invokeAction(action: ServerListItemAction?): Boolean {
        debugCheckNotNull(binding.profile)
        return binding.profile?.let { action?.invoke(it); true } ?: false
    }
}

private object Differ : DiffUtil.ItemCallback<ServerProfile>() {
    override fun areItemsTheSame(old: ServerProfile, new: ServerProfile): Boolean {
        debugCheck(old.isSaved() == new.isSaved())
        if (old.isSaved())
            return old.ID == new.ID // Use ID match saved servers
        else
            return old == new
    }

    override fun areContentsTheSame(old: ServerProfile, new: ServerProfile): Boolean {
        return old == new
    }
}
