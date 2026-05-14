package com.schroedingersblock.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ApiKeyManager {

    private static final Path KEY_FILE = Paths.get(
            System.getProperty("user.home"), "Documents", "SchroedingersBlock-MaxWorkspace", "api_key.txt");

    public static String loadKey() {
        try {
            if (Files.exists(KEY_FILE)) {
                String key = Files.readString(KEY_FILE).trim();
                if (!key.isEmpty()) return key;
            }
        } catch (IOException e) {
            System.err.println("[SchroedingersBlock] Could not read API key file: " + e.getMessage());
        }
        return null;
    }

    public static void saveKey(String key) {
        try {
            Files.createDirectories(KEY_FILE.getParent());
            Files.writeString(KEY_FILE, key.trim());
            System.out.println("[SchroedingersBlock] API key saved to " + KEY_FILE);
        } catch (IOException e) {
            System.err.println("[SchroedingersBlock] Could not save API key: " + e.getMessage());
        }
    }

    public static boolean hasKey() {
        return loadKey() != null;
    }

    public static Path getKeyFilePath() {
        return KEY_FILE;
    }
}
