package dev.akash.rusty;

import android.animation.LayoutTransition;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class HomeActivity extends Activity {

    private static final int COLS = 6;
    private static final int TOP = 0, DOCK = 1, GRID = 2;
    private static final int ROW_H_DP = 106, TITLE_H_DP = 44, TOP_INSET_DP = 56;
    private static final int RESIZE_MS = 220;
    private static final float TILE_SCALE = 1.08f, PILL_SCALE = 1.03f;
    /** Wallpaper drift: how far the scenery moves against the focus (it's scaled 1.06 in the layout, so edges never show). */
    private static final int PARALLAX_X_DP = 22, PARALLAX_Y_DP = 10;
    /** Ambient mode starts at 3 idle minutes, before Fire TV's 5-min screensaver. */
    private static final long AMBIENT_MS = 180_000;
    /** Time-of-day grading of wallpaper.jpg: morning, day, evening, night (null = untouched). */
    private static final String[] MOODS = {"morning", "day", "evening", "night"};
    private static final float[][] MOOD_MATRIX = {
            {1.08f, 0, 0, 0, 4, 0, 1.08f, 0, 0, 4, 0, 0, 1.14f, 0, 8, 0, 0, 0, 1, 0}, // morning: lighter, a touch cool
            null,                                                                    // day: as drawn
            {1.10f, 0, 0, 0, 6, 0, 1.0f, 0, 0, 0, 0, 0, 0.88f, 0, 0, 0, 0, 0, 1, 0},  // evening: warmer
            {0.50f, 0, 0, 0, 0, 0, 0.55f, 0, 0, 0, 0, 0, 0.75f, 0, 4, 0, 0, 0, 1, 0}, // night: darker, moonlit blue
    };

    /** Adapter item types; position 0 is always the header. */
    private static final int T_HEADER = 0, T_TITLE = 1, T_APP = 2, T_MORE = 3, T_CHANNEL = 4;

    /** Default dock, left to right. Override without rebuilding: one package per line in files/dock.txt. */
    private static final List<String> DEFAULT_DOCK = Arrays.asList(
            "in.startv.hotstar", "com.amazon.firebat", "com.netflix.ninja", "com.amazon.firetv.youtube",
            "com.onemainstream.sonyliv.android", "com.zee5.amazon");

    /** The Live TV button opens this app (a full-screen, channel-flipping TV experience); it's left out of the grid. */
    private static final String LIVE_TV_APP = "com.mitv.tvhome.mitvplus"; // Xiaomi TV+

    /** Launchable system stubs that don't belong in an app grid, plus the app behind the Live TV button. */
    private static final List<String> HIDDEN = Arrays.asList(LIVE_TV_APP,
            "com.amazon.tv.launcher", "com.amazon.ftv.profilepicker", "com.amazon.hedwig", "com.amazon.ssm",
            "com.amazon.vizzini.ftvcds", "com.android.providers.downloads.ui", "com.amazon.ftv.screensaver",
            "com.amazon.firetv.troubleshooting");

    static final class App {
        final String pkg, label;
        final Intent intent;
        final Drawable banner, icon;

        App(ResolveInfo ri, String category, PackageManager pm) {
            pkg = ri.activityInfo.packageName;
            label = ri.loadLabel(pm).toString();
            intent = new Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            banner = flatten(ri.activityInfo.loadBanner(pm)); // activity banner, else application banner
            icon = banner == null ? ri.loadIcon(pm) : null;
        }
    }

    /** A live channel from files/channels.json: opens straight into the channel in its app. */
    static final class Channel {
        final String name;
        final Intent intent;
        final Drawable logo; // optional
        final int bg;        // optional tile colour (behind the logo or the name), 0 = the normal tile colour

        Channel(String name, Intent intent, Drawable logo, int bg) { this.name = name; this.intent = intent; this.logo = logo; this.bg = bg; }
    }

    /** One adapter entry below the header: a section title, an app, the More/Less tile or a channel. */
    static final class Item {
        final int type;
        final App app;
        final Channel channel;
        final String text;

        Item(int type, App app, Channel channel, String text) { this.type = type; this.app = app; this.channel = channel; this.text = text; }

        /** Stable identity for remembering focus across reloads and restarts. */
        String key() {
            return type == T_APP ? "app:" + app.pkg : type == T_CHANNEL ? "ch:" + channel.name : type == T_MORE ? "more" : null;
        }
    }

    private float dp;
    private int headerH, scrollY;
    private RecyclerView list;
    private GlassView glass;
    private View header;
    private TextView hero, greeting, wifiName;
    private View[] pills;
    private LinearLayout dockRow;
    private final List<View> dockBanners = new ArrayList<>();
    private final Rect tmp = new Rect();
    private View scenery, ambientView;
    private ImageView wallpaper;
    private final Handler idle = new Handler(Looper.getMainLooper());
    private boolean ambient;
    private long driftStart;
    private float driftFrom;
    /**
     * Ambient drift: the scenery sways side to side over ~90 s. Stepped at 10 fps instead of an animator's 60:
     * it moves under a pixel per step, and a 60 fps animator redrew the whole screen for nothing.
     */
    private final Runnable driftTick = new Runnable() {
        @Override public void run() {
            if (!ambient) return;
            double t = (android.os.SystemClock.uptimeMillis() - driftStart) / 90_000.0 * 2 * Math.PI;
            float reach = px(PARALLAX_X_DP);
            // start where parallax left it, then settle into a slow sine
            scenery.setTranslationX((float) (Math.sin(t) * reach + driftFrom * Math.cos(t / 4) * Math.max(0, 1 - t / Math.PI)));
            idle.postDelayed(this, 100);
        }
    };
    private String wallpaperPath;
    private final Runnable startAmbient = this::enterAmbient;

    private List<App> dock = new ArrayList<>(), apps = new ArrayList<>();
    private List<Channel> channels = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();       // adapter position p is items.get(p - 1)
    private final List<int[]> rows = new ArrayList<>();       // adapter positions of each focusable grid row
    private final List<Integer> rowTops = new ArrayList<>();  // y of each grid row's top, in page pixels
    private boolean appsExpanded;

    private int zone = DOCK, topIdx, dockIdx, gridRow, gridCol;
    private boolean loaded, resumed;
    private long customStamp = -1; // last-modified of the files in custom() we loaded
    private Typeface thin, light, regular;
    private SharedPreferences prefs;

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };
    private final BroadcastReceiver tickReceiver = new BroadcastReceiver() { // time tick + network changes, no polling
        @Override public void onReceive(Context c, Intent i) { updateGreeting(); updateWifi(); updateMood(false); }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_home);
        dp = getResources().getDisplayMetrics().density;
        headerH = getResources().getDisplayMetrics().heightPixels;
        prefs = getPreferences(MODE_PRIVATE);
        appsExpanded = prefs.getBoolean("appsExpanded", false);
        thin = font(200);
        light = font(300);
        regular = font(400);

        list = findViewById(R.id.list);
        glass = findViewById(R.id.glass);
        scenery = findViewById(R.id.scenery);
        wallpaper = findViewById(R.id.wallpaper);
        ambientView = findViewById(R.id.ambient);
        ((TextView) findViewById(R.id.ambient_clock)).setTypeface(thin);
        ((TextView) findViewById(R.id.ambient_date)).setTypeface(light);
        header = LayoutInflater.from(this).inflate(R.layout.header, null);
        header.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headerH));
        hero = header.findViewById(R.id.hero);
        greeting = header.findViewById(R.id.greeting);
        wifiName = header.findViewById(R.id.wifi_name);
        dockRow = header.findViewById(R.id.dock);
        pills = new View[]{header.findViewById(R.id.pill_live), header.findViewById(R.id.pill_wifi), header.findViewById(R.id.pill_settings)};
        // icon-only pills that widen to show their label when focused; the row's width is fixed so only the pills move
        smoothResize(header.findViewById(R.id.pills));
        for (View p : pills) revealLabelAfterResize((ViewGroup) p);
        ((TextView) header.findViewById(R.id.clock)).setTypeface(thin);
        ((TextView) header.findViewById(R.id.date)).setTypeface(light);
        greeting.setTypeface(light);
        hero.setTypeface(light);
        wifiName.setTypeface(regular);
        ((TextView) header.findViewById(R.id.settings_label)).setTypeface(regular);
        ((TextView) header.findViewById(R.id.live_label)).setTypeface(regular);

        GridLayoutManager lm = new GridLayoutManager(this, COLS) {
            @Override // lay out one extra screen so the next row always exists before we scroll to it
            protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State s, @NonNull int[] extra) {
                extra[0] = extra[1] = headerH;
            }
        };
        lm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int p) { return p == 0 || items.get(p - 1).type == T_TITLE ? COLS : 1; }
        });
        list.setLayoutManager(lm);
        list.setItemAnimator(null);
        list.setAdapter(new Adapter());
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                scrollY += dy;
                glass.invalidate();
            }
        });

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packagesChanged, pf);
    }


    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(tickReceiver, f);
        updateGreeting();
        updateWifi();
        // dock.txt / wallpapers / channels.json pushed over ADB take effect the next time Home comes to the front
        long stamp = custom("dock.txt").lastModified() + custom("channels.json").lastModified();
        for (String m : MOODS) stamp += custom("wallpaper_" + m + ".jpg").lastModified();
        stamp += custom("wallpaper.jpg").lastModified();
        if (stamp != customStamp) {
            customStamp = stamp;
            updateMood(true);
            loadApps();
        } else {
            updateMood(false);
        }
        glass.invalidate();
        wake();
        entrance();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        unregisterReceiver(tickReceiver);
        idle.removeCallbacksAndMessages(null);
        if (ambient) exitAmbient(false);
        prefs.edit().putInt("zone", zone).putInt("top", topIdx).putInt("dock", dockIdx)
                .putString("key", focusedKey()).apply();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Opened again while already in front (Menu button): back to the dock. Coming back from an app keeps the last tile.
        if (resumed) wake();
        if (resumed && loaded && !dock.isEmpty()) focus(DOCK, dockIdx, true);
    }

    @Override
    public void onBackPressed() {
        if (zone == GRID && !dock.isEmpty()) focus(DOCK, Math.min(gridCol, dock.size() - 1), true);
        // never finish the home screen
    }

    // ---------------------------------------------------------------- D-pad

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            boolean wasAmbient = ambient;
            wake();
            if (wasAmbient) return true; // the key that ends ambient mode does nothing else
        }
        int dx = 0, dy = 0;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dx = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dx = 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: dy = -1; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dy = 1; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) activate();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && loaded) move(dx, dy);
        return true;
    }

    /**
     * Deterministic focus: rows are [top bar] [dock] [grid rows: apps, then channels]. Up/Down move exactly
     * one row and keep the column where the row is long enough; edges never fall through.
     */
    private void move(int dx, int dy) {
        switch (zone) {
            case TOP:
                if (dy > 0) {
                    if (!dock.isEmpty()) focus(DOCK, dockIdx, true);
                    else if (!rows.isEmpty()) focusGrid(0, 0);
                } else if (dx != 0) {
                    focus(TOP, clamp(topIdx + dx, pills.length - 1), true);
                }
                break;
            case DOCK:
                if (dy < 0) focus(TOP, topIdx, true);
                else if (dy > 0) { if (!rows.isEmpty()) focusGrid(0, dockIdx); }
                else focus(DOCK, clamp(dockIdx + dx, dock.size() - 1), true);
                break;
            case GRID:
                if (dy < 0) {
                    if (gridRow > 0) focusGrid(gridRow - 1, gridCol);
                    else if (!dock.isEmpty()) focus(DOCK, Math.min(gridCol, dock.size() - 1), true);
                    else focus(TOP, topIdx, true);
                } else if (dy > 0) {
                    if (gridRow < rows.size() - 1) focusGrid(gridRow + 1, gridCol);
                } else {
                    int c = gridCol + dx;
                    if (c >= 0 && c < rows.get(gridRow).length) focusGrid(gridRow, c);
                }
                break;
        }
    }

    private void focusGrid(int row, int col) {
        focus(GRID, row * COLS + Math.min(col, rows.get(row).length - 1), true);
    }

    /** {@code idx} is the pill / dock index, or {@code row * COLS + col} for the grid. */
    private void focus(int newZone, int idx, boolean animate) {
        View old = focusedView();
        if (old != null) setFocused(old, false, animate);
        zone = newZone;
        if (zone == TOP) topIdx = idx;
        else if (zone == DOCK) dockIdx = idx;
        else { gridRow = idx / COLS; gridCol = idx % COLS; }

        View now = focusedView();
        if (now != null) setFocused(now, true, animate);
        if (zone == DOCK) showHero(dock.get(dockIdx), animate);
        // the big name belongs to the dock; grid tiles show their own name
        float heroAlpha = zone == GRID ? 0f : 1f;
        hero.animate().cancel();
        if (animate) hero.animate().alpha(heroAlpha).setDuration(180); else hero.setAlpha(heroAlpha);
        if (zone == GRID) { int pos = rows.get(gridRow)[gridCol]; glass.moveTo(out -> bounds(gridBanner(pos), out, 8 * dp), animate); }
        else { View v = now; glass.moveTo(out -> bounds(v, out, zone == TOP ? 22 * dp : 8 * dp), animate); }

        int target = zone == GRID ? rowTops.get(gridRow) - px(TOP_INSET_DP) : 0;
        if (target != scrollY) {
            if (animate) list.smoothScrollBy(0, target - scrollY, new DecelerateInterpolator(2f), 380);
            else list.scrollBy(0, target - scrollY);
        }
        parallax(animate);
    }

    /** The scenery slides a little against the focus: left/right with the column, up/down with the zone. */
    private void parallax(boolean animate) {
        if (ambient) return;
        float nx = zone == TOP ? 0.7f
                : zone == DOCK ? (dockIdx + 0.5f) / Math.max(1, dock.size()) * 2 - 1
                : (gridCol + 0.5f) / COLS * 2 - 1;
        float tx = -nx * px(PARALLAX_X_DP), ty = (zone == TOP ? 1 : zone == DOCK ? 0 : -1) * px(PARALLAX_Y_DP);
        scenery.animate().cancel();
        if (animate) scenery.animate().translationX(tx).translationY(ty).setDuration(600).setInterpolator(new DecelerateInterpolator(1.5f));
        else { scenery.setTranslationX(tx); scenery.setTranslationY(ty); }
    }

    // ---------------------------------------------------------------- life: entrance, idle, ambient

    /** Coming to the front: the header's parts (or the grid) rise and fade in, staggered. */
    private void entrance() {
        DecelerateInterpolator ease = new DecelerateInterpolator(2f);
        if (zone == GRID) {
            list.setAlpha(0f);
            list.setTranslationY(px(24));
            list.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(ease)
                    .setUpdateListener(a -> glass.invalidate());
        } else {
            ViewGroup h = (ViewGroup) header; // clock block, pills, hero name, dock
            for (int i = 0; i < h.getChildCount(); i++) {
                View v = h.getChildAt(i);
                if (v == hero && zone == GRID) continue;
                v.animate().cancel();
                v.setAlpha(0f);
                v.setTranslationY(px(16));
                v.animate().alpha(1f).translationY(0).setStartDelay(i * 60L).setDuration(320).setInterpolator(ease);
            }
        }
        glass.setAlpha(0f);
        glass.animate().alpha(1f).setStartDelay(260).setDuration(200);
    }

    /** Any key, or coming to the front: ambient mode ends and the idle timer restarts. */
    private void wake() {
        idle.removeCallbacksAndMessages(null);
        if (ambient) exitAmbient(true);
        if (resumed) idle.postDelayed(startAmbient, AMBIENT_MS);
    }

    /** Idle for a few minutes: the UI fades away, a big clock stays and the scenery drifts slowly. */
    private void enterAmbient() {
        if (!resumed || ambient) return;
        ambient = true;
        list.animate().alpha(0f).setStartDelay(0).setDuration(900);
        glass.animate().alpha(0f).setStartDelay(0).setDuration(600);
        ambientView.setVisibility(View.VISIBLE);
        ambientView.animate().alpha(1f).setStartDelay(500).setDuration(1200);
        scenery.animate().cancel();
        scenery.animate().translationY(0).setDuration(2000);
        driftStart = android.os.SystemClock.uptimeMillis();
        driftFrom = scenery.getTranslationX();
        idle.postDelayed(driftTick, 2000);
    }

    private void exitAmbient(boolean animate) {
        ambient = false;
        idle.removeCallbacks(driftTick);
        ambientView.animate().cancel();
        list.animate().cancel();
        if (animate) {
            ambientView.animate().alpha(0f).setStartDelay(0).setDuration(250).withEndAction(() -> ambientView.setVisibility(View.GONE));
            list.setAlpha(1f);
            entrance();
            parallax(true);
        } else {
            ambientView.setAlpha(0f);
            ambientView.setVisibility(View.GONE);
            list.setAlpha(1f);
            glass.setAlpha(1f);
        }
    }

    private View focusedView() {
        if (zone == TOP) return pills[topIdx];
        if (zone == DOCK) return dockIdx < dockBanners.size() ? dockBanners.get(dockIdx) : null;
        return gridRow < rows.size() && gridCol < rows.get(gridRow).length ? gridBanner(rows.get(gridRow)[gridCol]) : null;
    }

    private View gridBanner(int pos) {
        RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(pos);
        return h == null ? null : h.itemView.findViewById(R.id.banner);
    }

    private boolean isGridFocus(int pos) {
        return zone == GRID && gridRow < rows.size() && gridCol < rows.get(gridRow).length && rows.get(gridRow)[gridCol] == pos;
    }

    private String focusedKey() {
        return zone == GRID && gridRow < rows.size() && gridCol < rows.get(gridRow).length
                ? items.get(rows.get(gridRow)[gridCol] - 1).key() : null;
    }

    private void setFocused(View v, boolean f, boolean animate) {
        // Wi-Fi and Settings show their label only when focused; Live TV (pills[0]) always shows it
        if (v == pills[1] || v == pills[2]) ((ViewGroup) v).getChildAt(1).setVisibility(f ? View.VISIBLE : View.GONE);
        float s = f ? (zone == TOP ? PILL_SCALE : TILE_SCALE) : 1f;
        View label = v.getId() == R.id.banner ? ((View) v.getParent()).findViewById(R.id.label) : null;
        if (animate) {
            v.animate().scaleX(s).scaleY(s).setDuration(200).setUpdateListener(a -> glass.invalidate());
            if (label != null) label.animate().alpha(f ? 1f : 0f).setDuration(160);
        } else {
            v.animate().cancel();
            v.setScaleX(s);
            v.setScaleY(s);
            if (label != null) label.setAlpha(f ? 1f : 0f);
        }
    }

    /** Glass target: the view's scaled bounds in root coordinates, padded a little. */
    private float bounds(View v, RectF out, float radius) {
        if (v == null || !v.isAttachedToWindow()) return -1;
        tmp.set(0, 0, v.getWidth(), v.getHeight());
        try {
            ((ViewGroup) findViewById(R.id.root)).offsetDescendantRectToMyCoords(v, tmp);
        } catch (IllegalArgumentException recycled) {
            return -1;
        }
        float s = v.getScaleX(), pad = 4 * dp;
        float hw = tmp.width() * s / 2 + pad, hh = tmp.height() * s / 2 + pad;
        out.set(tmp.exactCenterX() - hw, tmp.exactCenterY() - hh, tmp.exactCenterX() + hw, tmp.exactCenterY() + hh);
        return radius * s + pad;
    }

    private void activate() {
        if (!loaded) return;
        if (zone == TOP) {
            Intent i;
            if (topIdx == 0) {
                PackageManager pm = getPackageManager();
                i = pm.getLeanbackLaunchIntentForPackage(LIVE_TV_APP);
                if (i == null) i = pm.getLaunchIntentForPackage(LIVE_TV_APP);
                // not installed: fall back to Fire TV's own Live TV channel guide
                if (i == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse("livetv://showEpg"));
            } else if (topIdx == 1) {
                i = new Intent(Settings.ACTION_WIFI_SETTINGS);
            } else {
                // Fire OS answers ACTION_SETTINGS with a dummy that bounces to Amazon home; its real root lives in the stock launcher
                i = new Intent().setComponent(new ComponentName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.SettingsActivity"));
            }
            launch(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (zone == DOCK) {
            launch(dock.get(dockIdx).intent);
        } else if (gridRow < rows.size()) {
            Item it = items.get(rows.get(gridRow)[gridCol] - 1);
            if (it.type == T_APP) launch(it.app.intent);
            else if (it.type == T_CHANNEL) launch(it.channel.intent);
            else if (it.type == T_MORE) toggleApps();
        }
    }

    /** More shows every app (the tile becomes Less); Less folds the apps back into one row. Focus stays on the tile. */
    private void toggleApps() {
        appsExpanded = !appsExpanded;
        prefs.edit().putBoolean("appsExpanded", appsExpanded).apply();
        View old = focusedView();
        if (old != null) setFocused(old, false, false);
        buildItems();
        int[] at = find("more");
        gridRow = at[0];
        gridCol = at[1];
        list.getAdapter().notifyDataSetChanged();
        list.post(() -> focus(GRID, gridRow * COLS + gridCol, true));
    }

    private void launch(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Can't open this", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- data

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Map<String, App> all = new LinkedHashMap<>();
            for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                    String p = ri.activityInfo.packageName;
                    if (!all.containsKey(p) && !HIDDEN.contains(p) && !p.equals(getPackageName())) all.put(p, new App(ri, cat, pm));
                }
            }
            List<App> d = new ArrayList<>();
            for (String p : dockPackages()) { App a = all.remove(p); if (a != null) d.add(a); }
            List<App> g = new ArrayList<>(all.values());
            g.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
            List<Channel> ch = loadChannels();
            runOnUiThread(() -> setData(d, g, ch));
        }).start();
    }

    private List<String> dockPackages() {
        File f = custom("dock.txt");
        if (!f.exists()) return DEFAULT_DOCK;
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) if (!line.trim().isEmpty()) out.add(line.trim());
        } catch (Exception e) {
            return DEFAULT_DOCK;
        }
        return out;
    }

    /**
     * files/channels.json: [{"name": ..., "link": "intent:#Intent;...;end",
     * "logo": "optional file in files/channels/", "bg": "optional #RRGGBB tile colour"}]
     */
    private List<Channel> loadChannels() {
        List<Channel> out = new ArrayList<>();
        File f = custom("channels.json");
        if (!f.exists()) return out;
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Intent in = Intent.parseUri(o.getString("link"), Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                String logoName = o.optString("logo", "");
                File logoFile = new File(custom("channels"), logoName);
                Drawable logo = !logoName.isEmpty() && logoFile.exists() ? Drawable.createFromPath(logoFile.getPath()) : null;
                String bg = o.optString("bg", "");
                out.add(new Channel(o.getString("name"), in, logo, bg.isEmpty() ? 0 : android.graphics.Color.parseColor(bg)));
            }
        } catch (Exception e) {
            return new ArrayList<>(); // a broken file just means no channel row; Home itself keeps working
        }
        return out;
    }

    private void setData(List<App> d, List<App> g, List<Channel> ch) {
        String key = loaded ? focusedKey() : prefs.getString("key", null);
        if (!loaded) {
            zone = prefs.getInt("zone", DOCK);
            topIdx = prefs.getInt("top", 0);
            dockIdx = prefs.getInt("dock", 0);
        }
        dock = d;
        apps = g;
        channels = ch;
        dockRow.removeAllViews();
        dockBanners.clear();
        for (App a : dock) {
            View tile = LayoutInflater.from(this).inflate(R.layout.tile, dockRow, false);
            tile.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            tile.setPadding(0, 0, 0, 0);
            ((LinearLayout) tile).setGravity(android.view.Gravity.CENTER);
            tile.findViewById(R.id.label).setVisibility(View.GONE);
            bindApp(tile, a);
            dockRow.addView(tile);
            dockBanners.add(tile.findViewById(R.id.banner));
        }
        buildItems();
        list.getAdapter().notifyDataSetChanged();
        loaded = true;

        int[] at = find(key);
        topIdx = clamp(topIdx, pills.length - 1);
        dockIdx = clamp(dockIdx, dock.size() - 1);
        if (zone == GRID && at == null) zone = DOCK;
        if (zone == DOCK && dock.isEmpty()) zone = TOP;
        if (zone == GRID) { gridRow = at[0]; gridCol = at[1]; }
        if (!dock.isEmpty()) showHero(dock.get(dockIdx), false);
        // after the new items are laid out; read focus then, not now, so a key pressed in between isn't undone
        list.post(() -> focus(zone, zone == TOP ? topIdx : zone == DOCK ? dockIdx : gridRow * COLS + gridCol, false));
    }

    /** Lays the grid out as sections: "Apps" (one row + More, or all + Less) then "Live channels". */
    private void buildItems() {
        items.clear();
        rows.clear();
        rowTops.clear();
        int y = headerH;
        if (!apps.isEmpty()) {
            boolean folds = apps.size() > COLS;
            int shown = folds && !appsExpanded ? COLS - 1 : apps.size();
            List<Item> tiles = new ArrayList<>();
            for (int i = 0; i < shown; i++) tiles.add(new Item(T_APP, apps.get(i), null, null));
            if (folds) tiles.add(new Item(T_MORE, null, null, appsExpanded ? "Show less" : "+" + (apps.size() - shown) + " more"));
            y = addSection("Apps", tiles, y);
        }
        if (!channels.isEmpty()) {
            List<Item> tiles = new ArrayList<>();
            for (Channel c : channels) tiles.add(new Item(T_CHANNEL, null, c, null));
            addSection("Live channels", tiles, y);
        }
    }

    private int addSection(String title, List<Item> tiles, int y) {
        items.add(new Item(T_TITLE, null, null, title));
        y += px(TITLE_H_DP);
        for (int i = 0; i < tiles.size(); i += COLS) {
            int[] row = new int[Math.min(COLS, tiles.size() - i)];
            for (int k = 0; k < row.length; k++) {
                items.add(tiles.get(i + k));
                row[k] = items.size(); // adapter position = index + 1 (header is 0)
            }
            rows.add(row);
            rowTops.add(y);
            y += px(ROW_H_DP);
        }
        return y;
    }

    /** {row, col} of the grid item with this key, or null. */
    private int[] find(String key) {
        if (key == null) return null;
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < rows.get(r).length; c++) {
                if (key.equals(items.get(rows.get(r)[c] - 1).key())) return new int[]{r, c};
            }
        }
        return null;
    }

    private void bindApp(View tile, App a) {
        ImageView b = tile.findViewById(R.id.banner);
        b.setClipToOutline(true);
        if (a.banner != null) {
            b.setScaleType(ImageView.ScaleType.FIT_XY);
            b.setPadding(0, 0, 0, 0);
            b.setImageDrawable(a.banner);
        } else {
            int p = px(24);
            b.setScaleType(ImageView.ScaleType.FIT_CENTER);
            b.setPadding(p, p / 2, p, p / 2);
            b.setImageDrawable(a.icon);
        }
        ((TextView) tile.findViewById(R.id.label)).setText(a.label);
    }

    /** Channel tiles show the name on the tile (or the logo, if one was supplied); More/Less shows its text. */
    private void bindText(View tile, Item it) {
        View face = tile.findViewById(R.id.banner);
        face.setClipToOutline(true);
        ImageView logo = tile.findViewById(R.id.logo);
        TextView text = tile.findViewById(R.id.text);
        boolean hasLogo = it.type == T_CHANNEL && it.channel.logo != null;
        logo.setVisibility(hasLogo ? View.VISIBLE : View.GONE);
        logo.setImageDrawable(hasLogo ? it.channel.logo : null);
        // tile_bg is a shared shape: mutate() so tinting one channel's tile doesn't tint them all
        android.graphics.drawable.GradientDrawable bgShape = (android.graphics.drawable.GradientDrawable) getDrawable(R.drawable.tile_bg).mutate();
        int bg = it.type == T_CHANNEL ? it.channel.bg : 0;
        if (bg != 0) bgShape.setColor(bg);
        face.setBackground(bgShape);
        // on a light tile colour, a name tile needs dark text
        boolean lightTile = bg != 0 && android.graphics.Color.luminance(bg) > 0.5f;
        text.setTextColor(lightTile ? 0xFF1C1D24 : 0xF2FFFFFF);
        text.setVisibility(hasLogo ? View.GONE : View.VISIBLE);
        text.setText(it.type == T_CHANNEL ? it.channel.name : it.text);
        tile.findViewById(R.id.live_dot).setVisibility(it.type == T_CHANNEL ? View.VISIBLE : View.GONE);
        // under-tile label only where the face doesn't already say it
        ((TextView) tile.findViewById(R.id.label)).setText(hasLogo ? it.channel.name : "");
    }

    // ---------------------------------------------------------------- header

    private void showHero(App a, boolean animate) {
        if (!a.label.contentEquals(hero.getText())) {
            hero.setText(a.label);
            if (animate) { hero.setAlpha(0.3f); hero.animate().alpha(1f).setDuration(220); }
        }
    }

    private void updateGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String part = h >= 4 && h < 12 ? "Good morning" : h >= 12 && h < 17 ? "Good afternoon"
                : h >= 17 && h < 22 ? "Good evening" : "Good night"; // night: 10 pm to 4 am
        greeting.setText(part + ", " + getString(R.string.greeting_name));
    }

    private void updateWifi() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        String text;
        if (nc == null) {
            text = "Not connected";
        } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            text = "Ethernet";
        } else {
            WifiInfo wi = getApplicationContext().getSystemService(WifiManager.class).getConnectionInfo();
            String ssid = wi == null ? null : wi.getSSID();
            text = ssid == null || ssid.contains("unknown ssid") ? "Wi-Fi" : ssid.replace("\"", "");
        }
        wifiName.setText(text);
    }

    /**
     * Picks the wallpaper for the time of day: files/wallpaper_<mood>.jpg if you pushed one, otherwise
     * wallpaper.jpg colour-graded for the mood. Only decodes when the file actually changes.
     */
    private void updateMood(boolean force) {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int m = h >= 5 && h < 11 ? 0 : h >= 11 && h < 17 ? 1 : h >= 17 && h < 20 ? 2 : 3;
        File f = custom("wallpaper_" + MOODS[m] + ".jpg");
        boolean own = f.exists();
        if (!own) f = custom("wallpaper.jpg");
        String path = f.exists() ? f.getPath() : null;
        if (force || !Objects.equals(path, wallpaperPath)) {
            wallpaperPath = path;
            loadWallpaper(path);
        }
        wallpaper.setColorFilter(own || MOOD_MATRIX[m] == null ? null : new ColorMatrixColorFilter(MOOD_MATRIX[m]));
    }

    private void loadWallpaper(String path) {
        if (path == null) { wallpaper.setImageDrawable(null); wallpaper.setVisibility(View.GONE); return; }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = Math.max(1, o.outWidth / 1920);
        o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory; fine for a photo
        Bitmap bmp = BitmapFactory.decodeFile(path, o);
        if (bmp == null) return;
        wallpaper.setImageBitmap(bmp); // the previous bitmap is dropped and collected; only one is ever held
        wallpaper.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- helpers

    private Typeface font(int weight) {
        return new Typeface.Builder(getAssets(), "fonts/Outfit.ttf")
                .setFontVariationSettings("'wght' " + weight).build();
    }

    /**
     * Some banners (e.g. Amazon miniTV) are adaptive icons: drawn as-is they're zoomed to 150% and masked,
     * cropping and squashing the artwork. Stack their two layers plainly instead.
     */
    static Drawable flatten(Drawable d) {
        if (!(d instanceof AdaptiveIconDrawable)) return d;
        AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
        if (a.getBackground() == null) return a.getForeground();
        if (a.getForeground() == null) return a.getBackground();
        return new LayerDrawable(new Drawable[]{a.getBackground(), a.getForeground()});
    }

    /** Animates size changes of {@code g}'s children (a pill growing to show its label) without delays. */
    private static void smoothResize(ViewGroup g) {
        LayoutTransition t = new LayoutTransition();
        t.enableTransitionType(LayoutTransition.CHANGING);
        t.setDuration(RESIZE_MS);
        for (int type : new int[]{LayoutTransition.APPEARING, LayoutTransition.DISAPPEARING,
                LayoutTransition.CHANGE_APPEARING, LayoutTransition.CHANGE_DISAPPEARING, LayoutTransition.CHANGING}) {
            t.setStartDelay(type, 0);
        }
        g.setLayoutTransition(t);
    }

    /**
     * A pill's label fades in only once the pill has finished widening (otherwise it overhangs the still-narrow
     * pill and gets clipped), and disappears at once when the pill shrinks.
     */
    private static void revealLabelAfterResize(ViewGroup pill) {
        LayoutTransition t = new LayoutTransition();
        t.setStartDelay(LayoutTransition.APPEARING, RESIZE_MS);
        t.setDuration(LayoutTransition.APPEARING, 140);
        t.setStartDelay(LayoutTransition.DISAPPEARING, 0);
        t.setDuration(LayoutTransition.DISAPPEARING, 0);
        t.setStartDelay(LayoutTransition.CHANGE_APPEARING, 0);
        t.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 0);
        pill.setLayoutTransition(t);
    }

    /** Files you can push to /sdcard/Android/data/dev.akash.rusty/files/ to customise Rusty. */
    private File custom(String name) { return new File(getExternalFilesDir(null), name); }

    private int px(int dps) { return Math.round(dps * dp); }

    private static int clamp(int v, int max) { return Math.max(0, Math.min(v, max)); }

    // ---------------------------------------------------------------- grid

    private static final class VH extends RecyclerView.ViewHolder { VH(View v) { super(v); } }

    private final class Adapter extends RecyclerView.Adapter<VH> {
        @Override public int getItemCount() { return loaded ? 1 + items.size() : 1; }

        @Override public int getItemViewType(int p) { return p == 0 ? T_HEADER : items.get(p - 1).type; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LayoutInflater in = LayoutInflater.from(HomeActivity.this);
            if (type == T_HEADER) return new VH(header); // single header instance
            if (type == T_TITLE) {
                TextView t = (TextView) in.inflate(R.layout.section_title, parent, false);
                t.setTypeface(light);
                return new VH(t);
            }
            View tile = in.inflate(type == T_APP ? R.layout.tile : R.layout.tile_text, parent, false);
            ((TextView) tile.findViewById(R.id.label)).setTypeface(regular);
            if (type != T_APP) ((TextView) tile.findViewById(R.id.text)).setTypeface(regular);
            return new VH(tile);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int p) {
            if (p == 0) return;
            Item it = items.get(p - 1);
            if (it.type == T_TITLE) { ((TextView) h.itemView).setText(it.text); return; }
            if (it.type == T_APP) bindApp(h.itemView, it.app); else bindText(h.itemView, it);
            View banner = h.itemView.findViewById(R.id.banner);
            boolean f = isGridFocus(p);
            banner.animate().cancel();
            banner.setScaleX(f ? TILE_SCALE : 1f);
            banner.setScaleY(f ? TILE_SCALE : 1f);
            h.itemView.findViewById(R.id.label).setAlpha(f ? 1f : 0f);
        }
    }
}
