/*
 * Copyright (c) 2026  Connect Some.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.localsend

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.limelight.R

/**
 * The pinned devices, in a list whose rows can be dragged into a new order.
 *
 * Both LocalSend hosts show the favorites, and the reorder is the same on both: a row's handle
 * starts a vertical drag, dropping it lands the new index in [LsManager.reorderFavorite] (which
 * persists and re-emits the favorites flow), and the tap / long-press of the old rows are kept
 * - a tap still sends the outbox, a long press still opens the row's menu. Living in one class
 * rather than being written twice is what stops the standalone screen and the drawer drifting
 * apart, exactly the reason the rows themselves are in [LsWidgets].
 *
 * The list mirrors [LsManager.favorites]: the host feeds it through [submit] whenever the flow
 * emits, and [attachTo] wires the [RecyclerView] and the [ItemTouchHelper]. A drag mutates the
 * local copy and notifies the move so the animation is smooth, then commits the same move to the
 * manager; the echo the manager emits back is recognized as already-applied and redraws nothing,
 * so a drag in progress is never yanked by its own persistence.
 */
class LsFavoritesAdapter(
        private val context: Context,
        private val onTap: (LsPeer) -> Unit,
        private val onLongPress: (LsPeer) -> Unit,
) : RecyclerView.Adapter<LsFavoritesAdapter.RowHolder>() {

    private val items = ArrayList<LsPeer>()
    private var touchHelper: ItemTouchHelper? = null

    /**
     * Replaces the shown favorites with [peers]. Skipped when the fingerprint order is already
     * what is on screen - which is the case for the flow echo of a move this adapter just made -
     * so a commit does not reset an in-flight drag.
     */
    fun submit(peers: List<LsPeer>) {
        val previous = ArrayList(items)
        if (previous.map { it.fingerprint } == peers.map { it.fingerprint }) return
        items.clear()
        items.addAll(peers)
        // Item-level notifications rather than a blanket notifyDataSetChanged(): this list is a
        // wrap_content RecyclerView nested inside a scrolling parent, and a full refresh leaves it
        // measuring the height it had *before* the change - so a newly pinned row never showed and
        // the list looked stuck at its first entry (add a second favorite, still only the first is
        // visible). DiffUtil emits insert/remove/move, which re-run the measure and grow the list.
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = items.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previous[oldItemPosition].fingerprint == items[newItemPosition].fingerprint
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previous[oldItemPosition] == items[newItemPosition]
        }).dispatchUpdatesTo(this)
    }

    /**
     * Puts [list] in order with a plain vertical manager and installs the drag. The reorder is
     * wired here rather than at each host because both hosts reorder the identical favorites
     * list in the identical way; only the container differs.
     */
    fun attachTo(list: RecyclerView) {
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = this

        val callback = object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

            override fun onMove(recyclerView: RecyclerView,
                    source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = source.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                items.add(to, items.removeAt(from))
                notifyItemMoved(from, to)
                LsManager.reorderFavorite(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            // A drag starts from the row's handle, not a long press on the row - the long press
            // stays free for the row's menu.
            override fun isLongPressDragEnabled() = false
        }
        touchHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(list) }
    }

    inner class RowHolder(val row: LinearLayout, val label: TextView, val handle: ImageView)
        : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val pad = context.resources.getDimensionPixelSize(R.dimen.padding_small)
        val btnSize = context.resources.getDimensionPixelSize(R.dimen.action_btn_size)

        val label = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            setBackgroundResource(LsWidgets.rowSelectableBackground(context))
        }

        val handle = ImageView(context).apply {
            setImageResource(R.drawable.ic_drag_handle)
            contentDescription = context.getString(R.string.desc_localsend_drag_favorite)
            setColorFilter(0xFFAAAAAA.toInt())
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT)
            addView(label)
            addView(handle)
        }
        return RowHolder(row, label, handle)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val peer = items[position]
        holder.label.text = LsWidgets.favoriteLabel(context, peer)
        holder.label.setOnClickListener { onTap(peer) }
        holder.label.setOnLongClickListener { onLongPress(peer); true }
        holder.handle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper?.startDrag(holder)
            false
        }
    }

    override fun getItemCount() = items.size
}