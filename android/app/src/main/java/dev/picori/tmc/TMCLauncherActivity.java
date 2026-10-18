package dev.picori.tmc;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Keep the Activity on the display chosen by GammaOS's launcher. TMCActivity
 * selects the SDL surface's display before its first attachment; moving the
 * whole Activity instead does not match GammaOS Nano's primary-screen routing.
 */
public class TMCLauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, TMCActivity.class));
        finish();
    }
}
