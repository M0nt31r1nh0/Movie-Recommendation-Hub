package psia.g17.dto;

/**
 * A SHA-256 fingerprint of a profile snapshot, in two parts: {@code own} covers the user's own
 * favourite genres, watched movies and watchlist; {@code social} covers what the people they follow
 * watched. {@code root} combines both, so equal roots mean identical recommendation inputs.
 */
public record Fingerprint(String root, String own, String social) {
}
