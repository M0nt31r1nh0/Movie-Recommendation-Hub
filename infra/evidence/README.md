# Persistence tests

These scripts show that stopping all the VMs and starting them again, or destroying them and deploying a new cluster on the data disks, loses no application state. Run them in the management container, from `infra/` (see [../README.md → Testing persistence](../README.md#testing-persistence)). Their output, `*.txt`, is ignored by git.

| File | What it does |
|---|---|
| `snapshot.sh <label>` (`just snapshot <label>`) | Writes `<label>.txt` with the recommendation requests by status, the profiles database's row counts, the number of movies, the finished recommendation requests and their items, the Keycloak usernames, Kafka's topics and its consumer groups' offsets, Ollama's models, Prometheus's oldest sample (a new Prometheus would have a later one), when Grafana's users and organisations were created (read from a copy of its database) and the volumes' status. Also writes `<label>-pods.txt`, the pod list, for reference. |
| `compare.sh <before> <after>` (`just compare <before> <after>`) | Compares two snapshots, ignoring the models' "N minutes ago" text and differences in spacing. Prints `IDENTICAL: nothing lost`, or the lines that differ. |
| `markers.sh` | Prints the markers of the destroy test (2026-10-07), which are a `persistence_check` row written in each database, the `persistence-check` topic's offsets and Kafka's cluster ID. Redirect it to `markers-<label>.txt` before and after, and `diff` them. |

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

Wait until no recommendation request is `PENDING` before the first snapshot. A request in flight when the VMs stop (or are destroyed) fails by design (a failed profile request is deleted, a failed typed request stays visible for an hour), so it would show up as a difference.

## What the result means

- **`IDENTICAL: nothing lost`** means every database, Kafka topic, model and volume came back unchanged.
- **Lines that differ** mean a real loss when a row count or a finished request is missing. A `PENDING` request that is gone is not a loss, because it was in flight and failed (see above).

The snapshots hold no secrets (usernames and counts only), but git still ignores them, since each one is the evidence of a single run.
