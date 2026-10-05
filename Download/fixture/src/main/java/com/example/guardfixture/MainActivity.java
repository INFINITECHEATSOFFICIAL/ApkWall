package com.example.guardfixture;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String assetResult;
        try (InputStream input = getAssets().open("guard.txt");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            assetResult = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Exception failure) {
            assetResult = "ASSET DECRYPT FAILED: " + failure.getClass().getSimpleName();
        }

        String report = (FixtureApp.started ? "DEX WRAPPER RUNTIME TEST PASSED" : "ORIGINAL APPLICATION DID NOT RUN")
                + "\nSTRING DECODE PATH EXECUTED"
                + "\nASSET: " + assetResult
                + "\nRESOURCE ID: " + getString(R.string.fixture_title);
        TextView message = new TextView(this);
        message.setText(report);
        message.setTextColor(Color.rgb(35, 52, 75));
        message.setTextSize(18f);
        message.setGravity(17);
        message.setPadding(20, 20, 20, 20);
        setContentView(message);
    }
}
