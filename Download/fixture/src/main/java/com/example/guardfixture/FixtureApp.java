package com.example.guardfixture;

import android.app.Application;

public final class FixtureApp extends Application {
    public static volatile boolean started;
    @Override public void onCreate() {
        super.onCreate();
        started = true;
    }
}
