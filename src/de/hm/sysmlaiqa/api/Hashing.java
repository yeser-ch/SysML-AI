package de.hm.sysmlaiqa.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Kurze SHA-256-Prüfsummen (12 Hex-Zeichen) zur eindeutigen Kennzeichnung von Prompts, Exporten und Antworten. */
public final class Hashing {
    private Hashing() { }

    public static String sha256Short(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "nohash";
        }
    }
}
