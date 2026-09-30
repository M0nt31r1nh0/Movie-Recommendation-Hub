package psia.g17.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

final class Hashes {

    private Hashes() {
    }

    /** A typed request's fingerprint: the profile's, combined with the prompt as the user meant it. */
    static String prompt(String profileRoot, String prompt) {
        return sha256(profileRoot + "|" + prompt.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
