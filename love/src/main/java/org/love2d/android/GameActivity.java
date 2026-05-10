/**
 * Copyright (c) 2006-2023 LOVE Development Team
 *
 * This software is provided 'as-is', without any express or implied
 * warranty.  In no event will the authors be held liable for any damages
 * arising from the use of this software.
 *
 * Permission is granted to anyone to use this software for any purpose,
 * including commercial applications, and to alter it and redistribute it
 * freely, subject to the following restrictions:
 *
 * 1. The origin of this software must not be misrepresented; you must not
 *    claim that you wrote the original software. If you use this software
 *    in a product, an acknowledgment in the product documentation would be
 *    appreciated but is not required.
 * 2. Altered source versions must be plainly marked as such, and must not be
 *    misrepresented as being the original software.
 * 3. This notice may not be removed or altered from any source distribution.
 **/

package org.love2d.android;

import org.libsdl.app.SDLActivity;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Vibrator;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.*;
import android.content.pm.PackageManager;

import androidx.annotation.Keep;
import androidx.core.app.ActivityCompat;

public class GameActivity extends SDLActivity {
    private static DisplayMetrics metrics = null;
    private static String gamePath = "";
    private static Vibrator vibrator = null;
    private static volatile String pendingURL = null;
    private static volatile String pendingShareResult = null;
    protected final int[] externalStorageRequestDummy = new int[1];
    protected final int[] recordAudioRequestDummy = new int[1];
    public static final int EXTERNAL_STORAGE_REQUEST_CODE = 2;
    public static final int RECORD_AUDIO_REQUEST_CODE = 3;
    public static final int SHARE_REQUEST_CODE = 4;
    private static boolean immersiveActive = false;
    private static boolean needToCopyGameInArchive = false;
    private boolean storagePermissionUnnecessary = false;
    private boolean shortEdgesMode = false;
    public boolean embed = false;
    public int safeAreaTop = 0;
    public int safeAreaLeft = 0;
    public int safeAreaBottom = 0;
    public int safeAreaRight = 0;

    private static native void nativeSetDefaultStreamValues(int sampleRate, int framesPerBurst);

    @Override
    protected String[] getLibraries() {
        return new String[] {
            "c++_shared",
            "mpg123",
            "openal",
            "love",
        };
    }

    @Override
    protected String getMainSharedObject() {
        String[] libs = getLibraries();
        String libname = "lib" + libs[libs.length - 1] + ".so";

        // Since Lollipop, you can simply pass "libname.so" to dlopen
        // and it will resolve correct paths and load correct library.
        // This is mandatory for extractNativeLibs=false support in
        // Marshmallow.
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            return libname;
        } else {
            return getContext().getApplicationInfo().nativeLibraryDir + "/" + libname;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d("GameActivity", "started");

        int res = checkCallingOrSelfPermission(Manifest.permission.VIBRATE);
        if (res == PackageManager.PERMISSION_GRANTED) {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        } else {
            Log.d("GameActivity", "Vibration disabled: could not get vibration permission.");
        }

        // These 2 variables must be reset or it will use the existing value.
        gamePath = "";
        storagePermissionUnnecessary = false;
        embed = getResources().getBoolean(R.bool.embed);

        handleIntent(getIntent());
        setIntent(null);

        super.onCreate(savedInstanceState);
        metrics = getResources().getDisplayMetrics();

        // Set low-latency audio values
        nativeSetDefaultStreamValues(getAudioFreq(), getAudioSMP());

        if (android.os.Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            shortEdgesMode = false;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Log.d("GameActivity", "onNewIntent() with " + intent);
        Uri data = intent.getData();
        if (isHandledURL(data)) {
            Log.d("GameActivity", "URL received: " + data.toString());
            pendingURL = data.toString();
            return;
        }
        if (!embed) {
            handleIntent(intent);
            resetNative();
            startNative();
        }
    }

    protected void handleIntent(Intent intent) {
        Uri game = intent.getData();

        if (game != null && isHandledURL(game)) {
            Log.d("GameActivity", "Received URL: " + game.toString());
            pendingURL = game.toString();
        } else {
            // No game specified via the intent data or embed build is used.
            // Load game archive only when needed.
            needToCopyGameInArchive = embed;
            gamePath = "";
        }

        Log.d("GameActivity", "new gamePath: " + gamePath);
    }

    private void copyGameInsideArchive() {
        try {
            // If we have a game.love in our assets folder copy it to the cache folder
            // so that we can load it from native LÖVE code
            AssetManager assetManager = getAssets();
            InputStream gameStream = assetManager.open("game.love");
            String destinationFile = this.getCacheDir().getPath() + "/game.love";

            if (copyAssetFile(gameStream, destinationFile))
                gamePath = destinationFile;
            else
                gamePath = "game.love";
            storagePermissionUnnecessary = true;
        } catch (IOException e) {
            // There's no game.love in our assets
            Log.d("GameActivity", "Could not open game.love from assets: " + e.getMessage());
        }
    }

    protected void checkLovegameFolder() {
        // If no game.love was found and embed flavor is not used, fall back to the game in
        // <external storage>/Android/data/<package name>/games/lovegame
        if (!embed) {
            Log.d("GameActivity", "fallback to lovegame folder");
            File ext = getExternalFilesDir("games");
            if ((new File(ext, "/lovegame/main.lua")).exists()) {
                gamePath = ext.getPath() + "/lovegame/";
                storagePermissionUnnecessary = true;
            } else if (android.os.Build.VERSION.SDK_INT <= 28) {
                // Try to fallback to /sdcard/lovegame in Android 9 and earlier too.
                if (hasExternalStoragePermission()) {
                    ext = Environment.getExternalStorageDirectory();
                    if ((new File(ext, "/lovegame/main.lua")).exists()) {
                        gamePath = ext.getPath() + "/lovegame/";
                        storagePermissionUnnecessary = false;
                    }
                } else {
                    Log.d("GameActivity", "Cannot load game from /sdcard/lovegame: permission not granted");
                }
            }

            Log.d("GameActivity", "lovegame directory: " + gamePath);
        }
    }

    @Override
    protected void onDestroy() {
        if (vibrator != null) {
            Log.d("GameActivity", "Cancelling vibration");
            vibrator.cancel();
        }
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        if (vibrator != null) {
            Log.d("GameActivity", "Cancelling vibration");
            vibrator.cancel();
        }
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (immersiveActive && android.os.Build.VERSION.SDK_INT >= 30) {
            applyWindowInsetsImmersive(true);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SHARE_REQUEST_CODE) {
            pendingShareResult = (resultCode == RESULT_OK) ? "1" : "0";
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Keep
    public void setImmersiveMode(boolean immersive_mode) {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = immersive_mode ?
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES :
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            shortEdgesMode = immersive_mode;
        }

        immersiveActive = immersive_mode;

        // On API 30+ the deprecated setSystemUiVisibility flags no longer reliably hide
        // system bars (Android 15 enforces edge-to-edge for targetSdk 35). Use the modern
        // WindowInsetsController instead. Must run on the UI thread — setImmersiveMode is
        // called from SDLThread via nativeRunMain, and WindowInsetsController touches views.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            final boolean immersive = immersive_mode;
            runOnUiThread(() -> applyWindowInsetsImmersive(immersive));
        }
    }

    @androidx.annotation.RequiresApi(30)
    private void applyWindowInsetsImmersive(boolean immersive) {
        android.view.WindowInsetsController controller = getWindow().getInsetsController();
        if (controller == null) return;
        if (immersive) {
            controller.hide(android.view.WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            controller.show(android.view.WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(
                android.view.WindowInsetsController.BEHAVIOR_DEFAULT);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && immersiveActive && android.os.Build.VERSION.SDK_INT >= 30) {
            applyWindowInsetsImmersive(true);
        }
    }

    @Keep
    public boolean getImmersiveMode() {
        return immersiveActive;
    }

    @Keep
    public static String getGamePath() {
        GameActivity self = (GameActivity) mSingleton; // use SDL provided one
        Log.d("GameActivity", "called getGamePath(), game path = " + gamePath);

        if (gamePath.length() > 0) {
            if (self.storagePermissionUnnecessary || self.hasExternalStoragePermission()) {
                return gamePath;
            } else {
                Log.d("GameActivity", "cannot open game " + gamePath + ": no external storage permission given!");
            }
        } else if (needToCopyGameInArchive) {
            self.copyGameInsideArchive();
        } else {
            self.checkLovegameFolder();
        }

        return gamePath;
    }

    public static DisplayMetrics getMetrics() {
        return metrics;
    }

    @Keep
    public static void vibrate(double seconds) {
        if (vibrator != null) {
            vibrator.vibrate((long) (seconds * 1000.));
        }
    }

    @Keep
    public static boolean openURLFromLOVE(String url) {
        Log.d("GameActivity", "opening url = " + url);
        return openURL(url) == 0;
    }

    private static boolean isHandledURL(Uri uri) {
        if (uri == null) return false;
        if ("mushrooms".equals(uri.getScheme())) return true;
        if ("https".equals(uri.getScheme())) {
            String host = uri.getHost();
            return "mshr.ms".equals(host)
                || "spilledmushroo.ms".equals(host)
                || "spilledmushrooms.com".equals(host);
        }
        return false;
    }

    @Keep
    public static String getPendingURL() {
        String url = pendingURL;
        pendingURL = null;
        return url != null ? url : "";
    }

    @Keep
    public static void showShareSheet(String text, String url) {
        GameActivity self = (GameActivity) mSingleton;
        if (self == null) return;

        self.runOnUiThread(() -> {
            String shareText = text.isEmpty() ? url : (url.isEmpty() ? text : text + "\n" + url);
            if (shareText.isEmpty()) return;

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);

            self.startActivityForResult(Intent.createChooser(shareIntent, null), SHARE_REQUEST_CODE);
        });
    }

    @Keep
    public static String getPendingShareResult() {
        String result = pendingShareResult;
        pendingShareResult = null;
        return result != null ? result : "";
    }

    /**
     * Copies a given file from the assets folder to the destination.
     *
     * @return true if successful
     */
    boolean copyAssetFile(InputStream source, String destinationFileName) {
        boolean success = false;

        BufferedOutputStream destination = null;
        try {
            destination = new BufferedOutputStream(new FileOutputStream(destinationFileName, false));
        } catch (IOException e) {
            Log.d("GameActivity", "Could not open destination file: " + e.getMessage());
        }

        // perform the copying
        int chunk_read;
        int bytes_written = 0;

        assert (source != null && destination != null);

        try {
            byte[] buf = new byte[1024];
            chunk_read = source.read(buf);
            do {
                destination.write(buf, 0, chunk_read);
                bytes_written += chunk_read;
                chunk_read = source.read(buf);
            } while (chunk_read != -1);
        } catch (IOException e) {
            Log.d("GameActivity", "Copying failed:" + e.getMessage());
        }

        // close streams
        try {
            source.close();
            destination.close();
            success = true;
        } catch (IOException e) {
            Log.d("GameActivity", "Copying failed: " + e.getMessage());
        }

        Log.d("GameActivity", "Successfully copied stream to " + destinationFileName + " (" + bytes_written + " bytes written).");
        return success;
    }

    @Keep
    public boolean hasBackgroundMusic() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        return audioManager.isMusicActive();
    }

    @Keep
    public void showRecordingAudioPermissionMissingDialog() {
        Log.d("GameActivity", "showRecordingAudioPermissionMissingDialog()");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                AlertDialog dialog = new AlertDialog.Builder(mSingleton)
                    .setTitle("Audio Recording Permission Missing")
                    .setMessage("It appears that this game uses mic capabilities. The game may not work correctly without mic permission!")
                    .setNeutralButton("Continue", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface di, int id) {
                            synchronized (recordAudioRequestDummy) {
                                recordAudioRequestDummy.notify();
                            }
                        }
                    })
                    .create();
                dialog.show();
            }
        });

        synchronized (recordAudioRequestDummy) {
            try {
                recordAudioRequestDummy.wait();
            } catch (InterruptedException e) {
                Log.d("GameActivity", "mic permission dialog", e);
            }
        }
    }

    public void showExternalStoragePermissionMissingDialog() {
        AlertDialog dialog = new AlertDialog.Builder(mSingleton)
            .setTitle("Storage Permission Missing")
            .setMessage("LÖVE for Android will not be able to run non-packaged games without storage permission.")
            .setNeutralButton("Continue", null)
            .create();
        dialog.show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (grantResults.length > 0) {
            Log.d("GameActivity", "Received a request permission result");

            switch (requestCode) {
                case EXTERNAL_STORAGE_REQUEST_CODE: {
                    if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                        Log.d("GameActivity", "Permission granted");
                    } else {
                        Log.d("GameActivity", "Did not get permission.");
                        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_EXTERNAL_STORAGE)) {
                            showExternalStoragePermissionMissingDialog();
                        }
                    }

                    Log.d("GameActivity", "Unlocking LÖVE thread");
                    synchronized (externalStorageRequestDummy) {
                        externalStorageRequestDummy[0] = grantResults[0];
                        externalStorageRequestDummy.notify();
                    }
                    break;
                }
                case RECORD_AUDIO_REQUEST_CODE: {
                    if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                        Log.d("GameActivity", "Mic permission granted");
                    } else {
                        Log.d("GameActivity", "Did not get mic permission.");
                    }

                    Log.d("GameActivity", "Unlocking LÖVE thread");
                    synchronized (recordAudioRequestDummy) {
                        recordAudioRequestDummy[0] = grantResults[0];
                        recordAudioRequestDummy.notify();
                    }
                    break;
                }
                default:
                    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            }
        }
    }

    @Keep
    public boolean hasExternalStoragePermission() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }

        Log.d("GameActivity", "Requesting permission and locking LÖVE thread until we have an answer.");
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, EXTERNAL_STORAGE_REQUEST_CODE);

        synchronized (externalStorageRequestDummy) {
            try {
                externalStorageRequestDummy.wait();
            } catch (InterruptedException e) {
                Log.d("GameActivity", "requesting external storage permission", e);
                return false;
            }
        }

        return ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @Keep
    public boolean hasRecordAudioPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Keep
    public void requestRecordAudioPermission() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Log.d("GameActivity", "Requesting mic permission and locking LÖVE thread until we have an answer.");
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_AUDIO_REQUEST_CODE);

        synchronized (recordAudioRequestDummy) {
            try {
                recordAudioRequestDummy.wait();
            } catch (InterruptedException e) {
                Log.d("GameActivity", "requesting mic permission", e);
            }
        }
    }

    @Keep
    public boolean initializeSafeArea() {
        if (android.os.Build.VERSION.SDK_INT >= 28 && shortEdgesMode) {
            DisplayCutout cutout = getWindow().getDecorView().getRootWindowInsets().getDisplayCutout();

            if (cutout != null) {
                safeAreaTop = cutout.getSafeInsetTop();
                safeAreaLeft = cutout.getSafeInsetLeft();
                safeAreaBottom = cutout.getSafeInsetBottom();
                safeAreaRight = cutout.getSafeInsetRight();
                return true;
            }
        }

        return false;
    }

    @Keep
    public String[] buildFileTree() {
        // Map key is path, value is directory flag
        HashMap<String, Boolean> map = buildFileTree(getAssets(), "", new HashMap<String, Boolean>());
        ArrayList<String> result = new ArrayList<String>();

        for (Map.Entry<String, Boolean> data: map.entrySet()) {
            result.add((data.getValue() ? "d" : "f") + data.getKey());
        }

        String[] r = new String[result.size()];
        result.toArray(r);
        return r;
    }

    private HashMap<String, Boolean> buildFileTree(AssetManager assetManager, String dir, HashMap<String, Boolean> map) {
        String strippedDir = dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;

        // Try open dir
        try {
            InputStream test = assetManager.open(strippedDir);
            // It's a file
            test.close();
            map.put(strippedDir, false);
        } catch (FileNotFoundException e) {
            // It's a directory
            String[] list = null;

            // List files
            try {
                list = assetManager.list(strippedDir);
            } catch (IOException e2) {
                Log.e("GameActivity", strippedDir, e2);
            }

            // Mark as file
            map.put(dir, true);

            // This Object comparison is intentional.
            if (strippedDir != dir) {
                map.put(strippedDir, true);
            }

            if (list != null) {
                for (String path: list) {
                    buildFileTree(assetManager, dir + path + "/", map);
                }
            }
        } catch (IOException e) {
            Log.e("GameActivity", dir, e);
        }

        return map;
    }

    public int getAudioSMP() {
        int smp = 256;

        if (android.os.Build.VERSION.SDK_INT >= 17) {
            AudioManager a = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int b = Integer.parseInt(a.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
            return b > 0 ? b : smp;
        }

        return smp;
    }

    public int getAudioFreq() {
        int freq = 44100;

        if (android.os.Build.VERSION.SDK_INT >= 17) {
            AudioManager a = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int b = Integer.parseInt(a.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE));
            return b > 0 ? b : freq;
        }

        return freq;
    }

    public boolean isNativeLibsExtracted() {
        ApplicationInfo appInfo = getApplicationInfo();

        if (android.os.Build.VERSION.SDK_INT >= 23) {
            return (appInfo.flags & ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0;
        }

        return true;
    }

    @Keep
    public String getCRequirePath() {
        ApplicationInfo applicationInfo = getApplicationInfo();

        if (isNativeLibsExtracted()) {
            return applicationInfo.nativeLibraryDir + "/?.so";
        } else {
            // The native libs are inside the APK and can be loaded directly.
            // FIXME: What about split APKs?
            String abi;

            if (android.os.Build.VERSION.SDK_INT >= 21) {
                 abi = android.os.Build.SUPPORTED_ABIS[0];
            } else {
                // This codepath should NEVER be taken as if isNativeLibsExtracted()
                // returns false, it's 100% safe to assume we're on API level 23 or later.
                abi = android.os.Build.CPU_ABI;
            }

            return applicationInfo.sourceDir + "!/lib/" + abi + "/?.so";
        }
    }
}
