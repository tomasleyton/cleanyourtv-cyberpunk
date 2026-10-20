package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.widget.TextView;

import java.net.Inet4Address;
import java.util.Locale;

/**
 * The SYS // STATUS line of the bottom module: free and total RAM, the TV's IPv4 address (or "no
 * network") and the launcher version. Refreshes once a minute while it is on screen, on the main
 * thread; no service, no receiver, nothing survives the activity.
 */
public class SysStatusView extends TextView {

    private static final long PERIOD_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, PERIOD_MS);
        }
    };

    public SysStatusView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isInEditMode()) return;
        handler.removeCallbacks(tick);
        tick.run();
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private void refresh() {
        Context ctx = getContext();
        String free = "?", total = "?";
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            free = gb(mi.availMem);
            total = gb(mi.totalMem);
        }
        String net = ipAddress(ctx);
        if (net == null) net = ctx.getString(R.string.cyber_status_offline);
        String version;
        try {
            version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            version = "?";
        }
        setText(ctx.getString(R.string.cyber_status_line, free, total, net, version));
    }

    private static String gb(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024 * 1024));
    }

    /** IPv4 of the default network, or null when there is none (ACCESS_NETWORK_STATE is in the manifest). */
    private static String ipAddress(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        Network network = cm.getActiveNetwork();
        if (network == null) return null;
        LinkProperties lp = cm.getLinkProperties(network);
        if (lp == null) return null;
        for (LinkAddress a : lp.getLinkAddresses()) {
            if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
        }
        return null;
    }
}
