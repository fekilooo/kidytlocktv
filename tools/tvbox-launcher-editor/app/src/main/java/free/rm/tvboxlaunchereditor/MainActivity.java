package free.rm.tvboxlaunchereditor;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends Activity {
    private static final String LAUNCHER_PACKAGE = "com.moons.mylauncher3";
    private static final String LAUNCHER_FILES =
            "/data/user/0/com.moons.mylauncher3/files";
    private static final String CONFIG_PATH = LAUNCHER_FILES + "/default_apps_items.json";
    private static final String ICON_DIR = LAUNCHER_FILES + "/custom_shortcut_icons";
    private static final String AES_KEY = "*P0K2QuUVV$ktQo8";
    private static final long CUSTOM_UPDATE_TIME = 999_999_999_999L;
    private static final int MAX_SHORTCUTS = 10;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<AppEntry> apps = new ArrayList<>();
    private final Map<String, AppEntry> appsByPackage = new LinkedHashMap<>();
    private final Set<String> selectedPackages = new LinkedHashSet<>();

    private ShortcutAdapter adapter;
    private ListView listView;
    private TextView statusView;
    private Button cancelButton;
    private Button saveButton;
    private boolean applyingChecks;
    private int cursorPosition;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadApps();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(16), dp(28), dp(20));
        root.setBackgroundColor(Color.rgb(18, 18, 18));

        final TextView title = new TextView(this);
        title.setText("精彩影視捷徑管理");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        statusView = new TextView(this);
        statusView.setText("正在讀取目前捷徑與已安裝 App...");
        statusView.setTextColor(Color.LTGRAY);
        statusView.setTextSize(17);
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        listView = new ListView(this);
        listView.setDividerHeight(1);
        listView.setFocusable(true);
        listView.setItemsCanFocus(false);
        adapter = new ShortcutAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            cursorPosition = position;
            toggleCurrentApp();
        });
        listView.setOnFocusChangeListener((view, hasFocus) -> adapter.notifyDataSetChanged());
        listView.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || apps.isEmpty()) {
                return false;
            }
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (cursorPosition > 0) {
                        moveCursor(cursorPosition - 1);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (cursorPosition < apps.size() - 1) {
                        moveCursor(cursorPosition + 1);
                    } else {
                        saveButton.requestFocus();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    cancelButton.requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    saveButton.requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    toggleCurrentApp();
                    return true;
                default:
                    return false;
            }
        });
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        cancelButton = createButton("取消");
        cancelButton.setOnClickListener(view -> finish());
        cancelButton.setOnKeyListener(this::handleActionButtonKey);
        actions.addView(cancelButton, new LinearLayout.LayoutParams(dp(120), dp(56)));

        saveButton = createButton("保存並回到 HOME");
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(view -> saveSelection());
        saveButton.setOnKeyListener(this::handleActionButtonKey);
        final LinearLayout.LayoutParams saveParams =
                new LinearLayout.LayoutParams(dp(210), dp(56));
        saveParams.setMargins(dp(16), 0, 0, 0);
        actions.addView(saveButton, saveParams);
        root.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));

        setContentView(root);
    }

    private Button createButton(final String text) {
        final Button button = new Button(this);
        button.setText(text);
        button.setTextSize(17);
        button.setFocusable(true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setTextColor(Color.BLACK);
        button.setOnFocusChangeListener((view, hasFocus) -> {
            view.setBackgroundColor(hasFocus ? Color.WHITE : Color.LTGRAY);
            view.setScaleX(hasFocus ? 1.08f : 1.0f);
            view.setScaleY(hasFocus ? 1.08f : 1.0f);
        });
        button.setBackgroundColor(Color.LTGRAY);
        return button;
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean handleActionButtonKey(final View view, final int keyCode,
                                          final KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            listView.requestFocus();
            moveCursor(Math.min(cursorPosition, Math.max(0, apps.size() - 1)));
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            cancelButton.requestFocus();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            saveButton.requestFocus();
            return true;
        }
        return false;
    }

    private void loadApps() {
        executor.execute(() -> {
            try {
                final List<String> currentPackages = readCurrentPackages();
                final PackageManager packageManager = getPackageManager();
                final Intent launcherIntent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER);
                final List<ResolveInfo> resolvedApps =
                        packageManager.queryIntentActivities(launcherIntent, 0);

                final Map<String, AppEntry> uniqueApps = new LinkedHashMap<>();
                for (final ResolveInfo resolveInfo : resolvedApps) {
                    final String packageName = resolveInfo.activityInfo.packageName;
                    if (LAUNCHER_PACKAGE.equals(packageName)) {
                        continue;
                    }
                    final AppEntry entry = new AppEntry(
                            resolveInfo.loadLabel(packageManager).toString(),
                            packageName,
                            resolveInfo.activityInfo.name,
                            resolveInfo.loadIcon(packageManager));
                    uniqueApps.put(packageName, entry);
                }

                final List<AppEntry> sortedApps = new ArrayList<>(uniqueApps.values());
                Collections.sort(sortedApps, Comparator.comparing(
                        entry -> entry.label.toLowerCase(Locale.ROOT)));

                runOnUiThread(() -> {
                    apps.clear();
                    apps.addAll(sortedApps);
                    appsByPackage.clear();
                    for (final AppEntry app : apps) {
                        appsByPackage.put(app.packageName, app);
                    }
                    selectedPackages.clear();
                    for (final String packageName : currentPackages) {
                        if (appsByPackage.containsKey(packageName)
                                && selectedPackages.size() < MAX_SHORTCUTS) {
                            selectedPackages.add(packageName);
                        }
                    }
                    adapter.notifyDataSetChanged();
                    saveButton.setEnabled(true);
                    updateStatus();
                    if (!apps.isEmpty()) {
                        cursorPosition = 0;
                        listView.setSelection(0);
                        listView.requestFocus();
                    }
                });
            } catch (final Exception error) {
                showError("無法讀取 Launcher 設定", error);
            }
        });
    }

    private List<String> readCurrentPackages() throws Exception {
        final String encrypted = runRootForOutput("cat", CONFIG_PATH).replaceAll("\\s", "");
        if (TextUtils.isEmpty(encrypted)) {
            return Collections.emptyList();
        }
        final byte[] cipherText = Base64.decode(encrypted, Base64.DEFAULT);
        final Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(AES_KEY.getBytes(StandardCharsets.UTF_8), "AES"));
        final JSONObject root = new JSONObject(
                new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8));
        final List<String> packages = new ArrayList<>();
        final JSONArray datas = root.optJSONArray("datas");
        if (datas == null) {
            return packages;
        }
        for (int dataIndex = 0; dataIndex < datas.length(); dataIndex++) {
            final JSONObject data = datas.optJSONObject(dataIndex);
            if (data == null || data.optInt("targetBlock", -1) != 0) {
                continue;
            }
            final JSONArray items = data.optJSONArray("items");
            if (items == null) {
                continue;
            }
            for (int itemIndex = 0; itemIndex < items.length(); itemIndex++) {
                final String packageName = items.optJSONObject(itemIndex)
                        .optString("packagename", "");
                if (!TextUtils.isEmpty(packageName)) {
                    packages.add(packageName);
                }
            }
        }
        return packages;
    }

    private void saveSelection() {
        if (selectedPackages.isEmpty()) {
            Toast.makeText(this, "請至少選擇 1 個 App", Toast.LENGTH_SHORT).show();
            return;
        }
        saveButton.setEnabled(false);
        statusView.setText("正在備份並寫入精彩捷徑...");
        executor.execute(() -> {
            try {
                final File stagingDir = new File(getFilesDir(), "staging");
                if (!stagingDir.exists() && !stagingDir.mkdirs()) {
                    throw new IllegalStateException("無法建立暫存資料夾");
                }
                final JSONArray items = new JSONArray();
                int index = 0;
                for (final String packageName : selectedPackages) {
                    final AppEntry app = appsByPackage.get(packageName);
                    if (app == null) {
                        continue;
                    }
                    final String iconName = sanitizePackageName(packageName) + ".png";
                    final File iconFile = new File(stagingDir, iconName);
                    saveDrawable(app.icon, iconFile);

                    int versionCode = 0;
                    try {
                        final PackageInfo packageInfo = getPackageManager()
                                .getPackageInfo(packageName, 0);
                        versionCode = packageInfo.versionCode;
                    } catch (final PackageManager.NameNotFoundException ignored) {
                    }

                    final JSONObject item = new JSONObject();
                    item.put("classname", app.className);
                    item.put("filename", "");
                    item.put("imgurl", "file://" + ICON_DIR + "/" + iconName);
                    item.put("packagename", packageName);
                    item.put("tag", String.valueOf(2001 + index));
                    item.put("tagupdatetime", String.valueOf(CUSTOM_UPDATE_TIME));
                    item.put("title", app.label);
                    item.put("url", "");
                    item.put("versionCode", versionCode);
                    items.put(item);
                    index++;
                }

                final JSONObject data = new JSONObject();
                data.put("items", items);
                data.put("modeName", "custom");
                data.put("targetBlock", 0);
                data.put("updateTime", CUSTOM_UPDATE_TIME);

                final JSONObject root = new JSONObject();
                root.put("datas", new JSONArray().put(data));
                root.put("message", "custom shortcuts");
                root.put("result", "ok");
                root.put("time", String.valueOf(CUSTOM_UPDATE_TIME));

                final Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
                cipher.init(Cipher.ENCRYPT_MODE,
                        new SecretKeySpec(AES_KEY.getBytes(StandardCharsets.UTF_8), "AES"));
                final String encrypted = Base64.encodeToString(
                        cipher.doFinal(root.toString().getBytes(StandardCharsets.UTF_8)),
                        Base64.NO_WRAP);
                final File configFile = new File(stagingDir, "default_apps_items.json");
                try (FileOutputStream output = new FileOutputStream(configFile)) {
                    output.write(encrypted.getBytes(StandardCharsets.UTF_8));
                }

                final String timestamp = new SimpleDateFormat(
                        "yyyyMMdd-HHmmss", Locale.US).format(new Date());
                runRoot("mkdir", "-p", "/sdcard/TVBoxLauncherEditorBackup");
                runRoot("cp", CONFIG_PATH,
                        "/sdcard/TVBoxLauncherEditorBackup/default_apps_items-"
                                + timestamp + ".json");
                runRoot("mkdir", "-p", ICON_DIR);
                for (final String packageName : selectedPackages) {
                    final String iconName = sanitizePackageName(packageName) + ".png";
                    final File iconFile = new File(stagingDir, iconName);
                    if (iconFile.exists()) {
                        final String targetIcon = ICON_DIR + "/" + iconName;
                        runRoot("cp", iconFile.getAbsolutePath(), targetIcon);
                        runRoot("chown", "1000:1000", targetIcon);
                        runRoot("chmod", "600", targetIcon);
                    }
                }
                runRoot("cp", configFile.getAbsolutePath(), CONFIG_PATH);
                runRoot("chown", "-R", "1000:1000", ICON_DIR);
                runRoot("chown", "1000:1000", CONFIG_PATH);
                runRoot("chmod", "700", ICON_DIR);
                runRoot("chmod", "600", CONFIG_PATH);
                runRoot("am", "force-stop", LAUNCHER_PACKAGE);

                runOnUiThread(() -> {
                    Toast.makeText(this, "精彩捷徑已更新", Toast.LENGTH_LONG).show();
                    final Intent home = new Intent(Intent.ACTION_MAIN);
                    home.addCategory(Intent.CATEGORY_HOME);
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(home);
                    finish();
                });
            } catch (final Exception error) {
                showError("保存失敗", error);
                runOnUiThread(() -> saveButton.setEnabled(true));
            }
        });
    }

    private void updateStatus() {
        statusView.setText("已選擇 " + selectedPackages.size()
                + " / " + MAX_SHORTCUTS
                + " 個 App。上下移動、OK 勾選、左右前往按鈕。");
    }

    private void moveCursor(final int position) {
        cursorPosition = Math.max(0, Math.min(position, apps.size() - 1));
        listView.setSelection(cursorPosition);
        listView.smoothScrollToPosition(cursorPosition);
        adapter.notifyDataSetChanged();
    }

    private void toggleCurrentApp() {
        if (applyingChecks || cursorPosition < 0 || cursorPosition >= apps.size()) {
            return;
        }
        final String packageName = apps.get(cursorPosition).packageName;
        if (selectedPackages.contains(packageName)) {
            selectedPackages.remove(packageName);
        } else {
            if (selectedPackages.size() >= MAX_SHORTCUTS) {
                Toast.makeText(this, "精彩選單最多顯示 10 個 App", Toast.LENGTH_SHORT).show();
                return;
            }
            selectedPackages.add(packageName);
        }
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    private void saveDrawable(final Drawable drawable, final File target) throws Exception {
        final int width = Math.max(1, Math.min(256, drawable.getIntrinsicWidth()));
        final int height = Math.max(1, Math.min(256, drawable.getIntrinsicHeight()));
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        try (FileOutputStream output = new FileOutputStream(target)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("無法輸出 App 圖示");
            }
        } finally {
            bitmap.recycle();
        }
    }

    private String sanitizePackageName(final String packageName) {
        return packageName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String runRootForOutput(final String... command) throws Exception {
        final Process process = new ProcessBuilder(prependRoot(command))
                .redirectErrorStream(true)
                .start();
        final StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        final int result = process.waitFor();
        if (result != 0) {
            throw new IllegalStateException("Root 指令失敗：" + output);
        }
        return output.toString();
    }

    private void runRoot(final String... command) throws Exception {
        runRootForOutput(command);
    }

    private String[] prependRoot(final String[] command) {
        final String[] rootCommand = new String[command.length + 2];
        rootCommand[0] = "su";
        rootCommand[1] = "0";
        System.arraycopy(command, 0, rootCommand, 2, command.length);
        return rootCommand;
    }

    private void showError(final String title, final Exception error) {
        runOnUiThread(() -> {
            statusView.setText(title + "：" + error.getMessage());
            Toast.makeText(this, title, Toast.LENGTH_LONG).show();
        });
    }

    private static final class AppEntry {
        final String label;
        final String packageName;
        final String className;
        final Drawable icon;

        AppEntry(final String label, final String packageName,
                 final String className, final Drawable icon) {
            this.label = label;
            this.packageName = packageName;
            this.className = className;
            this.icon = icon;
        }
    }

    private final class ShortcutAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public AppEntry getItem(final int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(final int position) {
            return position;
        }

        @Override
        public View getView(final int position, final View convertView,
                            final ViewGroup parent) {
            final TextView row = convertView instanceof TextView
                    ? (TextView) convertView : new TextView(MainActivity.this);
            final AppEntry app = getItem(position);
            final boolean checked = selectedPackages.contains(app.packageName);
            final boolean cursor = position == cursorPosition && listView.hasFocus();
            row.setText((checked ? "[X]  " : "[ ]  ") + app.label
                    + "\n       " + app.packageName);
            row.setTextSize(19);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(8), dp(14), dp(8));
            row.setMinHeight(dp(68));
            row.setTextColor(cursor ? Color.BLACK : Color.WHITE);
            row.setBackgroundColor(cursor ? Color.WHITE : Color.TRANSPARENT);
            return row;
        }
    }
}
