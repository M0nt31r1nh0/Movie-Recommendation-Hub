# Persistence tests

These scripts show that stopping all the VMs and starting them again, or destroying them and deploying a new cluster on the data disks, loses no application state. Run them in the management container, from `infra/` (see [../README.md → Testing persistence](../README.md#testing-persistence)). Their output, `*.txt`, is ignored by git.

| File | What it does |
|---|---|
| `snapshot.sh <label>` (`just snapshot <label>`) | Writes `<label>.txt`: the recommendation requests by status, the profiles database's row counts, the number of movies, the finished recommendation requests and their items, the Keycloak usernames, Kafka's topics and its consumer groups' offsets, Ollama's models and the volumes' status. Also writes `<label>-pods.txt`, the pod list, for reference. |
| `compare.sh <before> <after>` (`just compare <before> <after>`) | Compares two snapshots, ignoring the models' "N minutes ago" text and differences in spacing. Prints `IDENTICAL: nothing lost`, or the lines that differ. |
| `markers.sh` | Prints the markers of the destroy test (2026-10-07): a `persistence_check` row written in each database, the `persistence-check` topic's offsets and Kafka's cluster ID. Redirect it to `markers-<label>.txt` before and after, and `diff` them. |

## How to run

```bash
just snapshot before
just stop
just start
just snapshot after
just compare before after
```

Or, across a whole new cluster (`just destroy` keeps the data disks and the static IP):

```bash
just snapshot before
just destroy
just all
just snapshot rebuilt
just compare before rebuilt
```

Wait until no recommendation request is `PENDING` before the first snapshot: a request in flight when the VMs stop (or are destroyed) fails by design (a failed profile request is deleted, a failed typed request stays visible for an hour), so it would show up as a difference.

## What the result means

- **`IDENTICAL: nothing lost`:** every database, Kafka topic, model and volume came back unchanged.
- **Lines that differ:** a row count or a finished request that is missing is a real loss. A `PENDING` request that is gone is not: it was in flight and failed (see above).

The snapshots hold no secrets (usernames and counts only), but they are still ignored by git: they are evidence of one run, not source.
