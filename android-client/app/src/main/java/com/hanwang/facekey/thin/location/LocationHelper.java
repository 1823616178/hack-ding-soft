package com.hanwang.facekey.thin.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanwang.facekey.thin.protocol.Protocol;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Android location fix plus Tianditu/Android reverse geocoding, with a manual test fallback. */
public final class LocationHelper {
    public static final class Fix {
        public final String address;
        public final String lat;
        public final String lng;
        public final String source;

        public Fix(String address, String lat, String lng, String source) {
            this.address = address;
            this.lat = lat;
            this.lng = lng;
            this.source = source;
        }

        @Override
        public String toString() {
            return "Fix{lat=" + lat + ", lng=" + lng + ", source=" + source + "}";
        }
    }

    public static final class LocationException extends Exception {
        public final String code;

        public LocationException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private LocationHelper() {
    }

    public static Fix manual(String address, String lat, String lng) throws LocationException {
        try {
            String latText = Protocol.formatCoordinate(lat, "lat");
            String lngText = Protocol.formatCoordinate(lng, "lng");
            String name = address == null || address.trim().isEmpty() ? "测试位置" : address.trim();
            return new Fix(name, latText, lngText, "manual");
        } catch (IllegalArgumentException e) {
            throw new LocationException("INVALID_MANUAL_LOCATION", e.getMessage());
        }
    }

    public static Fix requestFix(Context context, long timeoutMs, String tiandituKey)
            throws LocationException {
        requireLocationPermission(context);
        Location location = requestLocation(context, timeoutMs);
        String address = reverseGeocode(context, location.getLongitude(), location.getLatitude(), tiandituKey);
        return new Fix(
                address,
                Protocol.formatCoordinate(location.getLatitude(), "lat"),
                Protocol.formatCoordinate(location.getLongitude(), "lng"),
                location.getProvider() == null ? "android" : location.getProvider());
    }

    private static void requireLocationPermission(Context context) throws LocationException {
        if (context.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, Process.myPid(), Process.myUid())
                != PackageManager.PERMISSION_GRANTED
                && context.checkPermission(Manifest.permission.ACCESS_COARSE_LOCATION, Process.myPid(), Process.myUid())
                != PackageManager.PERMISSION_GRANTED) {
            throw new LocationException("LOCATION_PERMISSION_DENIED", "未授予定位权限");
        }
    }

    private static Location requestLocation(Context context, long timeoutMs) throws LocationException {
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) throw new LocationException("LOCATION_UNAVAILABLE", "系统定位服务不可用");

        String bestProvider = null;
        if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) bestProvider = LocationManager.GPS_PROVIDER;
        else if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) bestProvider = LocationManager.NETWORK_PROVIDER;

        Location last = null;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            if (!manager.isProviderEnabled(provider)) continue;
            try {
                Location known = manager.getLastKnownLocation(provider);
                if (known != null && (last == null || known.getTime() > last.getTime())) last = known;
            } catch (SecurityException ignored) {
            }
        }
        if (last != null && System.currentTimeMillis() - last.getTime() < 2 * 60 * 1000L) {
            return last;
        }
        if (bestProvider == null) {
            throw new LocationException("LOCATION_DISABLED", "请开启 GPS 或网络定位");
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Location> best = new AtomicReference<>();
        HandlerThread thread = new HandlerThread("efacego-location");
        thread.start();
        Looper looper = thread.getLooper();
        final LocationManager locationManager = manager;

        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location == null) return;
                Location current = best.get();
                if (current == null || location.getAccuracy() < current.getAccuracy()
                        || location.getTime() > current.getTime()) {
                    best.set(location);
                }
                latch.countDown();
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };

        try {
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!locationManager.isProviderEnabled(provider)) continue;
                try {
                    locationManager.requestLocationUpdates(provider, 0L, 0f, listener, looper);
                } catch (SecurityException e) {
                    throw new LocationException("LOCATION_PERMISSION_DENIED", "未授予定位权限");
                } catch (IllegalArgumentException ignored) {
                }
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                locationManager.removeUpdates(listener);
            } catch (Exception ignored) {
            }
            thread.quitSafely();
        }

        Location result = best.get();
        if (result == null) {
            if (last != null) return last;
            throw new LocationException("LOCATION_TIMEOUT", "获取定位超时，请到开阔处重试");
        }
        return result;
    }

    public static String reverseGeocode(Context context, double lng, double lat, String tiandituKey) {
        String tianditu = tiandituReverse(lng, lat, tiandituKey);
        if (tianditu != null && !tianditu.isEmpty()) return tianditu;
        try {
            Geocoder geocoder = new Geocoder(context, Locale.CHINA);
            if (Geocoder.isPresent()) {
                List<android.location.Address> addresses = geocoder.getFromLocation(lat, lng, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    android.location.Address address = addresses.get(0);
                    StringBuilder text = new StringBuilder();
                    for (int i = 0; i <= address.getMaxAddressLineIndex(); i++) {
                        if (i > 0) text.append(' ');
                        text.append(address.getAddressLine(i));
                    }
                    if (text.length() > 0) return text.toString();
                }
            }
        } catch (Exception ignored) {
        }
        return "无法获取精确定位位置";
    }

    private static String tiandituReverse(double lng, double lat, String key) {
        if (key == null || key.trim().isEmpty()) return null;
        HttpUrl base = HttpUrl.parse("http://api.tianditu.gov.cn/geocoder");
        if (base == null) return null;
        String postStr = "{\"lon\":" + lng + ",\"lat\":" + lat + ",\"ver\":1}";
        HttpUrl url = base.newBuilder()
                .addQueryParameter("postStr", postStr)
                .addQueryParameter("type", "geocode")
                .addQueryParameter("tk", key.trim())
                .build();
        Request request = new Request.Builder().url(url).get().build();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            JsonObject root = JsonParser.parseString(response.body().string()).getAsJsonObject();
            if (!root.has("result") || !root.get("result").isJsonObject()) return null;
            JsonObject result = root.getAsJsonObject("result");
            if (result.has("formatted_address") && !result.get("formatted_address").isJsonNull()) {
                return result.get("formatted_address").getAsString();
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return null;
    }
}
