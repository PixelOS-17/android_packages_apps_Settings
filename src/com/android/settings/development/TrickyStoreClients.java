package com.android.settings.development;

import android.app.ActivityManager;
import android.content.Context;
import android.provider.Settings;
import android.security.trickystore.TrickyStoreService;
import android.text.TextUtils;
import android.util.Log;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Restarts every package whose key attestation is served by TrickyStore.
 * Leaf certificates and generated keys are cached in the app process, so a
 * new keybox, profile, or target list does nothing for a bank until that
 * process is gone.
 */
final class TrickyStoreClients {
    private static final String TAG = "TrickyStoreClients";

    private TrickyStoreClients() {}

    static void forceStop(Context context) {
        if (context == null) {
            return;
        }
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null) {
            return;
        }
        for (String pkg : targetPackages(context)) {
            try {
                am.forceStopPackage(pkg);
                Log.i(TAG, pkg + " process killed");
            } catch (SecurityException e) {
                Log.w(TAG, "Failed to stop " + pkg, e);
            }
        }
    }

    private static Set<String> targetPackages(Context context) {
        Set<String> packages = new LinkedHashSet<>();
        packages.add("com.google.android.gms");
        packages.add("com.android.vending");

        String stored = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.SPOOF_TRICKYSTORE_TARGET);
        String content = TextUtils.isEmpty(stored)
                ? TrickyStoreService.DEFAULT_TARGET_LIST : stored;
        if ("#".equals(content.trim())) {
            return packages;
        }
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            char last = line.charAt(line.length() - 1);
            if (last == '!' || last == '?' || last == '-') {
                line = line.substring(0, line.length() - 1).trim();
            }
            if (line.isEmpty() || "android".equals(line)) {
                continue;
            }
            packages.add(line);
        }
        return packages;
    }
}
