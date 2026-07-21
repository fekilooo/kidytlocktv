package free.rm.tvboxparentalcontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "parental_mode";
    private static final String PREF_PIN = "pin";
    private static final String PREF_HIDDEN = "hidden_packages";
    private static final String DEFAULT_PIN = "0000";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<AppEntry> apps = new ArrayList<>();
    private SharedPreferences preferences;
    private AppAdapter adapter;
    private TextView rootStatus;
    private ProgressBar progress;
    private Button applyButton;
    private Button restoreButton;
    private Button pinButton;
    private String launcherPackage;
    private boolean rootReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        bindViews();
        showPinDialog(false);
    }

    private void bindViews() {
        rootStatus = findViewById(R.id.rootStatus);
        progress = findViewById(R.id.progress);
        applyButton = findViewById(R.id.applyButton);
        restoreButton = findViewById(R.id.restoreButton);
        pinButton = findViewById(R.id.pinButton);
        ListView appList = findViewById(R.id.appList);
        adapter = new AppAdapter(this, apps);
        appList.setAdapter(adapter);
        appList.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry entry = adapter.getItem(position);
            if (!entry.protectedApp) {
                entry.selectedHidden = !entry.selectedHidden;
                adapter.notifyDataSetChanged();
            }
        });
        appList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                adapter.setSelectedPosition(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                adapter.setSelectedPosition(-1);
            }
        });

        applyButton.setOnClickListener(view -> applyChanges());
        restoreButton.setOnClickListener(view -> confirmRestoreAll());
        pinButton.setOnClickListener(view -> showPinDialog(true));
        setBusy(true);
    }

    private void showPinDialog(boolean changingPin) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint(changingPin ? R.string.pin_new : R.string.pin_default_hint);
        input.setPadding(32, 12, 32, 12);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(changingPin ? R.string.change_pin : R.string.pin_title)
                .setView(input)
                .setNegativeButton(R.string.cancel, (ignored, which) -> {
                    if (!changingPin) {
                        finish();
                    }
                })
                .setPositiveButton(R.string.confirm, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String value = input.getText().toString();
                    if (changingPin) {
                        if (!value.matches("\\d{4,8}")) {
                            input.setError(getString(R.string.pin_invalid));
                            return;
                        }
                        preferences.edit().putString(PREF_PIN, value).apply();
                        Toast.makeText(this, R.string.pin_changed, Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    } else if (value.equals(preferences.getString(PREF_PIN, DEFAULT_PIN))) {
                        dialog.dismiss();
                        initializeAppList();
                    } else {
                        input.setError(getString(R.string.pin_wrong));
                    }
                }));
        dialog.setOnCancelListener(ignored -> {
            if (!changingPin) {
                finish();
            }
        });
        dialog.getWindow();
        dialog.show();
    }

    private void initializeAppList() {
        setBusy(true);
        rootStatus.setText(R.string.loading);
        executor.execute(() -> {
            RootResult root = runRoot("id");
            List<AppEntry> loaded = loadLaunchableApps();
            runOnUiThread(() -> {
                rootReady = root.success && root.output.contains("uid=0");
                rootStatus.setText(rootReady ? R.string.root_ready : R.string.root_missing);
                rootStatus.setTextColor(rootReady ? getColor(R.color.accent) : Color.rgb(255, 96, 96));
                apps.clear();
                apps.addAll(loaded);
                adapter.notifyDataSetChanged();
                setBusy(false);
                applyButton.setEnabled(rootReady);
                restoreButton.setEnabled(rootReady);
                if (!rootReady) {
                    Toast.makeText(this, R.string.root_detail, Toast.LENGTH_LONG).show();
                } else if (apps.isEmpty()) {
                    Toast.makeText(this, R.string.no_apps, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private List<AppEntry> loadLaunchableApps() {
        PackageManager pm = getPackageManager();
        Map<String, AppEntry> results = new LinkedHashMap<>();
        Set<String> protectedPackages = getProtectedPackages(pm);
        Set<String> rememberedHidden = preferences.getStringSet(PREF_HIDDEN, Collections.emptySet());

        collectLauncherApps(pm, Intent.CATEGORY_LAUNCHER, results, protectedPackages, rememberedHidden);
        collectLauncherApps(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, results, protectedPackages, rememberedHidden);
        for (String packageName : rememberedHidden) {
            addPackage(pm, packageName, results, protectedPackages, rememberedHidden);
        }

        List<AppEntry> list = new ArrayList<>(results.values());
        list.sort(Comparator.comparing(entry -> entry.label.toLowerCase(Locale.getDefault())));
        return list;
    }

    private void collectLauncherApps(PackageManager pm, String category,
            Map<String, AppEntry> results, Set<String> protectedPackages, Set<String> rememberedHidden) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS);
        for (ResolveInfo info : resolved) {
            if (info.activityInfo != null) {
                addPackage(pm, info.activityInfo.packageName, results, protectedPackages, rememberedHidden);
            }
        }
    }

    private void addPackage(PackageManager pm, String packageName, Map<String, AppEntry> results,
            Set<String> protectedPackages, Set<String> rememberedHidden) {
        if (results.containsKey(packageName)) {
            return;
        }
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            String label = String.valueOf(pm.getApplicationLabel(info));
            Drawable icon = pm.getApplicationIcon(info);
            boolean hidden = rememberedHidden.contains(packageName) || isDisabled(pm, packageName);
            results.put(packageName, new AppEntry(packageName, label, icon,
                    protectedPackages.contains(packageName), hidden));
        } catch (PackageManager.NameNotFoundException ignored) {
            // A removed app can remain in preferences until the next successful apply.
        }
    }

    private Set<String> getProtectedPackages(PackageManager pm) {
        Set<String> protectedPackages = new HashSet<>();
        Collections.addAll(protectedPackages, getPackageName(), "android", "com.android.systemui",
                "com.android.settings", "com.google.android.packageinstaller",
                "com.android.packageinstaller", "com.google.android.permissioncontroller",
                "com.android.permissioncontroller");

        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo home = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        if (home != null && home.activityInfo != null) {
            launcherPackage = home.activityInfo.packageName;
            protectedPackages.add(launcherPackage);
        }

        InputMethodManager inputMethods = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (inputMethods != null) {
            for (InputMethodInfo inputMethod : inputMethods.getEnabledInputMethodList()) {
                protectedPackages.add(inputMethod.getPackageName());
            }
        }
        return protectedPackages;
    }

    private boolean isDisabled(PackageManager pm, String packageName) {
        int state = pm.getApplicationEnabledSetting(packageName);
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
    }

    private void applyChanges() {
        if (!rootReady) {
            return;
        }
        setBusy(true);
        rootStatus.setText(R.string.applying);
        List<AppEntry> snapshot = new ArrayList<>(apps);
        executor.execute(() -> {
            boolean allSucceeded = true;
            Set<String> hiddenPackages = new HashSet<>();
            for (AppEntry entry : snapshot) {
                if (entry.protectedApp) {
                    continue;
                }
                if (entry.selectedHidden != entry.initiallyHidden) {
                    String action = entry.selectedHidden ? "disable-user --user 0 " : "enable --user 0 ";
                    RootResult result = runRoot("pm " + action + entry.packageName);
                    if (result.success) {
                        entry.initiallyHidden = entry.selectedHidden;
                    } else {
                        allSucceeded = false;
                        entry.selectedHidden = entry.initiallyHidden;
                    }
                }
                if (entry.initiallyHidden) {
                    hiddenPackages.add(entry.packageName);
                }
            }
            preferences.edit().putStringSet(PREF_HIDDEN, hiddenPackages).commit();
            refreshLauncher();
            boolean success = allSucceeded;
            runOnUiThread(() -> {
                Toast.makeText(this, success ? R.string.applied : R.string.operation_failed,
                        Toast.LENGTH_LONG).show();
                initializeAppList();
            });
        });
    }

    private void confirmRestoreAll() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.restore_title)
                .setMessage(R.string.restore_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (ignored, which) -> restoreAll())
                .show();
    }

    private void restoreAll() {
        setBusy(true);
        rootStatus.setText(R.string.applying);
        Set<String> hidden = new HashSet<>(preferences.getStringSet(PREF_HIDDEN, Collections.emptySet()));
        executor.execute(() -> {
            boolean allSucceeded = true;
            Set<String> failed = new HashSet<>();
            for (String packageName : hidden) {
                if (isSafePackageName(packageName)) {
                    RootResult result = runRoot("pm enable --user 0 " + packageName);
                    if (!result.success) {
                        allSucceeded = false;
                        failed.add(packageName);
                    }
                }
            }
            preferences.edit().putStringSet(PREF_HIDDEN, failed).commit();
            refreshLauncher();
            boolean success = allSucceeded;
            runOnUiThread(() -> {
                Toast.makeText(this, success ? R.string.restored : R.string.operation_failed,
                        Toast.LENGTH_LONG).show();
                initializeAppList();
            });
        });
    }

    private void refreshLauncher() {
        if (isSafePackageName(launcherPackage)) {
            runRoot("am force-stop " + launcherPackage);
        }
    }

    private RootResult runRoot(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "0", "sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int exitCode = process.waitFor();
            return new RootResult(exitCode == 0, output.toString());
        } catch (Exception exception) {
            return new RootResult(false, exception.toString());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private boolean isSafePackageName(String packageName) {
        return packageName != null && packageName.matches("[A-Za-z0-9._]+");
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        applyButton.setEnabled(!busy && rootReady);
        restoreButton.setEnabled(!busy && rootReady);
        pinButton.setEnabled(!busy);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private static final class AppEntry {
        final String packageName;
        final String label;
        final Drawable icon;
        final boolean protectedApp;
        boolean initiallyHidden;
        boolean selectedHidden;

        AppEntry(String packageName, String label, Drawable icon, boolean protectedApp, boolean hidden) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.protectedApp = protectedApp;
            this.initiallyHidden = hidden;
            this.selectedHidden = hidden;
        }
    }

    private final class AppAdapter extends BaseAdapter {
        private final LayoutInflater inflater;
        private final List<AppEntry> entries;
        private int selectedPosition = -1;

        AppAdapter(Context context, List<AppEntry> entries) {
            inflater = LayoutInflater.from(context);
            this.entries = entries;
        }

        @Override public int getCount() { return entries.size(); }
        @Override public AppEntry getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        void setSelectedPosition(int position) {
            if (selectedPosition != position) {
                selectedPosition = position;
                notifyDataSetChanged();
            }
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.app_item, parent, false);
                holder = new ViewHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            AppEntry entry = getItem(position);
            boolean selected = position == selectedPosition;
            convertView.setSelected(selected);
            holder.focusMarker.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            holder.icon.setImageDrawable(entry.icon);
            holder.label.setText(entry.label);
            holder.label.setSelected(selected);
            holder.packageName.setText(entry.packageName);
            holder.check.setChecked(entry.selectedHidden);
            holder.check.setVisibility(entry.protectedApp ? View.INVISIBLE : View.VISIBLE);
            holder.state.setText(entry.protectedApp ? R.string.protected_app
                    : entry.selectedHidden ? R.string.hidden : R.string.visible);
            holder.state.setTextColor(entry.selectedHidden && !entry.protectedApp
                    ? Color.rgb(255, 152, 80) : getColor(R.color.text_secondary));
            convertView.setAlpha(entry.protectedApp ? 0.62f : 1f);
            return convertView;
        }
    }

    private static final class ViewHolder {
        final View focusMarker;
        final ImageView icon;
        final TextView label;
        final TextView packageName;
        final TextView state;
        final CheckBox check;

        ViewHolder(View view) {
            focusMarker = view.findViewById(R.id.focusMarker);
            icon = view.findViewById(R.id.appIcon);
            label = view.findViewById(R.id.appLabel);
            packageName = view.findViewById(R.id.packageName);
            state = view.findViewById(R.id.appState);
            check = view.findViewById(R.id.hiddenCheck);
        }
    }

    private static final class RootResult {
        final boolean success;
        final String output;

        RootResult(boolean success, String output) {
            this.success = success;
            this.output = output;
        }
    }
}
