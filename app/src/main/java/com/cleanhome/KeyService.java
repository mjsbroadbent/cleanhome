package com.cleanhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Makes the remote's settings button open TV settings from anywhere.
 *
 * On Google TV that button normally opens a panel belonging to the stock home
 * screen, so it goes dead once that home screen is disabled. This service
 * watches remote key presses and opens the settings app itself.
 *
 * Every key press is also written to the log under the tag "CleanHome", so the
 * code a button sends can be read with:  adb logcat -s CleanHome
 */
public class KeyService extends AccessibilityService {

    private static final String TAG = "CleanHome";

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            Log.i(TAG, "key " + event.getKeyCode() + " "
                    + KeyEvent.keyCodeToString(event.getKeyCode())
                    + " scan " + event.getScanCode());
        }

        // This Hisense remote's settings (gear) button sends NOTIFICATION;
        // SETTINGS is the code other Android TV remotes use.
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_NOTIFICATION || code == KeyEvent.KEYCODE_SETTINGS) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                Intent i = new Intent(Settings.ACTION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(i);
                } catch (Exception e) {
                    Log.w(TAG, "could not open settings", e);
                }
            }
            return true;    // swallow both the press and the release
        }
        // Channel up / down brighten and dim the Hue room, once lights are paired.
        if ((code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN)
                && Hue.isReady(this)) {
            // A held button repeats many times a second; act on every sixth.
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() % 6 == 0) {
                Hue.stepAsync(this, code == KeyEvent.KEYCODE_CHANNEL_UP);
            }
            return true;
        }
        return false;       // every other button behaves as normal
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    // ------------------------------------------------------- on / off switch

    /** Whether Clean Home has been given the permission to flip the switch below. */
    static boolean canSwitch(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Turns this service on or off in the TV's accessibility settings.
     *
     * Some apps handle the remote differently whenever any accessibility
     * service is running, so the launcher turns this one off while such an
     * app is open and back on when the home screen returns. Other services
     * in the list are left alone. Returns false if the permission is missing.
     */
    static boolean setEnabled(Context c, boolean on) {
        if (!canSwitch(c)) return false;
        try {
            ContentResolver cr = c.getContentResolver();
            String me = new ComponentName(c, KeyService.class).flattenToString();
            String current = Settings.Secure.getString(cr, "enabled_accessibility_services");

            StringBuilder list = new StringBuilder();
            boolean present = false;
            if (current != null) {
                for (String s : current.split(":")) {
                    if (s.isEmpty()) continue;
                    ComponentName cn = ComponentName.unflattenFromString(s);
                    boolean mine = cn != null && cn.flattenToString().equals(me);
                    if (mine) present = true;
                    if (mine && !on) continue;          // drop it
                    if (list.length() > 0) list.append(':');
                    list.append(s);
                }
            }
            if (on && !present) {
                if (list.length() > 0) list.append(':');
                list.append(me);
            }
            if (on == present) return true;             // already as wanted

            Settings.Secure.putString(cr, "enabled_accessibility_services", list.toString());
            Settings.Secure.putInt(cr, "accessibility_enabled", list.length() > 0 ? 1 : 0);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "could not switch the button service", e);
            return false;
        }
    }
}
