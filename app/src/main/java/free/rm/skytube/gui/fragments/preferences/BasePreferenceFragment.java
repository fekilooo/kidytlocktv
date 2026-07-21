/*
 * SkyTube
 * Copyright (C) 2021  Zsombor Gegesy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation (version 3 of the License).
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package free.rm.skytube.gui.fragments.preferences;

import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.EditTextPreference;
import androidx.preference.EditTextPreferenceDialogFragmentCompat;
import androidx.preference.ListPreference;
import androidx.preference.ListPreferenceDialogFragmentCompat;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.MultiSelectListPreferenceDialogFragmentCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import free.rm.skytube.app.SkyTubeApp;
import free.rm.skytube.gui.activities.PreferencesActivity;
import free.rm.skytube.gui.businessobjects.PinUtils;
import free.rm.skytube.gui.businessobjects.TvFocusHelper;

/**
 * Base class for Preference pages, which wants to act after the preference are saved.
 */
abstract class BasePreferenceFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String PREFERENCE_DIALOG_FRAGMENT_TAG =
            "androidx.preference.PreferenceFragment.DIALOG";

    @Override
    public void onCreatePreferences(android.os.Bundle savedInstanceState, String rootKey) {
        boolean alreadyVerified = getActivity() instanceof PreferencesActivity
                && ((PreferencesActivity) getActivity()).isPinVerifiedForSession();
        if (SkyTubeApp.getSettings().isPinSet() && !alreadyVerified) {
            PinUtils.promptForPin(getContext(),
                () -> showPreferencesInternal(rootKey),
                () -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        } else {
            showPreferencesInternal(rootKey);
        }
    }

    protected abstract void showPreferencesInternal(String rootKey);

    @Override
    public void onDisplayPreferenceDialog(@NonNull Preference preference) {
        final FragmentManager fragmentManager = getParentFragmentManager();
        if (fragmentManager.findFragmentByTag(PREFERENCE_DIALOG_FRAGMENT_TAG) != null) {
            return;
        }

        final DialogFragment dialogFragment;
        if (preference instanceof EditTextPreference) {
            dialogFragment = TvEditTextPreferenceDialogFragment.newInstance(preference.getKey());
        } else if (preference instanceof ListPreference) {
            dialogFragment = TvListPreferenceDialogFragment.newInstance(preference.getKey());
        } else if (preference instanceof MultiSelectListPreference) {
            dialogFragment = TvMultiSelectListPreferenceDialogFragment.newInstance(preference.getKey());
        } else {
            super.onDisplayPreferenceDialog(preference);
            return;
        }

        dialogFragment.setTargetFragment(this, 0);
        dialogFragment.show(fragmentManager, PREFERENCE_DIALOG_FRAGMENT_TAG);
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    public static class TvEditTextPreferenceDialogFragment extends EditTextPreferenceDialogFragmentCompat {
        public static TvEditTextPreferenceDialogFragment newInstance(String key) {
            final TvEditTextPreferenceDialogFragment fragment = new TvEditTextPreferenceDialogFragment();
            final Bundle args = new Bundle(1);
            args.putString(ARG_KEY, key);
            fragment.setArguments(args);
            return fragment;
        }

        @NonNull
        @Override
        public AppCompatDialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            AppCompatDialog dialog = (AppCompatDialog) super.onCreateDialog(savedInstanceState);
            dialog.setOnShowListener(ignored -> TvFocusHelper.prepareDialog(dialog));
            return dialog;
        }
    }

    public static class TvListPreferenceDialogFragment extends ListPreferenceDialogFragmentCompat {
        public static TvListPreferenceDialogFragment newInstance(String key) {
            final TvListPreferenceDialogFragment fragment = new TvListPreferenceDialogFragment();
            final Bundle args = new Bundle(1);
            args.putString(ARG_KEY, key);
            fragment.setArguments(args);
            return fragment;
        }

        @NonNull
        @Override
        public AppCompatDialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            AppCompatDialog dialog = (AppCompatDialog) super.onCreateDialog(savedInstanceState);
            dialog.setOnShowListener(ignored -> TvFocusHelper.prepareDialog(dialog));
            return dialog;
        }
    }

    public static class TvMultiSelectListPreferenceDialogFragment extends MultiSelectListPreferenceDialogFragmentCompat {
        public static TvMultiSelectListPreferenceDialogFragment newInstance(String key) {
            final TvMultiSelectListPreferenceDialogFragment fragment = new TvMultiSelectListPreferenceDialogFragment();
            final Bundle args = new Bundle(1);
            args.putString(ARG_KEY, key);
            fragment.setArguments(args);
            return fragment;
        }

        @NonNull
        @Override
        public AppCompatDialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            AppCompatDialog dialog = (AppCompatDialog) super.onCreateDialog(savedInstanceState);
            dialog.setOnShowListener(ignored -> TvFocusHelper.prepareDialog(dialog));
            return dialog;
        }
    }

}
