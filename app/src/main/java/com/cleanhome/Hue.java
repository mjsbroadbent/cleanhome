package com.cleanhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Talks to a Philips Hue bridge on the home network.
 *
 * Pairing stores the bridge address, an access key and the chosen room on the
 * TV. After that, {@link #stepAsync} brightens or dims that room one step.
 * Nothing here may be called on the main thread except the "is it set up"
 * getters and stepAsync.
 */
final class Hue {

    private static final String TAG = "CleanHome";
    private static final String PREFS = "hue";

    /** One step is a fifth of the brightness range (1..254). */
    private static final int STEP = 51;

    static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean busy = new AtomicBoolean(false);

    private static volatile String scheme = "http";
    private static volatile long lastRediscover = 0;

    private Hue() {
    }

    // ------------------------------------------------------------- settings

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean hasKey(Context c) {
        return prefs(c).getString("user", null) != null;
    }

    /** True once a bridge is paired and a room has been picked. */
    static boolean isReady(Context c) {
        return hasKey(c) && prefs(c).getString("group", null) != null;
    }

    static String roomName(Context c) {
        return prefs(c).getString("groupName", "none");
    }

    static void setRoom(Context c, String id, String name) {
        prefs(c).edit().putString("group", id).putString("groupName", name).apply();
    }

    static void unpair(Context c) {
        prefs(c).edit().clear().apply();
    }

    // -------------------------------------------------------------- pairing

    /**
     * Finds the bridge and asks it for an access key. The round button on the
     * bridge must have been pressed in the last 30 seconds.
     */
    static void pair(Context c) throws Exception {
        String ip = discover(c);
        if (ip == null) {
            throw new Exception("Couldn't find a Hue bridge on the network");
        }
        String reply = request("POST", ip, "/api", "{\"devicetype\":\"cleanhome#tv\"}");
        JSONObject first = new JSONArray(reply).getJSONObject(0);
        if (!first.has("success")) {
            int type = first.optJSONObject("error") == null
                    ? 0 : first.optJSONObject("error").optInt("type");
            throw new Exception(type == 101
                    ? "Press the button on the Hue bridge first, then choose Pair again"
                    : "The Hue bridge refused to pair");
        }
        String user = first.getJSONObject("success").getString("username");
        prefs(c).edit().putString("ip", ip).putString("user", user).apply();
    }

    /** Rooms and zones on the bridge, each as {id, name}. */
    static List<String[]> rooms(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        String reply = request("GET", p.getString("ip", ""),
                "/api/" + p.getString("user", "") + "/groups", null);
        JSONObject all = new JSONObject(reply);
        List<String[]> out = new ArrayList<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject g = all.getJSONObject(id);
            String type = g.optString("type");
            if (type.equals("Room") || type.equals("Zone")) {
                out.add(new String[]{id, g.optString("name", "Room " + id)});
            }
        }
        return out;
    }

    // -------------------------------------------------------------- control

    /** Brighten (up) or dim (down) the chosen room by one step, in the background. */
    static void stepAsync(final Context c, final boolean up) {
        if (!busy.compareAndSet(false, true)) return;   // still sending the last one
        EXEC.execute(new Runnable() {
            @Override public void run() {
                try {
                    step(c, up);
                } catch (Exception e) {
                    Log.w(TAG, "lights: " + e);
                } finally {
                    busy.set(false);
                }
            }
        });
    }

    private static void step(Context c, boolean up) throws Exception {
        try {
            stepOnce(c, up);
        } catch (IOException e) {
            // The bridge may have a new address. Look for it again, at most once a minute.
            long now = System.currentTimeMillis();
            if (now - lastRediscover < 60000) throw e;
            lastRediscover = now;
            String ip = discover(c);
            if (ip == null) throw e;
            prefs(c).edit().putString("ip", ip).apply();
            stepOnce(c, up);
        }
    }

    private static void stepOnce(Context c, boolean up) throws Exception {
        SharedPreferences p = prefs(c);
        String ip = p.getString("ip", "");
        String base = "/api/" + p.getString("user", "") + "/groups/" + p.getString("group", "");

        JSONObject g = new JSONObject(request("GET", ip, base, null));
        boolean anyOn = g.getJSONObject("state").optBoolean("any_on");
        int bri = g.getJSONObject("action").optInt("bri", 254);

        if (!up && !anyOn) return;                      // already off
        if (!up && bri <= STEP + 9) {                   // dimming past the lowest step
            request("PUT", ip, base + "/action", "{\"on\":false}");
            return;
        }
        if (anyOn) {
            bri += up ? STEP : -STEP;                   // from off, come back at the old level
        }
        bri = Math.max(STEP, Math.min(254, bri));
        if (bri > 254 - STEP / 2) bri = 254;            // land exactly on full

        // Colour bulbs follow "xy"; bulbs that only do shades of white ignore
        // it and follow "ct" instead, so both are sent.
        request("PUT", ip, base + "/action", "{\"on\":true,\"bri\":" + bri
                + ",\"ct\":" + warmth(bri) + ",\"xy\":" + color(bri)
                + ",\"transitiontime\":2}");
    }

    /**
     * The colour for a brightness, as a point on the colour chart Hue uses:
     * deep orange at the lowest step, through warm whites, to neutral white
     * at full. Brightness levels between the listed stops are blended.
     */
    private static String color(int bri) {
        final float[][] stops = {
                {0.590f, 0.390f},   // lowest step: deep orange
                {0.510f, 0.415f},   // amber-white, about 2200K
                {0.460f, 0.411f},   // warm white, about 2700K
                {0.418f, 0.397f},   // soft white, about 3300K
                {0.380f, 0.377f},   // full: neutral white, about 4000K
        };
        float t = (bri - STEP) / (float) (254 - STEP);
        t = Math.max(0f, Math.min(1f, t)) * (stops.length - 1);
        int i = Math.min((int) t, stops.length - 2);
        float f = t - i;
        float x = stops[i][0] + f * (stops[i + 1][0] - stops[i][0]);
        float y = stops[i][1] + f * (stops[i + 1][1] - stops[i][1]);
        return String.format(java.util.Locale.US, "[%.4f,%.4f]", x, y);
    }

    /**
     * The colour of white for a brightness: warmest at the lowest step,
     * neutral at full, evenly graded between. Hue measures this in "mireds",
     * where a bigger number is warmer: 500 is candle-like (2000K), 250 is
     * neutral white (4000K).
     */
    private static int warmth(int bri) {
        final int warmest = 500, neutral = 250;
        float t = (bri - STEP) / (float) (254 - STEP);  // 0 at the lowest step, 1 at full
        t = Math.max(0f, Math.min(1f, t));
        return Math.round(warmest + t * (neutral - warmest));
    }

    // ------------------------------------------------------------ discovery

    /** The bridge's address, or null. Looks on the local network first, then asks Hue's lookup service. */
    private static String discover(Context c) {
        String ip = discoverLocal(c);
        if (ip == null) ip = discoverCloud();
        Log.i(TAG, "hue bridge: " + ip);
        return ip;
    }

    private static String discoverLocal(Context c) {
        final NsdManager nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return null;
        final String[] found = {null};
        final CountDownLatch done = new CountDownLatch(1);

        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onServiceFound(NsdServiceInfo info) {
                try {
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override public void onServiceResolved(NsdServiceInfo r) {
                            InetAddress host = r.getHost();
                            if (host != null) {
                                found[0] = host.getHostAddress();
                                done.countDown();
                            }
                        }
                        @Override public void onResolveFailed(NsdServiceInfo r, int code) {
                        }
                    });
                } catch (Exception ignored) {
                }
            }
            @Override public void onServiceLost(NsdServiceInfo info) { }
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onStartDiscoveryFailed(String type, int code) { done.countDown(); }
            @Override public void onStopDiscoveryFailed(String type, int code) { }
        };

        try {
            nsd.discoverServices("_hue._tcp", NsdManager.PROTOCOL_DNS_SD, listener);
            done.await(4, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (Exception ignored) {
            }
        }
        return found[0];
    }

    private static String discoverCloud() {
        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL("https://discovery.meethue.com/").openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            JSONArray list = new JSONArray(read(c));
            return list.length() == 0 ? null
                    : list.getJSONObject(0).optString("internalipaddress", null);
        } catch (Exception e) {
            return null;
        }
    }

    // ----------------------------------------------------------------- http

    /** Sends one request to the bridge, trying plain http and then https. */
    private static String request(String method, String ip, String path, String body)
            throws IOException {
        String first = scheme;
        String second = first.equals("http") ? "https" : "http";
        try {
            return send(first, method, ip, path, body);
        } catch (IOException e) {
            String reply = send(second, method, ip, path, body);
            scheme = second;
            return reply;
        }
    }

    private static String send(String scheme, String method, String ip, String path, String body)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection)
                new URL(scheme + "://" + ip + path).openConnection();
        if (c instanceof HttpsURLConnection) {
            // The bridge's certificate is issued for its serial number, not its
            // address, so the normal check can't pass. Skip it, but only for an
            // address on the home network.
            if (!InetAddress.getByName(ip).isSiteLocalAddress()) {
                throw new IOException("not a home-network address");
            }
            try {
                ((HttpsURLConnection) c).setSSLSocketFactory(relaxed());
            } catch (Exception e) {
                throw new IOException(e);
            }
            ((HttpsURLConnection) c).setHostnameVerifier(
                    new javax.net.ssl.HostnameVerifier() {
                        @Override public boolean verify(String h, javax.net.ssl.SSLSession s) {
                            return true;
                        }
                    });
        }
        c.setConnectTimeout(1500);
        c.setReadTimeout(2500);
        c.setRequestMethod(method);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.getOutputStream().write(body.getBytes("UTF-8"));
        }
        return read(c);
    }

    private static String read(HttpURLConnection c) throws IOException {
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static SSLSocketFactory relaxed() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String type) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String type) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        return ctx.getSocketFactory();
    }
}
