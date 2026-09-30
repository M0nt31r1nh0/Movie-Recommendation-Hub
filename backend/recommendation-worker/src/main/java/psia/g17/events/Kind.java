package psia.g17.events;

public enum Kind {
    /** Ten movies for the user's profile, e.g. prepared by the background agent. */
    PROFILE,
    /** Ten movies for a request the user typed, e.g. "a horror movie from the 80s". */
    PROMPT
}
