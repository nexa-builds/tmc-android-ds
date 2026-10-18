package dev.picori.tmc;

import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

/**
 * Project Picori's SDL3 Android shell. Normally SDL owns the Activity's screen
 * and SecondScreenManager hosts the companion on another display. With SWAP
 * SCREENS enabled, keep the Activity and companion on the default display and
 * host SDL in a Presentation instead. This is the GammaOS Nano / RG DS Plus
 * layout: lower Activity, upper game, without moving the Activity itself.
 */
public class TMCActivity extends SDLActivity {
    private static final String TAG = "TMCActivity";
    private static final int IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;

    private SecondScreenManager mSecondScreen;
    private Presentation mGamePresentation;

    // SDL3's SDLSurface constructor is protected. Subclass it rather than
    // changing the vendored SDL glue just to supply a Presentation context.
    private static final class PresentationSurface extends SDLSurface {
        PresentationSurface(Context context) {
            super(context);
        }
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "main" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // This MUST precede super.onCreate(): SDL calls createSDLSurface there.
        // A surface created with the lower Activity's context and moved after
        // attachment has already acquired the wrong display/window resources.
        Display gameDisplay = GameScreenLayout.findGameDisplay(this);
        if (gameDisplay != null) {
            try {
                mGamePresentation = new Presentation(this, gameDisplay,
                        android.R.style.Theme_Material_NoActionBar_Fullscreen);
                mGamePresentation.setCancelable(false);
            } catch (RuntimeException e) {
                mGamePresentation = null;
                Log.w(TAG, "Could not create the game Presentation; using the default layout", e);
            }
        }

        super.onCreate(savedInstanceState);
        if (mSurface == null || mLayout == null) {
            // SDL already shows its library/version error dialog in this case.
            // Do not show an empty game Presentation from onStart().
            mGamePresentation = null;
            return;
        }
        applyImmersiveMode();
        if (mGamePresentation == null || !attachGamePresentation()) {
            mSecondScreen = new SecondScreenManager(this);
        }
    }

    @Override
    protected SDLSurface createSDLSurface(Context context) {
        if (mGamePresentation != null) {
            return new PresentationSurface(mGamePresentation.getContext());
        }
        return super.createSDLSurface(context);
    }

    /** Re-home SDL before the first layout pass; it must never attach below. */
    private boolean attachGamePresentation() {
        if (mSurface.getParent() instanceof ViewGroup) {
            ((ViewGroup) mSurface.getParent()).removeView(mSurface);
        }
        Window window = mGamePresentation.getWindow();
        if (window != null) {
            // Controller focus stays in the lower Activity; touch on the upper
            // game surface still works. B must not dismiss this Dialog.
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            window.getDecorView().setSystemUiVisibility(IMMERSIVE_FLAGS);
        }
        mGamePresentation.setContentView(mSurface, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        try {
            mGamePresentation.show();
        } catch (RuntimeException e) {
            Log.w(TAG, "Game Presentation rejected; using the default layout", e);
            mGamePresentation.dismiss();
            mGamePresentation = null;
            // show() failed before the first traversal. Replace the unattached
            // surface so its cached Display/context also belongs to the fallback.
            mSurface = super.createSDLSurface(this);
            mLayout.addView(mSurface, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return false;
        }
        mLayout.addView(new SecondScreenView(this), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        SecondScreenManager.nativeSetGameOnSecondaryDisplay(true);
        Log.i(TAG, "Game Presentation on display " + mGamePresentation.getDisplay().getDisplayId()
                + ", companion Activity on display "
                + getWindowManager().getDefaultDisplay().getDisplayId());
        return true;
    }

    // The upper SDL surface is not in the Activity's view tree. Forward the
    // lower window's controller/keyboard input exactly as SDL's listener would.
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mGamePresentation != null && mSurface != null) {
            int key = event.getKeyCode();
            if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN
                    && key != KeyEvent.KEYCODE_CAMERA && key != KeyEvent.KEYCODE_ZOOM_IN
                    && key != KeyEvent.KEYCODE_ZOOM_OUT) {
                return mSurface.onKey(mSurface, key, event);
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (mGamePresentation != null && mSurface != null
                && event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            return mSurface.dispatchGenericMotionEvent(event);
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    private void applyImmersiveMode() {
        Window window = getWindow();
        if (window != null) {
            window.getDecorView().setSystemUiVisibility(IMMERSIVE_FLAGS);
        }
        if (mGamePresentation != null && mGamePresentation.getWindow() != null) {
            mGamePresentation.getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE_FLAGS);
        }
        // Keep SDL's re-hide watchdog armed after transient system-bar reveals.
        mFullscreenModeActive = true;
    }

    // Match SDL's onStart/onStop boundary, not transient onPause/onResume.
    // Hide, rather than dismiss, the upper window so its context stays valid
    // across a background/foreground cycle.
    @Override
    protected void onStart() {
        super.onStart();
        if (mSecondScreen != null) {
            mSecondScreen.start();
        }
        if (mGamePresentation != null) {
            try {
                mGamePresentation.show();
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not re-show the game Presentation; relaunch required", e);
            }
        }
    }

    @Override
    protected void onStop() {
        if (mSecondScreen != null) {
            mSecondScreen.stop();
        }
        if (mGamePresentation != null) {
            mGamePresentation.hide();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (mGamePresentation != null) {
            mGamePresentation.dismiss();
            mGamePresentation = null;
        }
        super.onDestroy();
    }
}
