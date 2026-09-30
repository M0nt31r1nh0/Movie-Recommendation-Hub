package psia.g17.dto;

/** A movie watched by people the user follows, and by how many of them. */
public record FriendWatch(int movieId, int watchers) {
}
