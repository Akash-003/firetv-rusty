package dev.akash.rusty;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS protects its launcher (no disable/hide without root) and handles Home before accessibility
 * services see it, then blocks other apps' launches for 5 s. So:
 *  - the remote's Menu (☰) button opens Rusty instantly (it does reach the key filter);
 *  - Home stays Amazon's, except right after boot, when Rusty replaces Amazon's home once.
 */
public class HomeKeyService extends AccessibilityService {
    // ponytail: "just booted" = first 3 min of uptime; a boot-completed receiver if this ever misfires
    private static final long BOOT_WINDOW_MS = 3 * 60_000;
    private boolean tookOverAfterBoot;

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_MENU) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) openRusty();
        return true; // swallow both down and up so the app underneath never sees Menu
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        CharSequence cls = e.getClassName();
        if (!tookOverAfterBoot && SystemClock.elapsedRealtime() < BOOT_WINDOW_MS
                && cls != null && cls.toString().endsWith("HomeActivity_vNext")) {
            tookOverAfterBoot = true;
            openRusty();
        }
    }

    /** Plain start, no CATEGORY_HOME: a HOME intent would compete with Amazon's launcher for the home slot and lose. */
    private void openRusty() {
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    @Override
    public void onInterrupt() { }
}
