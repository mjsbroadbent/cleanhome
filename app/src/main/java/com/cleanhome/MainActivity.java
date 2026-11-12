package com.cleanhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A home screen that shows only the apps you pick, in one centred row.
 *
 *   OK            open the app
 *   hold OK       move the tile left/right, or remove it
 *   Menu button   choose which apps appear (also "Edit apps" at the bottom)
 */
public class MainActivity extends Activity {

    private static final String PREFS = "home";
    private static final String KEY_APPS = "apps";

    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    /** Chosen apps, in display order, as flattened component names. */
    private final List<String> chosen = new ArrayList<>();
    private LinearLayout row;
    private int focusIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(64), 0, dp(64), 0);
        row.setClipChildren(false);
        row.setClipToPadding(false);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setFillViewport(true);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.addView(row, new FrameLayout.LayoutParams(WRAP, MATCH));
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.addView(footerButton("Edit apps", new View.OnClickListener() {
            @Override public void onClick(View v) { pickApps(); }
        }));
        footer.addView(footerButton("Lights", new View.OnClickListener() {
            @Override public void onClick(View v) { lights(); }
        }));
        footer.addView(footerButton("Settings", new View.OnClickListener() {
            @Override public void onClick(View v) { open(new Intent(Settings.ACTION_SETTINGS)); }
        }));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        flp.bottomMargin = dp(24);
        root.addView(footer, flp);

        setContentView(root);
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    /** Home is the bottom of the stack: Back does nothing. */
    @Override
    public void onBackPressed() {
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            pickApps();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- tiles

    private void render() {
        row.removeAllViews();
        for (String comp : chosen) {
            View tile = tile(comp);
            if (tile != null) row.addView(tile);
        }
        if (row.getChildCount() == 0) {
            row.addView(emptyTile());
        }
        int i = Math.max(0, Math.min(focusIndex, row.getChildCount() - 1));
        row.getChildAt(i).requestFocus();
    }

    /** One app tile, or null if the app is no longer installed. */
    private View tile(final String comp) {
        final ComponentName cn = ComponentName.unflattenFromString(comp);
        if (cn == null) return null;

        PackageManager pm = getPackageManager();
        final CharSequence label;
        Drawable art;
        boolean hasBanner = true;
        try {
            ActivityInfo ai = pm.getActivityInfo(cn, 0);
            label = ai.loadLabel(pm);
            art = ai.loadBanner(pm);
            if (art == null) art = ai.applicationInfo.loadBanner(pm);
            if (art == null) {
                hasBanner = false;
                art = ai.loadIcon(pm);
            }
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }

        ImageView image = new ImageView(this);
        image.setImageDrawable(art);
        if (hasBanner) {
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setPadding(dp(36), dp(36), dp(36), dp(36));
        }

        final View cell = cell(image, hasBanner ? null : label);
        cell.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setComponent(cn);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                open(i);
            }
        });
        cell.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                tileMenu(comp, label);
                return true;
            }
        });
        return cell;
    }

    /** Shown when nothing has been picked yet. */
    private View emptyTile() {
        TextView t = new TextView(this);
        t.setText("Choose apps");
        t.setTextSize(20);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        View cell = cell(t, null);
        cell.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickApps(); }
        });
        return cell;
    }

    /**
     * The shared tile shell: a rounded card with a white outline and slight
     * zoom when focused, and an optional caption underneath.
     */
    private View cell(View content, CharSequence caption) {
        final FrameLayout card = new FrameLayout(this);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF1A1A1A);
        cardBg.setCornerRadius(dp(12));
        card.setBackground(cardBg);
        card.setClipToOutline(true);
        card.addView(content, new FrameLayout.LayoutParams(MATCH, MATCH));

        final GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(dp(16));
        ring.setStroke(dp(3), Color.TRANSPARENT);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(ring);
        frame.setPadding(dp(5), dp(5), dp(5), dp(5));
        frame.addView(card, new FrameLayout.LayoutParams(dp(256), dp(144)));

        // The caption is always laid out (invisible when unused) so that
        // banner tiles and icon tiles sit on the same line.
        TextView text = new TextView(this);
        text.setText(caption == null ? "" : caption);
        text.setTextSize(16);
        text.setTextColor(Color.WHITE);
        text.setGravity(Gravity.CENTER);
        text.setSingleLine(true);
        text.setPadding(0, dp(8), 0, 0);
        text.setVisibility(caption == null ? View.INVISIBLE : View.VISIBLE);

        final LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.addView(frame, new LinearLayout.LayoutParams(WRAP, WRAP));
        cell.addView(text, new LinearLayout.LayoutParams(dp(256), WRAP));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        cell.setLayoutParams(lp);

        cell.setFocusable(true);
        cell.setFocusableInTouchMode(true);
        cell.setClickable(true);
        cell.setAlpha(0.6f);
        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                ring.setStroke(dp(3), focused ? Color.WHITE : Color.TRANSPARENT);
                float scale = focused ? 1.08f : 1f;
                v.animate().scaleX(scale).scaleY(scale)
                        .alpha(focused ? 1f : 0.6f).setDuration(120).start();
                if (focused) {
                    int i = row.indexOfChild(v);
                    if (i >= 0) focusIndex = i;
                }
            }
        });
        return cell;
    }

    private View footerButton(String label, View.OnClickListener onClick) {
        final TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setTextColor(0xFF666666);
        t.setPadding(dp(20), dp(10), dp(20), dp(10));
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setClickable(true);
        t.setOnClickListener(onClick);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                t.setTextColor(focused ? Color.WHITE : 0xFF666666);
            }
        });
        return t;
    }

    // ---------------------------------------------------------------- menus

    private void tileMenu(final String comp, CharSequence label) {
        CharSequence[] items = {"Move left", "Move right", "Remove from home"};
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        int i = chosen.indexOf(comp);
                        if (i < 0) return;
                        if (which == 0 && i > 0) {
                            Collections.swap(chosen, i, i - 1);
                            focusIndex = i - 1;
                        } else if (which == 1 && i < chosen.size() - 1) {
                            Collections.swap(chosen, i, i + 1);
                            focusIndex = i + 1;
                        } else if (which == 2) {
                            chosen.remove(i);
                        }
                        save();
                        render();
                    }
                })
                .show();
    }

    /** Tick-list of every app that can be opened from a launcher. */
    private void pickApps() {
        final PackageManager pm = getPackageManager();

        // One entry per package; the TV (leanback) entry wins over the phone one.
        final Map<String, ResolveInfo> byPackage = new LinkedHashMap<>();
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String category : categories) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo r : pm.queryIntentActivities(query, 0)) {
                String pkg = r.activityInfo.packageName;
                if (!pkg.equals(getPackageName()) && !byPackage.containsKey(pkg)) {
                    byPackage.put(pkg, r);
                }
            }
        }

        final List<String[]> apps = new ArrayList<>();   // {label, package, component}
        for (ResolveInfo r : byPackage.values()) {
            ComponentName cn = new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
            apps.add(new String[]{
                    String.valueOf(r.loadLabel(pm)), cn.getPackageName(), cn.flattenToString()});
        }
        Collections.sort(apps, new java.util.Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) {
                return a[0].compareToIgnoreCase(b[0]);
            }
        });

        final CharSequence[] labels = new CharSequence[apps.size()];
        final boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i)[0];
            checked[i] = indexOfPackage(apps.get(i)[1]) >= 0;
        }

        new AlertDialog.Builder(this)
                .setTitle("Apps on the home screen")
                .setMultiChoiceItems(labels, checked,
                        new android.content.DialogInterface.OnMultiChoiceClickListener() {
                            @Override public void onClick(android.content.DialogInterface d,
                                                          int which, boolean isChecked) {
                                checked[which] = isChecked;
                            }
                        })
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Done", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        // Un-ticked apps drop out; newly ticked ones go on the end,
                        // so the order you already arranged is kept.
                        for (int i = 0; i < apps.size(); i++) {
                            int at = indexOfPackage(apps.get(i)[1]);
                            if (checked[i] && at < 0) chosen.add(apps.get(i)[2]);
                            if (!checked[i] && at >= 0) chosen.remove(at);
                        }
                        save();
                        render();
                    }
                })
                .show();
    }

    // --------------------------------------------------------------- lights

    private void lights() {
        if (!Hue.hasKey(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Pair lights")
                    .setMessage("Press the round button on the Hue bridge, then choose Pair "
                            + "within 30 seconds.\n\nAfterwards, Channel Up and Channel Down "
                            + "on the remote brighten and dim the room you pick.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Pair", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            pairLights();
                        }
                    })
                    .show();
            return;
        }
        CharSequence[] items = {"Change room (now: " + Hue.roomName(this) + ")", "Unpair lights"};
        new AlertDialog.Builder(this)
                .setTitle("Lights")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            chooseRoom();
                        } else {
                            Hue.unpair(MainActivity.this);
                            toast("Lights unpaired. Channel buttons are back to normal.");
                        }
                    }
                })
                .show();
    }

    private void pairLights() {
        toast("Looking for the Hue bridge\u2026");
        Hue.EXEC.execute(new Runnable() {
            @Override public void run() {
                String problem = null;
                try {
                    Hue.pair(MainActivity.this);
                } catch (Exception e) {
                    problem = e.getMessage();
                }
                final String message = problem;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (message == null) chooseRoom(); else toast(message);
                    }
                });
            }
        });
    }

    private void chooseRoom() {
        Hue.EXEC.execute(new Runnable() {
            @Override public void run() {
                List<String[]> found = null;
                try {
                    found = Hue.rooms(MainActivity.this);
                } catch (Exception ignored) {
                }
                final List<String[]> rooms = found;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (rooms == null) {
                            toast("Couldn't reach the Hue bridge");
                            return;
                        }
                        if (rooms.isEmpty()) {
                            toast("No rooms are set up on the Hue bridge");
                            return;
                        }
                        CharSequence[] names = new CharSequence[rooms.size()];
                        for (int i = 0; i < names.length; i++) names[i] = rooms.get(i)[1];
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Which room?")
                                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int which) {
                                        String[] room = rooms.get(which);
                                        Hue.setRoom(MainActivity.this, room[0], room[1]);
                                        toast("Channel buttons now control " + room[1]);
                                    }
                                })
                                .show();
                    }
                });
            }
        });
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private int indexOfPackage(String pkg) {
        for (int i = 0; i < chosen.size(); i++) {
            ComponentName cn = ComponentName.unflattenFromString(chosen.get(i));
            if (cn != null && cn.getPackageName().equals(pkg)) return i;
        }
        return -1;
    }

    // -------------------------------------------------------------- helpers

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show();
        }
    }

    private void load() {
        chosen.clear();
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_APPS, "");
        for (String s : saved.split("\n")) {
            if (!s.isEmpty()) chosen.add(s);
        }
    }

    private void save() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_APPS, TextUtils.join("\n", chosen)).apply();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
