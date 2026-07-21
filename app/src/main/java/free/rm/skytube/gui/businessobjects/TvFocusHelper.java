/*
 * SkyTube
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation (version 3 of the License).
 */

package free.rm.skytube.gui.businessobjects;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import free.rm.skytube.R;

/** Utilities that connect touch-oriented layouts into predictable TV D-pad focus paths. */
public final class TvFocusHelper {
    private TvFocusHelper() {
    }

    /**
     * Handles the vertical transitions that Android's default focus finder cannot resolve across
     * nested ViewPagers, scrolling containers, tabs and toolbars.
     */
    public static boolean handleMainNavigation(@NonNull Activity activity, @NonNull KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }

        final int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
            return false;
        }

        final View root = activity.getWindow().getDecorView();
        final View current = activity.getCurrentFocus();
        if (current == null || !isDescendant(root, current)) {
            return false;
        }

        if (current.getId() == R.id.channel_subscribe_button
                && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            final View search = root.findViewById(R.id.menu_search);
            if (search != null && search.isShown()) {
                search.setFocusableInTouchMode(true);
                search.post(search::requestFocus);
                return true;
            }
        }

        final TabLayout currentTabs = findAncestor(current, TabLayout.class);
        if (currentTabs != null) {
            prepareTabs(currentTabs);
            focusNearestAction(root, currentTabs, keyCode == KeyEvent.KEYCODE_DPAD_UP);
            return true;
        }

        final Toolbar currentToolbar = findAncestor(current, Toolbar.class);
        if (currentToolbar != null && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            final TabLayout tabsBelow = findNearestTabs(root, currentToolbar, false);
            if (tabsBelow != null) {
                return focusSelectedTab(tabsBelow);
            }
            focusNearestAction(root, currentToolbar, false);
            return true;
        }

        final RecyclerView recyclerView = findAncestor(current, RecyclerView.class);
        if (recyclerView != null && keyCode == KeyEvent.KEYCODE_DPAD_UP
                && isInFirstAdapterRow(recyclerView, current)) {
            final TabLayout tabsAbove = findNearestTabs(root, recyclerView, true);
            if (tabsAbove != null) {
                return focusSelectedTab(tabsAbove);
            }
            return focusNearestAction(root, recyclerView, true);
        }

        // Standalone actions such as the channel subscribe button can otherwise become a focus
        // island because they overlap the app bar rather than participating in its view hierarchy.
        if (recyclerView == null
                && focusNearestAction(root, current, keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
            return true;
        }

        return false;
    }

    /** Moves the initial focus away from non-actionable scrolling containers onto visible content. */
    public static void focusInitialContent(@NonNull Activity activity) {
        final View current = activity.getCurrentFocus();
        if (current != null && current.isClickable()) {
            return;
        }
        final View root = activity.getWindow().getDecorView();
        root.post(() -> {
            final List<RecyclerView> lists = new ArrayList<>();
            collectViews(root, RecyclerView.class, lists);
            for (RecyclerView list : lists) {
                if (isActuallyVisible(list) && list.getChildCount() > 0) {
                    final View first = list.getChildAt(0);
                    if (first.isFocusable() && first.requestFocus()) {
                        return;
                    }
                }
            }
            final List<TabLayout> tabs = new ArrayList<>();
            collectViews(root, TabLayout.class, tabs);
            for (TabLayout tabLayout : tabs) {
                if (isActuallyVisible(tabLayout) && focusSelectedTab(tabLayout)) {
                    return;
                }
            }
            final View firstAction = findFirstAction(root);
            if (firstAction != null) {
                firstAction.requestFocus();
            }
        });
    }

    /** Makes custom Material dialogs reachable and gives every actionable control a focus ring. */
    public static void prepareDialog(@NonNull Dialog dialog) {
        if (dialog.getWindow() == null) {
            return;
        }
        final View root = dialog.getWindow().getDecorView();
        root.post(() -> {
            prepareDialogHierarchy(root);
            prepareDialogLists(root);
            prepareDialogActions(root);
            final View preferredContent = findPreferredDialogContent(root);
            if (preferredContent != null && preferredContent.requestFocus()) {
                return;
            }
            final View preferredAction = findPreferredDialogAction(root);
            if (preferredAction != null && preferredAction.requestFocus()) {
                return;
            }
            final View focused = dialog.getCurrentFocus();
            if (focused == null || focused instanceof ScrollView) {
                final View firstAction = findFirstAction(root);
                if (firstAction != null) {
                    firstAction.requestFocus();
                }
            }
        });
    }

    /** Makes toolbar buttons behave like a horizontal TV navigation strip. */
    public static void prepareToolbarNavigation(@NonNull Toolbar toolbar, View downTarget) {
        final List<View> actions = collectToolbarActions(toolbar);
        for (int i = 0; i < actions.size(); i++) {
            final View current = actions.get(i);
            final View left = i > 0 ? actions.get(i - 1) : null;
            final View right = i + 1 < actions.size() ? actions.get(i + 1) : null;
            ensureTvActionReady(current);
            current.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    return left != null && left.requestFocus();
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    return right != null && right.requestFocus();
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    return requestFirstFocusableDescendant(downTarget);
                }
                return false;
            });
        }
    }

    @Nullable
    public static View findFirstToolbarAction(@Nullable Toolbar toolbar) {
        if (toolbar == null) {
            return null;
        }
        final List<View> actions = collectToolbarActions(toolbar);
        return actions.isEmpty() ? null : actions.get(0);
    }

    @Nullable
    public static View findLastToolbarAction(@Nullable Toolbar toolbar) {
        if (toolbar == null) {
            return null;
        }
        final List<View> actions = collectToolbarActions(toolbar);
        return actions.isEmpty() ? null : actions.get(actions.size() - 1);
    }

    /** Lets the first row in a RecyclerView move back to a known toolbar target on DPAD_UP. */
    public static void prepareRecyclerTopNavigation(@NonNull RecyclerView recyclerView, View upTarget) {
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            prepareRecyclerChildForUpNavigation(recyclerView, recyclerView.getChildAt(i), upTarget);
        }
        recyclerView.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View view) {
                prepareRecyclerChildForUpNavigation(recyclerView, view, upTarget);
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View view) {
            }
        });
    }

    private static void prepareDialogLists(View root) {
        final List<RecyclerView> lists = new ArrayList<>();
        collectViews(root, RecyclerView.class, lists);
        for (RecyclerView list : lists) {
            for (int i = 0; i < list.getChildCount(); i++) {
                prepareDialogListRow(root, list, list.getChildAt(i));
            }
            list.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
                @Override
                public void onChildViewAttachedToWindow(@NonNull View view) {
                    prepareDialogListRow(root, list, view);
                }

                @Override
                public void onChildViewDetachedFromWindow(@NonNull View view) {
                }
            });
        }
    }

    private static void prepareDialogListRow(View root, RecyclerView list, View row) {
        if (!row.isClickable()) {
            return;
        }
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        ensureTvActionVisuals(row);
        row.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return moveDialogListFocus(root, list, view, -1);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && isLastDialogListRow(list, view)) {
                return focusPreferredDialogAction(root);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return moveDialogListFocus(root, list, view, 1);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                return focusDialogAction(root, list, false);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return focusDialogAction(root, list, true);
            }
            return false;
        });
    }

    private static boolean focusDialogAction(View root, RecyclerView list, boolean rightmost) {
        final Rect listRect = screenRect(list);
        final List<View> actions = new ArrayList<>();
        collectActions(root, list, actions);
        View best = null;
        int bestX = rightmost ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        for (View action : actions) {
            final Rect rect = screenRect(action);
            if (rect.top < listRect.bottom - 8) {
                continue;
            }
            final int centerX = rect.centerX();
            if ((rightmost && centerX > bestX) || (!rightmost && centerX < bestX)) {
                best = action;
                bestX = centerX;
            }
        }
        return best != null && best.requestFocus();
    }

    private static void prepareDialogHierarchy(View view) {
        if (shouldSkipDialogTextFocus(view)) {
            view.setFocusable(false);
            view.setFocusableInTouchMode(false);
            view.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                        || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    View target = findPreferredDialogAction(v.getRootView());
                    return target != null && target.requestFocus();
                }
                return false;
            });
        }
        if (view.isEnabled() && view.isClickable()) {
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            ensureTvActionVisuals(view);
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                prepareDialogHierarchy(group.getChildAt(i));
            }
        }
    }

    private static void prepareDialogActions(View root) {
        final List<View> buttons = collectDialogButtons(root);
        for (int i = 0; i < buttons.size(); i++) {
            final View current = buttons.get(i);
            final View left = i > 0 ? buttons.get(i - 1) : null;
            final View right = i + 1 < buttons.size() ? buttons.get(i + 1) : null;
            ensureTvActionReady(current);
            current.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    return left != null && left.requestFocus();
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    return right != null && right.requestFocus();
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    final View preferredContent = findLastDialogContent(root);
                    return preferredContent != null && preferredContent.requestFocus();
                }
                return false;
            });
        }
    }

    private static View findPreferredDialogContent(View root) {
        final List<RecyclerView> lists = new ArrayList<>();
        collectViews(root, RecyclerView.class, lists);
        for (RecyclerView list : lists) {
            if (!isActuallyVisible(list) || list.getChildCount() == 0) {
                continue;
            }
            final View focusedChild = findFocusedDescendant(list);
            if (focusedChild != null) {
                return focusedChild;
            }
            final View firstChild = list.getChildAt(0);
            if (firstChild != null && firstChild.isFocusable()) {
                return firstChild;
            }
        }
        return findFirstFocusableByClass(root, EditText.class);
    }

    private static View findLastDialogContent(View root) {
        final List<RecyclerView> lists = new ArrayList<>();
        collectViews(root, RecyclerView.class, lists);
        for (RecyclerView list : lists) {
            if (!isActuallyVisible(list) || list.getChildCount() == 0) {
                continue;
            }
            for (int i = list.getChildCount() - 1; i >= 0; i--) {
                final View child = list.getChildAt(i);
                if (child != null && child.isFocusable() && child.isShown()) {
                    return child;
                }
            }
        }
        return findPreferredDialogContent(root);
    }

    private static boolean shouldSkipDialogTextFocus(View view) {
        if (!(view instanceof ScrollView) && !(view instanceof android.widget.TextView)) {
            return false;
        }
        final int id = view.getId();
        if (id == View.NO_ID) {
            return false;
        }
        try {
            final String entry = view.getResources().getResourceEntryName(id);
            return "md_content".equals(entry) || "md_contentScrollView".equals(entry);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static View findPreferredDialogAction(View root) {
        View preferred = findDialogActionByEntryName(root, "md_buttonDefaultNegative");
        if (preferred == null) {
            preferred = findDialogActionByEntryName(root, "md_buttonDefaultPositive");
        }
        if (preferred == null) {
            preferred = findDialogActionByEntryName(root, "md_buttonDefaultNeutral");
        }
        return preferred != null ? preferred : findFirstAction(root);
    }

    private static List<View> collectDialogButtons(View root) {
        final List<View> buttons = new ArrayList<>();
        addDialogButton(root, "md_buttonDefaultNegative", buttons);
        addDialogButton(root, "md_buttonDefaultNeutral", buttons);
        addDialogButton(root, "md_buttonDefaultPositive", buttons);
        return buttons;
    }

    private static void addDialogButton(View root, String entryName, List<View> result) {
        final View button = findDialogActionByEntryName(root, entryName);
        if (button != null) {
            result.add(button);
        }
    }

    private static View findDialogActionByEntryName(View view, String entryName) {
        if (view.isShown() && view.isEnabled() && view.isClickable() && view.isFocusable()) {
            final int id = view.getId();
            if (id != View.NO_ID) {
                try {
                    if (entryName.equals(view.getResources().getResourceEntryName(id))) {
                        return view;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                final View result = findDialogActionByEntryName(group.getChildAt(i), entryName);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static View findFirstAction(View view) {
        if (view.isShown() && view.isEnabled() && view.isClickable() && view.isFocusable()) {
            return view;
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                final View result = findFirstAction(group.getChildAt(i));
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static <T extends View> View findFirstFocusableByClass(View root, Class<T> type) {
        if (type.isInstance(root) && root.isShown() && root.isEnabled() && root.isFocusable()) {
            return root;
        }
        if (root instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                final View result = findFirstFocusableByClass(group.getChildAt(i), type);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static View findFocusedDescendant(View root) {
        if (root.isFocused()) {
            return root;
        }
        if (root instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                final View result = findFocusedDescendant(group.getChildAt(i));
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static boolean isInFirstAdapterRow(RecyclerView recyclerView, View focused) {
        final View item = recyclerView.findContainingItemView(focused);
        if (item == null) {
            return false;
        }
        final int position = recyclerView.getChildAdapterPosition(item);
        if (position == RecyclerView.NO_POSITION) {
            return false;
        }
        if (recyclerView.getLayoutManager() instanceof GridLayoutManager) {
            return position < ((GridLayoutManager) recyclerView.getLayoutManager()).getSpanCount();
        }
        return position == 0;
    }

    private static TabLayout findNearestTabs(View root, View source, boolean above) {
        final List<TabLayout> layouts = new ArrayList<>();
        collectViews(root, TabLayout.class, layouts);
        final Rect sourceRect = screenRect(source);
        TabLayout best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (TabLayout tabs : layouts) {
            if (!isActuallyVisible(tabs)) {
                continue;
            }
            final Rect rect = screenRect(tabs);
            final int distance = above ? sourceRect.top - rect.bottom : rect.top - sourceRect.bottom;
            if (distance >= -8 && distance < bestDistance) {
                best = tabs;
                bestDistance = distance;
            }
        }
        return best;
    }

    public static boolean focusSelectedTab(@NonNull TabLayout tabLayout) {
        prepareTabs(tabLayout);
        final ViewGroup strip = tabLayout.getChildCount() > 0 && tabLayout.getChildAt(0) instanceof ViewGroup
                ? (ViewGroup) tabLayout.getChildAt(0) : null;
        if (strip == null || strip.getChildCount() == 0) {
            return tabLayout.requestFocus();
        }
        final int selected = Math.max(0, tabLayout.getSelectedTabPosition());
        return strip.getChildAt(Math.min(selected, strip.getChildCount() - 1)).requestFocus();
    }

    private static void prepareTabs(TabLayout tabLayout) {
        if (tabLayout.getChildCount() == 0 || !(tabLayout.getChildAt(0) instanceof ViewGroup)) {
            return;
        }
        final ViewGroup strip = (ViewGroup) tabLayout.getChildAt(0);
        for (int i = 0; i < strip.getChildCount(); i++) {
            final View tab = strip.getChildAt(i);
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setFocusableInTouchMode(true);
        }
    }

    private static boolean focusNearestAction(View root, View sourceContainer, boolean above) {
        final Rect source = screenRect(sourceContainer);
        final List<View> actions = new ArrayList<>();
        collectActions(root, sourceContainer, actions);
        View best = null;
        long bestScore = Long.MAX_VALUE;
        for (View candidate : actions) {
            final Rect rect = screenRect(candidate);
            final int verticalGap = above ? source.top - rect.bottom : rect.top - source.bottom;
            if (verticalGap < -8) {
                continue;
            }
            final int horizontalDistance = Math.abs(rect.centerX() - source.centerX());
            final long score = (long) Math.max(0, verticalGap) * 10000L + horizontalDistance;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best != null && best.requestFocus();
    }

    private static void collectActions(View view, View excludedSubtree, List<View> result) {
        if (view == excludedSubtree) {
            return;
        }
        if (isActuallyVisible(view) && view.isEnabled() && view.isClickable() && view.isFocusable()) {
            result.add(view);
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectActions(group.getChildAt(i), excludedSubtree, result);
            }
        }
    }

    private static <T extends View> void collectViews(View view, Class<T> type, List<T> result) {
        if (type.isInstance(view)) {
            result.add(type.cast(view));
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectViews(group.getChildAt(i), type, result);
            }
        }
    }

    private static Rect screenRect(View view) {
        final Rect visible = new Rect();
        if (view.getGlobalVisibleRect(visible)) {
            return visible;
        }
        final int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    private static boolean isActuallyVisible(View view) {
        final Rect visible = new Rect();
        return view.isShown() && view.getGlobalVisibleRect(visible)
                && visible.width() > 0 && visible.height() > 0;
    }

    private static List<View> collectToolbarActions(Toolbar toolbar) {
        final List<View> actions = new ArrayList<>();
        collectToolbarActions(toolbar, toolbar, actions);
        Collections.sort(actions, Comparator.comparingInt((View view) -> screenRect(view).centerX())
                .thenComparingInt(view -> screenRect(view).centerY()));
        return actions;
    }

    private static void collectToolbarActions(View rootToolbar, View view, List<View> result) {
        if (!isActuallyVisible(view)) {
            return;
        }
        if (view instanceof ViewGroup) {
            final int before = result.size();
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectToolbarActions(rootToolbar, group.getChildAt(i), result);
            }
            if (view != rootToolbar && result.size() > before) {
                return;
            }
        }
        if (view != rootToolbar && isToolbarActionCandidate(view)) {
            ensureTvActionReady(view);
            if (view.isFocusable()) {
                result.add(view);
            }
        }
    }

    private static boolean isLastDialogListRow(RecyclerView list, View row) {
        final int adapterPosition = list.getChildAdapterPosition(row);
        if (adapterPosition == RecyclerView.NO_POSITION || list.getAdapter() == null) {
            return false;
        }
        return adapterPosition >= list.getAdapter().getItemCount() - 1;
    }

    private static boolean focusPreferredDialogAction(View root) {
        final View preferred = findPreferredDialogAction(root);
        if (preferred != null && preferred.requestFocus()) {
            return true;
        }
        final View positive = findDialogActionByEntryName(root, "md_buttonDefaultPositive");
        if (positive != null && positive.requestFocus()) {
            return true;
        }
        final View neutral = findDialogActionByEntryName(root, "md_buttonDefaultNeutral");
        return neutral != null && neutral.requestFocus();
    }

    private static boolean isToolbarActionCandidate(View child) {
        return child.isClickable()
                || child.hasOnClickListeners()
                || child.isFocusable()
                || child.getContentDescription() != null;
    }

    private static boolean moveDialogListFocus(View root, RecyclerView list, View row, int direction) {
        final int adapterPosition = list.getChildAdapterPosition(row);
        if (adapterPosition == RecyclerView.NO_POSITION) {
            return false;
        }
        final int targetPosition = adapterPosition + direction;
        if (targetPosition < 0) {
            return false;
        }
        if (list.getAdapter() == null) {
            return false;
        }
        if (targetPosition >= list.getAdapter().getItemCount()) {
            return direction > 0 && focusPreferredDialogAction(root);
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            final View child = list.getChildAt(i);
            if (list.getChildAdapterPosition(child) == targetPosition) {
                child.setFocusable(true);
                child.setFocusableInTouchMode(true);
                return child.requestFocus();
            }
        }
        list.scrollToPosition(targetPosition);
        list.post(() -> {
            for (int i = 0; i < list.getChildCount(); i++) {
                final View child = list.getChildAt(i);
                if (list.getChildAdapterPosition(child) == targetPosition) {
                    child.setFocusable(true);
                    child.setFocusableInTouchMode(true);
                    child.requestFocus();
                    return;
                }
            }
            if (direction > 0) {
                focusPreferredDialogAction(root);
            }
        });
        return true;
    }

    private static void ensureTvActionReady(View view) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        ensureTvActionVisuals(view);
    }

    private static void ensureTvActionVisuals(View view) {
        if (view instanceof EditText) {
            return;
        }
        if (view.getForeground() == null) {
            view.setForeground(ContextCompat.getDrawable(view.getContext(), R.drawable.tv_control_focus));
        }
    }

    private static void prepareRecyclerChildForUpNavigation(RecyclerView recyclerView, View child, View upTarget) {
        child.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_UP) {
                return false;
            }
            if (isInFirstAdapterRow(recyclerView, view)) {
                return requestFirstFocusableDescendant(upTarget);
            }
            return false;
        });
    }

    private static boolean requestFirstFocusableDescendant(View root) {
        if (root == null) {
            return false;
        }
        if (root.isShown() && root.isEnabled() && root.isFocusable() && root.requestFocus()) {
            return true;
        }
        if (root instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (requestFirstFocusableDescendant(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isDescendant(View root, View view) {
        View current = view;
        while (current != null) {
            if (current == root) {
                return true;
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    private static <T extends View> T findAncestor(View view, Class<T> type) {
        View current = view;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return null;
    }
}
