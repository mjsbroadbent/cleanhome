package com.cleanhome;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Updates Clean Home from its own GitHub releases, with no computer involved.
 *
 * Every build publishes two files to a release: the app itself and a
 * one-line version.txt holding its build number. "Update" compares that
 * number with the installed one and, if it is higher, downloads the app and
 * hands it to the TV's installer, which asks for one confirmation.
 */
final class Updater {

    private static final String TAG = "CleanHome";
    private static final String BASE =
            "https://github.com/mjsbroadbent/cleanhome/releases/latest/download/";

    /** Marks the message the TV's installer sends back to MainActivity. */
    static final String ACTION = "com.cleanhome.INSTALL_STATUS";

    private Updater() {
    }

    static void run(final Activity a) {
        toast(a, "Checking for updates…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    int latest = Integer.parseInt(
                            new String(fetch(BASE + "version.txt"), "UTF-8").trim());
                    int mine = a.getPackageManager()
                            .getPackageInfo(a.getPackageName(), 0).versionCode;
                    if (latest <= mine) {
                        toast(a, "Clean Home is up to date (build " + mine + ")");
                        return;
                    }
                    toast(a, "Downloading build " + latest + "…");
                    install(a);
                } catch (Exception e) {
                    Log.w(TAG, "update failed", e);
                    toast(a, "Update failed: " + e.getMessage());
                }
            }
        }).start();
    }

    /** Streams the new app straight into the TV's installer. */
    private static void install(Activity a) throws IOException {
        PackageInstaller installer = a.getPackageManager().getPackageInstaller();
        int id = installer.createSession(new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL));
        PackageInstaller.Session session = installer.openSession(id);
        try {
            HttpURLConnection c = open(BASE + "app-debug.apk");
            try {
                InputStream in = c.getInputStream();
                OutputStream out = session.openWrite("cleanhome", 0, -1);
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                session.fsync(out);
                out.close();
            } finally {
                c.disconnect();
            }

            // The installer reports back by re-opening MainActivity, which
            // passes the message to handle() below.
            Intent back = new Intent(a, MainActivity.class).setAction(ACTION);
            int mutable = 1 << 25;      // PendingIntent.FLAG_MUTABLE (Android 12+)
            PendingIntent pending = PendingIntent.getActivity(
                    a, id, back, PendingIntent.FLAG_UPDATE_CURRENT | mutable);
            session.commit(pending.getIntentSender());
        } catch (IOException e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
    }

    /** Called by MainActivity when the installer reports back. */
    static void handle(Activity a, Intent intent) {
        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // The TV wants the person to confirm: show its prompt.
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) a.startActivity(confirm);
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            toast(a, "Update didn't install" + (why == null ? "" : ": " + why));
        }
    }

    // ----------------------------------------------------------------- http

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException(code == 404 ? "no published build found" : "HTTP " + code);
        }
        return c;
    }

    private static byte[] fetch(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static void toast(final Activity a, final String text) {
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(a, text, Toast.LENGTH_LONG).show();
            }
        });
    }
}
