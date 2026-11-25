package com.aure.totk.setup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pre-game Galaxy S25+ QoL & Performance Tweaks editor.
 * Reads and writes <game>/totk.env (applied after settings.env) while preserving any manual developer lines. */
final class S25Tweaks {
    private static final class Entry {
        final String key, label, hint;
        final String[] labels, values; // "" value means unset / omit key
        int idx;
        TextView valueView;

        Entry(String key, String label, String hint, String[] labels, String[] values) {
            this.key = key; this.label = label; this.hint = hint;
            this.labels = labels; this.values = values;
        }
    }

    static void show(Activity activity) {
        File gameDir = InstallState.gameDir(activity);
        if (gameDir == null) return;
        File envFile = new File(gameDir, "totk.env");
        Map<String, String> existing = readEnv(envFile);

        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("TOTK_S25_PROFILE", "S25+ Auto-Tuner",
                "API 37 ADPF + Adreno 830 Vulkan + Oryon v2 threading",
                new String[]{"On (Default)", "Off (Stock)"},
                new String[]{"", "0"}));
        entries.add(new Entry("TOTK_ASPECT", "Screen Aspect Ratio",
                "Fill the Galaxy S25+ display without side black bars",
                new String[]{"16:9 (Default)", "19.5:9 (S25+ Fullscreen)", "21:9 (Ultrawide)"},
                new String[]{"", "19.5:9", "21:9"}));
        entries.add(new Entry("TOTK_AUTOLOAD", "Autoload Latest Save",
                "Skip the title screen straight into your newest save",
                new String[]{"Off", "On"},
                new String[]{"", "1"}));
        entries.add(new Entry("TOTK_SHADOW_CASCADES", "Shadow Cascades",
                "Fewer cascades = big CPU & GPU boost in Lookout Landing / Korok Forest",
                new String[]{"3 (S25+ Default)", "2 (Max FPS)", "4 (Game Full)"},
                new String[]{"", "2", "0"}));
        entries.add(new Entry("TOTK_POINT_SHADOWS", "Point-Light Shadows",
                "Cap dynamic point-light shadow casters",
                new String[]{"2 (S25+ Default)", "1 (Max FPS)", "Game Full"},
                new String[]{"", "1", "64"}));
        entries.add(new Entry("TOTK_FAR_CLAMP", "Draw Distance Clamp",
                "Trim far render camera distance (gameplay & physics unchanged)",
                new String[]{"Default (Full)", "1500m", "1000m (Fast)", "750m (Max FPS)"},
                new String[]{"", "1500", "1000", "750"}));
        entries.add(new Entry("TOTK_OVERLAY_SYS", "Full Thermal/CPU/GPU Overlay",
                "Add thermal headroom + CPU/GPU rows when Performance Overlay = Full",
                new String[]{"Off", "On"},
                new String[]{"", "1"}));
        entries.add(new Entry("TOTK_MENU_CAPTURE_FIX", "Ability Wheel Blur Fix (PR #44)",
                "Prevents random solid-black backgrounds on ability wheel & quick-select",
                new String[]{"On (Default)", "Off"},
                new String[]{"", "0"}));
        entries.add(new Entry("TOTK_TOUCH_UI", "On-Screen Touch UI",
                "Show draggable UI pill in-game (tap pill to hide/show buttons; Off hides even the pill)",
                new String[]{"On (Show UI Pill)", "Off (Hide Completely)"},
                new String[]{"", "0"}));
        entries.add(new Entry("TOTK_GYRO_SOURCE", "Gyro Motion Source (Eden DSU)",
                "Auto: Switch 2 controller via Joycon2Android (127.0.0.1:26760), falls back to phone gyro",
                new String[]{"Auto (DSU -> Phone)", "DSU Controller Only", "Phone Gyro Only"},
                new String[]{"", "dsu", "phone"}));

        for (Entry e : entries) {
            String cur = existing.getOrDefault(e.key, "");
            e.idx = 0;
            for (int i = 0; i < e.values.length; i++) {
                if (e.values[i].equals(cur)) { e.idx = i; break; }
            }
        }

        float dp = activity.getResources().getDisplayMetrics().density;
        ScrollView scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(14 * dp);
        list.setPadding(pad, Math.round(8 * dp), pad, pad);
        list.setBackgroundColor(0xFF0E141B);
        scroll.addView(list);

        for (Entry e : entries) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int rp = Math.round(10 * dp);
            row.setPadding(rp, rp, rp, rp);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF17212C);
            bg.setCornerRadius(10 * dp);
            row.setBackground(bg);

            LinearLayout left = new LinearLayout(activity);
            left.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(activity);
            title.setText(e.label);
            title.setTextColor(Color.WHITE);
            title.setTextSize(14f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            TextView sub = new TextView(activity);
            sub.setText(e.hint);
            sub.setTextColor(0xFF9FB0C3);
            sub.setTextSize(11.5f);
            left.addView(title);
            left.addView(sub);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(left, llp);

            TextView val = new TextView(activity);
            val.setText(e.labels[e.idx]);
            val.setTextColor(0xFF79C0FF);
            val.setTextSize(13f);
            val.setTypeface(Typeface.DEFAULT_BOLD);
            val.setPadding(Math.round(10 * dp), Math.round(6 * dp), Math.round(10 * dp), Math.round(6 * dp));
            GradientDrawable vbg = new GradientDrawable();
            vbg.setColor(0xFF223244);
            vbg.setCornerRadius(8 * dp);
            val.setBackground(vbg);
            e.valueView = val;
            row.addView(val);

            row.setOnClickListener(v -> {
                e.idx = (e.idx + 1) % e.values.length;
                e.valueView.setText(e.labels[e.idx]);
            });

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = Math.round(8 * dp);
            list.addView(row, rlp);
        }

        TextView testBtn = new TextView(activity);
        testBtn.setText("Test Joycon2Android DSU Gyro (127.0.0.1:26760)");
        testBtn.setTextColor(Color.WHITE);
        testBtn.setTextSize(13.5f);
        testBtn.setTypeface(Typeface.DEFAULT_BOLD);
        testBtn.setGravity(Gravity.CENTER);
        int tp = Math.round(11 * dp);
        testBtn.setPadding(tp, tp, tp, tp);
        GradientDrawable tbg = new GradientDrawable();
        tbg.setColor(0xFF1F6FEB);
        tbg.setCornerRadius(10 * dp);
        testBtn.setBackground(tbg);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Math.round(12 * dp);
        testBtn.setOnClickListener(v -> {
            testBtn.setText("Testing 127.0.0.1:26760...");
            new Thread(() -> {
                String res = com.aure.totk.DsuMotionClient.probeOnce(
                        com.aure.totk.DsuMotionClient.DEFAULT_HOST,
                        com.aure.totk.DsuMotionClient.DEFAULT_PORT);
                activity.runOnUiThread(() -> {
                    testBtn.setText("Test Joycon2Android DSU Gyro (127.0.0.1:26760)");
                    new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("DSU Gyro Test Result")
                            .setMessage(res)
                            .setPositiveButton("OK", null)
                            .show();
                });
            }, "totk-dsu-probe").start();
        });
        list.addView(testBtn, tlp);

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("S25+ Tweaks & QoL (totk.env)")
                .setView(scroll)
                .setPositiveButton("Save", (d, w) -> saveEnv(envFile, existing, entries))
                .setNeutralButton("Share totk.log", (d, w) -> shareLog(activity))
                .setNegativeButton("Cancel", null)
                .show();
    }

    static void shareLog(Activity activity) {
        try {
            File dir = InstallState.gameDir(activity);
            File log = dir != null ? new File(dir, "totk.log") : new File(activity.getFilesDir(), "game/totk.log");
            if (!log.isFile()) log = new File(activity.getFilesDir(), "game/totk.log");
            String text;
            if (!log.isFile()) {
                text = "totk.log not found yet (checked " + log.getAbsolutePath() + "). Launch the game once first.";
            } else {
                long len = log.length();
                int keep = (int) Math.min(len, 24576L);
                byte[] buf = new byte[keep];
                try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
                    raf.seek(len - keep);
                    raf.readFully(buf);
                }
                text = new String(buf, StandardCharsets.UTF_8);
            }
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "TotK Native S25+ totk.log");
            send.putExtra(Intent.EXTRA_TEXT, text);
            activity.startActivity(Intent.createChooser(send, "Share totk.log"));
        } catch (Throwable ignored) {}
    }

    private static Map<String, String> readEnv(File file) {
        Map<String, String> map = new LinkedHashMap<>();
        if (!file.isFile()) return map;
        try {
            for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (Throwable ignored) {}
        return map;
    }

    private static void saveEnv(File file, Map<String, String> existing, List<Entry> entries) {
        Map<String, String> out = new LinkedHashMap<>(existing);
        for (Entry e : entries) {
            String val = e.values[e.idx];
            if (val.isEmpty()) out.remove(e.key);
            else out.put(e.key, val);
        }
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            StringBuilder sb = new StringBuilder("# Managed by S25+ Tweaks & QoL (manual lines are preserved)\n");
            for (Map.Entry<String, String> kv : out.entrySet()) {
                sb.append(kv.getKey()).append('=').append(kv.getValue()).append('\n');
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                fos.getFD().sync();
            }
        } catch (Throwable ignored) {}
    }
}
