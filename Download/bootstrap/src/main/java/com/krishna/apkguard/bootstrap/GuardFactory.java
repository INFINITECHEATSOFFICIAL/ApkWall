package com.krishna.apkguard.bootstrap;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import dalvik.system.DexClassLoader;

import java.io.File;
import java.lang.reflect.Constructor;

/**
 * Minimal runtime for Krishna APK Wall's local AES-GCM DEX wrapper.
 * The protected APK's pre-existing AppComponentFactory is loaded from the decrypted DEX set and
 * delegated to, preserving AndroidX CoreComponentFactory and compatible custom factories.
 */
public final class GuardFactory extends AppComponentFactory {
    private volatile GuardPayload payload;
    private volatile AppComponentFactory originalFactory;

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader parent, ApplicationInfo info) {
        try {
            GuardPayload loaded = GuardPayload.openAndDecrypt(info);
            GuardRuntimeChecks.enforce(loaded.getProtectionFlags());
            payload = loaded;
            ClassLoader decryptedLoader = new DexClassLoader(
                    loaded.getDexPath(),
                    new File(info.dataDir, "code_cache/krishna-apk-wall").getAbsolutePath(),
                    info.nativeLibraryDir,
                    parent
            );
            String factoryName = loaded.getOriginalFactoryClass();
            if (factoryName == null || factoryName.length() == 0) return decryptedLoader;

            Class<?> factoryClass = Class.forName(factoryName, true, decryptedLoader);
            if (!AppComponentFactory.class.isAssignableFrom(factoryClass)) {
                throw new IllegalStateException("Original appComponentFactory is not an AppComponentFactory: " + factoryName);
            }
            Constructor<?> constructor = factoryClass.getDeclaredConstructor();
            if (!constructor.isAccessible()) constructor.setAccessible(true);
            originalFactory = (AppComponentFactory) constructor.newInstance();
            ClassLoader selected = originalFactory.instantiateClassLoader(decryptedLoader, info);
            return selected != null ? selected : decryptedLoader;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Krishna APK Wall could not authenticate or load the protected DEX payload", e);
        }
    }

    @Override
    public Application instantiateApplication(ClassLoader classLoader, String className)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        GuardPayload current = payload;
        String original = current == null ? null : current.getOriginalApplicationClass();
        if (original == null || original.length() == 0) original = Application.class.getName();
        AppComponentFactory delegate = originalFactory;
        if (delegate != null) return delegate.instantiateApplication(classLoader, original);
        if (Application.class.getName().equals(original)) return new Application();
        try {
            Class<?> applicationClass = Class.forName(original, true, classLoader);
            if (!Application.class.isAssignableFrom(applicationClass)) {
                throw new InstantiationException("Original manifest Application is not an android.app.Application subclass");
            }
            Constructor<?> constructor = applicationClass.getDeclaredConstructor();
            if (!constructor.isAccessible()) constructor.setAccessible(true);
            return (Application) constructor.newInstance();
        } catch (ClassNotFoundException e) {
            throw e;
        } catch (IllegalAccessException e) {
            throw e;
        } catch (InstantiationException e) {
            throw e;
        } catch (Exception e) {
            InstantiationException wrapped = new InstantiationException("Could not create the original Application class");
            wrapped.initCause(e);
            throw wrapped;
        }
    }

    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        AppComponentFactory delegate = originalFactory;
        return delegate != null ? delegate.instantiateActivity(cl, className, intent)
                : super.instantiateActivity(cl, className, intent);
    }

    @Override
    public Service instantiateService(ClassLoader cl, String className, Intent intent)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        AppComponentFactory delegate = originalFactory;
        return delegate != null ? delegate.instantiateService(cl, className, intent)
                : super.instantiateService(cl, className, intent);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        AppComponentFactory delegate = originalFactory;
        return delegate != null ? delegate.instantiateReceiver(cl, className, intent)
                : super.instantiateReceiver(cl, className, intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader cl, String className)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        AppComponentFactory delegate = originalFactory;
        return delegate != null ? delegate.instantiateProvider(cl, className)
                : super.instantiateProvider(cl, className);
    }
}
