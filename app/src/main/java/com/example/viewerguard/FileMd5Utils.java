package com.example.viewerguard;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

public final class FileMd5Utils {

    private FileMd5Utils() {
    }

    public static String md5(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return null;
        }
        byte[] buffer = new byte[16 * 1024];
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
