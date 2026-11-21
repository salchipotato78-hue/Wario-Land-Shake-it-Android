package com.wario.shakeit;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The game's saves (the Wii NAND's title folder that holds WLS.bin and banner.bin), the same as the PC
 * launcher's: export and import as one zip, and a copy before each start (last 10 kept). The runtime
 * keeps them in NAND/title/00010000/52574c45/data, or in Saves/<game>/NAND when another game owns the
 * shared NAND, so the folder is looked up in both places.
 */
final class Saves {

    static final String SAVE_FILE = "WLS.bin";
    private static final String TITLE_PATH = "title/00010000/52574c45/data";
    private static final String BACKUP_DIR = "SaveBackups";
    private static final int KEEP_BACKUPS = 10;
    private static final String PREFS = "launcher";
    private static final String KEY_BACKUP_ON_START = "backup_on_start";

    private Saves() {
    }

    /** The folder with the game's save files: the one that has WLS.bin, or the default (created on demand). */
    static File saveDir(Context context) {
        File data = SetupActivity.dataDir(context);
        File shared = new File(data, "NAND/" + TITLE_PATH);
        if (new File(shared, SAVE_FILE).isFile()) {
            return shared;
        }
        File[] games = new File(data, "Saves").listFiles();
        if (games != null) {
            for (File game : games) {
                File own = new File(game, "NAND/" + TITLE_PATH);
                if (new File(own, SAVE_FILE).isFile()) {
                    return own;
                }
            }
        }
        return shared;
    }

    static boolean hasSaves(Context context) {
        return new File(saveDir(context), SAVE_FILE).isFile();
    }

    static boolean backupOnStart(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_BACKUP_ON_START, true);
    }

    static void setBackupOnStart(Context context, boolean on) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BACKUP_ON_START, on).apply();
    }

    /** True while the game runs in its own process (saves must not change under it). */
    static boolean gameRunning(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes == null) {
            return false;
        }
        for (ActivityManager.RunningAppProcessInfo info : processes) {
            if ((context.getPackageName() + ":game").equals(info.processName)) {
                return true;
            }
        }
        return false;
    }

    /** Copies the current saves to SaveBackups/<time>/ and keeps the newest ten. Does nothing without saves. */
    static void backupNow(Context context) {
        File src = saveDir(context);
        File[] files = src.listFiles(File::isFile);
        if (files == null || files.length == 0 || !new File(src, SAVE_FILE).isFile()) {
            return;
        }
        File root = new File(SetupActivity.dataDir(context), BACKUP_DIR);
        File dst = new File(root, new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()));
        if (!dst.mkdirs()) {
            return;
        }
        try {
            for (File f : files) {
                copy(f, new File(dst, f.getName()));
            }
        } catch (IOException e) {
            return;
        }
        File[] backups = root.listFiles(File::isDirectory);
        if (backups != null && backups.length > KEEP_BACKUPS) {
            Arrays.sort(backups, (a, b) -> a.getName().compareTo(b.getName()));
            for (int i = 0; i < backups.length - KEEP_BACKUPS; i++) {
                deleteRecursive(backups[i]);
            }
        }
    }

    static int backupCount(Context context) {
        File[] backups = new File(SetupActivity.dataDir(context), BACKUP_DIR).listFiles(File::isDirectory);
        return backups == null ? 0 : backups.length;
    }

    /** Writes every save file into a zip (flat names, no folders). */
    static void exportTo(Context context, OutputStream out) throws IOException {
        File src = saveDir(context);
        File[] files = src.listFiles(File::isFile);
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            if (files != null) {
                for (File f : files) {
                    zip.putNextEntry(new ZipEntry(f.getName()));
                    try (InputStream in = new FileInputStream(f)) {
                        copyStream(in, zip);
                    }
                    zip.closeEntry();
                }
            }
        }
    }

    /**
     * Replaces the saves with the zip's. The current saves are backed up first. The zip must contain
     * WLS.bin, so a random zip is never written over the saves. Returns the number of files imported.
     */
    static int importFrom(Context context, Uri uri) throws IOException {
        InputStream raw = context.getContentResolver().openInputStream(uri);
        if (raw == null) {
            throw new IOException("cannot open the file");
        }
        // Read it all first: a bad zip must not touch the saves.
        List<String> names = new ArrayList<>();
        List<byte[]> contents = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = new File(entry.getName()).getName();
                if (!entry.isDirectory() && isSaveName(name)) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    copyStream(zip, bytes);
                    names.add(name);
                    contents.add(bytes.toByteArray());
                }
                zip.closeEntry();
            }
        }
        if (!names.contains(SAVE_FILE)) {
            throw new IOException("no " + SAVE_FILE + " in the zip");
        }

        backupNow(context);
        File dst = saveDir(context);
        if (!dst.isDirectory() && !dst.mkdirs()) {
            throw new IOException("cannot create the saves folder");
        }
        File[] old = dst.listFiles(File::isFile);
        if (old != null) {
            for (File f : old) {
                f.delete();
            }
        }
        for (int i = 0; i < names.size(); i++) {
            try (OutputStream out = new FileOutputStream(new File(dst, names.get(i)))) {
                out.write(contents.get(i));
            }
        }
        return names.size();
    }

    // Only the files the game keeps in its save folder.
    private static boolean isSaveName(String name) {
        return name.equals(SAVE_FILE) || name.equals("banner.bin");
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            copyStream(in, out);
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
    }

    private static void deleteRecursive(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }
}
