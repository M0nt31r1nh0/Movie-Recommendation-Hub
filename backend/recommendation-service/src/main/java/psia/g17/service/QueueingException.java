package psia.g17.service;

/** A request couldn't be queued: profiles-service or Kafka didn't answer. Nothing was stored for it. */
public class QueueingException extends RuntimeException {

    public QueueingException(String message, Throwable cause) {
        super(message, cause);
    }
}
