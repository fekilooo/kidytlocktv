/*
 * SkyTube
 * Copyright (C) 2015  Ramon Mifsud
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation (version 3 of the License).
 */

package free.rm.skytube.gui.businessobjects.views;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Keeps rapid vertical D-pad navigation inside a video grid while RecyclerView attaches the next
 * row. Without this guard Android may briefly choose the permanent subscriptions list on the left.
 */
public class TvGridRecyclerView extends RecyclerView {
    private static final int FOCUS_RETRY_COUNT = 4;
    private static final long FOCUS_RETRY_DELAY_MS = 32L;
    private int pendingDownFocusPosition = NO_POSITION;
    private int lastFocusedAdapterPosition = NO_POSITION;
    private boolean dpadNavigationLocked;
    private final AdapterDataObserver focusRestoreObserver = new AdapterDataObserver() {
        @Override
        public void onChanged() {
            restoreLockedFocusAfterDataChange();
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            restoreLockedFocusAfterDataChange();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            restoreLockedFocusAfterDataChange();
        }
    };

    public TvGridRecyclerView(@NonNull Context context) {
        super(context);
    }

    public TvGridRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public TvGridRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs,
                              int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setAdapter(@Nullable Adapter adapter) {
        final Adapter oldAdapter = getAdapter();
        if (oldAdapter != null) {
            oldAdapter.unregisterAdapterDataObserver(focusRestoreObserver);
        }
        super.setAdapter(adapter);
        if (adapter != null) {
            adapter.registerAdapterDataObserver(focusRestoreObserver);
        }
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        final ViewHolder holder = findContainingViewHolder(focused);
        if (holder != null && holder.getBindingAdapterPosition() != NO_POSITION) {
            lastFocusedAdapterPosition = holder.getBindingAdapterPosition();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleDpadDown(event)) {
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            pendingDownFocusPosition = NO_POSITION;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Handles DPAD_DOWN before Activity-level focus search can escape to a sibling list. */
    public boolean handleDpadDown(@NonNull KeyEvent event) {
        final View focused = findFocus();
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                && focused != null && isDescendant(focused)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                moveFocusDown(focused);
            }
            return true;
        }
        return false;
    }

    public boolean handleLockedDpadDown(@NonNull KeyEvent event) {
        if (!dpadNavigationLocked || event.getKeyCode() != KeyEvent.KEYCODE_DPAD_DOWN) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            final View focused = findFocus();
            if (focused != null && !isDescendant(focused)) {
                // A resumed Activity may let Android place focus in the subscriptions list before
                // MainFragment restores this grid. Never consume that list's navigation keys.
                return false;
            }
            if (focused != null && focused != this && isDescendant(focused)) {
                moveFocusDown(focused);
            } else {
                moveFocusDownFromPosition(lastFocusedAdapterPosition);
            }
        }
        return true;
    }

    public void setDpadNavigationLocked(boolean locked) {
        dpadNavigationLocked = locked;
        if (!locked) {
            pendingDownFocusPosition = NO_POSITION;
        }
    }

    public boolean containsView(@Nullable View view) {
        return view != null && isDescendant(view);
    }

    public void setPreferredFocusPosition(int adapterPosition) {
        lastFocusedAdapterPosition = adapterPosition;
    }

    public int getPreferredFocusPosition() {
        return lastFocusedAdapterPosition;
    }

    public void requestPreferredChildFocus() {
        final Adapter<?> adapter = getAdapter();
        final LayoutManager layoutManager = getLayoutManager();
        if (adapter == null || adapter.getItemCount() == 0) {
            requestFocus();
            return;
        }

        int targetPosition = lastFocusedAdapterPosition;
        if (targetPosition == NO_POSITION || targetPosition >= adapter.getItemCount()) {
            targetPosition = 0;
            if (layoutManager instanceof GridLayoutManager) {
                final int firstVisible =
                        ((GridLayoutManager) layoutManager).findFirstVisibleItemPosition();
                if (firstVisible != NO_POSITION) {
                    targetPosition = firstVisible;
                }
            }
        }

        final int safeTargetPosition = targetPosition;
        final ViewHolder attachedTarget = findViewHolderForAdapterPosition(safeTargetPosition);
        if (attachedTarget != null && attachedTarget.itemView.requestFocus()) {
            lastFocusedAdapterPosition = safeTargetPosition;
            return;
        }
        scrollToPosition(safeTargetPosition);
        post(() -> requestAdapterPositionFocus(safeTargetPosition, FOCUS_RETRY_COUNT));
    }

    public void restoreLockedNavigationFocus() {
        if (dpadNavigationLocked) {
            requestPreferredChildFocus();
        }
    }

    private void moveFocusDown(@NonNull View focused) {
        final ViewHolder focusedHolder = findContainingViewHolder(focused);
        final Adapter<?> adapter = getAdapter();
        final LayoutManager layoutManager = getLayoutManager();
        if (focusedHolder == null || adapter == null
                || !(layoutManager instanceof GridLayoutManager)) {
            return;
        }

        final int focusedPosition = focusedHolder.getBindingAdapterPosition();
        if (focusedPosition == NO_POSITION) {
            return;
        }
        lastFocusedAdapterPosition = focusedPosition;
        moveFocusDownFromPosition(focusedPosition);
    }

    private void moveFocusDownFromPosition(int focusedPosition) {
        final Adapter<?> adapter = getAdapter();
        final LayoutManager layoutManager = getLayoutManager();
        if (focusedPosition == NO_POSITION || adapter == null
                || !(layoutManager instanceof GridLayoutManager)) {
            return;
        }
        final int spanCount = ((GridLayoutManager) layoutManager).getSpanCount();
        if (pendingDownFocusPosition >= adapter.getItemCount()) {
            return;
        }
        final int basePosition = pendingDownFocusPosition > focusedPosition
                ? pendingDownFocusPosition : focusedPosition;
        final int targetPosition = basePosition + spanCount;
        if (targetPosition >= adapter.getItemCount()) {
            // Remember exactly one row beyond the current page. When paging appends that row,
            // the observer moves focus there instead of allowing RecyclerView to focus itself.
            pendingDownFocusPosition = targetPosition;
            return;
        }

        pendingDownFocusPosition = targetPosition;
        final ViewHolder attachedTarget = findViewHolderForAdapterPosition(targetPosition);
        if (attachedTarget != null && attachedTarget.itemView.requestFocus()) {
            lastFocusedAdapterPosition = targetPosition;
            pendingDownFocusPosition = NO_POSITION;
            return;
        }

        scrollToPosition(targetPosition);
        post(() -> requestPendingDownFocus(FOCUS_RETRY_COUNT));
    }

    private void requestPendingDownFocus(int retriesRemaining) {
        final int targetPosition = pendingDownFocusPosition;
        if (targetPosition == NO_POSITION) {
            return;
        }

        final ViewHolder target = findViewHolderForAdapterPosition(targetPosition);
        if (target != null && target.itemView.requestFocus()) {
            lastFocusedAdapterPosition = targetPosition;
            if (pendingDownFocusPosition == targetPosition) {
                pendingDownFocusPosition = NO_POSITION;
            }
            return;
        }
        if (retriesRemaining > 0 && isAttachedToWindow()) {
            postDelayed(() -> requestPendingDownFocus(retriesRemaining - 1),
                    FOCUS_RETRY_DELAY_MS);
        }
    }

    private void restoreLockedFocusAfterDataChange() {
        if (!dpadNavigationLocked) {
            return;
        }
        post(() -> {
            final View focused = findFocus();
            if (focused != null && focused != this && isDescendant(focused)
                    && findContainingViewHolder(focused) != null
                    && pendingDownFocusPosition == NO_POSITION) {
                return;
            }
            final int targetPosition = pendingDownFocusPosition != NO_POSITION
                    ? pendingDownFocusPosition : lastFocusedAdapterPosition;
            final Adapter<?> adapter = getAdapter();
            if (targetPosition != NO_POSITION && adapter != null && adapter.getItemCount() > 0) {
                final int safeTargetPosition = targetPosition < adapter.getItemCount()
                        ? targetPosition : Math.min(lastFocusedAdapterPosition,
                        adapter.getItemCount() - 1);
                if (safeTargetPosition == NO_POSITION) {
                    return;
                }
                scrollToPosition(safeTargetPosition);
                post(() -> requestAdapterPositionFocus(safeTargetPosition, FOCUS_RETRY_COUNT));
            }
        });
    }

    @Override
    public View focusSearch(View focused, int direction) {
        final View candidate = super.focusSearch(focused, direction);
        if (direction != View.FOCUS_DOWN && direction != View.FOCUS_UP) {
            return candidate;
        }

        final ViewHolder focusedHolder = findContainingViewHolder(focused);
        if (focusedHolder == null || (candidate != null && isDescendant(candidate))) {
            return candidate;
        }

        final int currentPosition = focusedHolder.getBindingAdapterPosition();
        final Adapter<?> adapter = getAdapter();
        final LayoutManager layoutManager = getLayoutManager();
        if (currentPosition == NO_POSITION || adapter == null
                || !(layoutManager instanceof GridLayoutManager)) {
            return focused;
        }

        final int spanCount = ((GridLayoutManager) layoutManager).getSpanCount();
        final int targetPosition = direction == View.FOCUS_DOWN
                ? currentPosition + spanCount
                : currentPosition - spanCount;
        if (targetPosition >= 0 && targetPosition < adapter.getItemCount()) {
            scrollToPosition(targetPosition);
            post(() -> requestAdapterPositionFocus(targetPosition, FOCUS_RETRY_COUNT));
        }

        // At a temporary or real edge, keep focus on the current card instead of escaping left.
        return focused;
    }

    private void requestAdapterPositionFocus(int adapterPosition, int retriesRemaining) {
        final ViewHolder target = findViewHolderForAdapterPosition(adapterPosition);
        if (target != null && target.itemView.requestFocus()) {
            lastFocusedAdapterPosition = adapterPosition;
            if (pendingDownFocusPosition == adapterPosition) {
                pendingDownFocusPosition = NO_POSITION;
            }
            return;
        }
        if (retriesRemaining > 0 && isAttachedToWindow()) {
            postDelayed(() -> requestAdapterPositionFocus(adapterPosition, retriesRemaining - 1),
                    FOCUS_RETRY_DELAY_MS);
        }
    }

    private boolean isDescendant(@NonNull View candidate) {
        if (candidate == this) {
            return true;
        }
        ViewParent parent = candidate.getParent();
        while (parent instanceof View) {
            if (parent == this) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }
}
