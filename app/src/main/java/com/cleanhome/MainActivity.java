package com.cleanhome;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
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
 * A home screen that shows only the apps you pick, in a grid three across
 * (sized so that nine tiles, three rows, fill the screen).
 *
 *   OK            open the app
 *   hold OK       move the tile earlier/later, or remove it
 *   Menu button   choose which apps appear (also "Edit apps" at the bottom)
 */
public class MainActivity extends Activity {

    private static final String PREFS = "home";
    private static final String KEY_APPS = "apps";

    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    /** Chosen apps, in display order, as flattened component names. */
    private final List<String> chosen = new ArrayList<>();
    private static final int COLUMNS = 3;

    private LinearLayout grid;
    /** The tiles on screen, in reading order. */
    private final List<View> tiles = new ArrayList<>();
    private int focusIndex = 0;

    /** The glow under the focused tile, and the animation that makes it shimmer. */
    private View glowView;
    private AnimatorSet glowAnim;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);

        // The grid sits in the middle of everything above the footer.
        FrameLayout area = new FrameLayout(this);
        area.setClipChildren(false);
        area.addView(grid, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        root.addView(area, new LinearLayout.LayoutParams(MATCH, 0, 1f));

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
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(WRAP, WRAP);
        flp.gravity = Gravity.CENTER_HORIZONTAL;
        flp.bottomMargin = dp(8);
        root.addView(footer, flp);

        setContentView(root);
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopGlow();     // no point animating while another app is on screen
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
        grid.removeAllViews();
        stopGlow();
        tiles.clear();
        for (String comp : chosen) {
            View tile = tile(comp);
            if (tile != null) tiles.add(tile);
        }
        if (tiles.isEmpty()) {
            tiles.add(emptyTile());
        }

        // Lay the tiles out three to a line. Lines are left-aligned, so a
        // part-filled last line still reads as a grid.
        LinearLayout line = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % COLUMNS == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setClipChildren(false);
                grid.addView(line, new LinearLayout.LayoutParams(WRAP, WRAP));
            }
            line.addView(tiles.get(i));
        }

        int i = Math.max(0, Math.min(focusIndex, tiles.size() - 1));
        tiles.get(i).requestFocus();
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
        View content;
        if (hasBanner) {
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            content = image;
        } else {
            // No TV banner: show the app's icon with its name underneath.
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            TextView name = new TextView(this);
            name.setText(label);
            name.setTextSize(13);
            name.setTextColor(Color.WHITE);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setPadding(dp(8), dp(6), dp(8), 0);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.addView(image, new LinearLayout.LayoutParams(dp(52), dp(52)));
            box.addView(name, new LinearLayout.LayoutParams(MATCH, WRAP));
            content = box;
        }

        final View cell = cell(content);
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
        View cell = cell(t);
        cell.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickApps(); }
        });
        return cell;
    }

    /**
     * The shared tile shell: a rounded card that, when focused, gets a white
     * outline, a slight zoom and a shimmering glow along its bottom edge.
     */
    private View cell(View content) {
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
        frame.addView(card, new FrameLayout.LayoutParams(dp(208), dp(117)));

        // The glow is a soft round light, squashed flat and centred on the
        // card's bottom edge. It is drawn behind the card, so only the half
        // spilling out underneath is seen.
        final View glow = new View(this);
        GradientDrawable light = new GradientDrawable();
        light.setShape(GradientDrawable.OVAL);
        light.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        light.setGradientRadius(dp(60));
        light.setColors(new int[]{0xE6FFFFFF, 0x55CFE4FF, 0x00FFFFFF});
        glow.setBackground(light);
        glow.setAlpha(0f);
        glow.setScaleX(1.5f);
        glow.setScaleY(0.4f);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                dp(120), dp(120), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        glp.topMargin = dp(5 + 117 - 60);

        final FrameLayout cell = new FrameLayout(this);
        cell.setClipChildren(false);
        cell.addView(glow, glp);
        cell.addView(frame, new FrameLayout.LayoutParams(
                WRAP, WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(218), dp(127));
        lp.leftMargin = dp(10);
        lp.rightMargin = dp(10);
        lp.topMargin = dp(7);
        lp.bottomMargin = dp(7);
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
                    int i = tiles.indexOf(v);
                    if (i >= 0) focusIndex = i;
                    startGlow(glow);
                } else if (glowView == glow) {
                    stopGlow();
                }
            }
        });
        return cell;
    }

    /**
     * Shimmer: the glow's brightness and its width pulse at two different
     * speeds, so the light never repeats in an obvious beat.
     */
    private void startGlow(View glow) {
        stopGlow();
        glowView = glow;
        ObjectAnimator bright = ObjectAnimator.ofFloat(glow, "alpha", 0.35f, 1f);
        bright.setDuration(1100);
        ObjectAnimator width = ObjectAnimator.ofFloat(glow, "scaleX", 1.5f, 1.8f);
        width.setDuration(1700);
        for (ObjectAnimator a : new ObjectAnimator[]{bright, width}) {
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.REVERSE);
        }
        glowAnim = new AnimatorSet();
        glowAnim.playTogether(bright, width);
        glowAnim.start();
    }

    private void stopGlow() {
        if (glowAnim != null) {
            glowAnim.cancel();
            glowAnim = null;
        }
        if (glowView != null) {
            glowView.setAlpha(0f);
            glowView = null;
        }
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
        CharSequence[] items = {"Move earlier", "Move later", "Remove from home"};
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
