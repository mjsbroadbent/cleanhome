package com.cleanhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
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
}
