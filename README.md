# RosterGuard

Reliable employee roster synchronisation service. An independent implementation of a
hiring case study (HR Roster Sync). **Not** an official MoveInSync product.

## Status
Milestone 1 (foundation) done. Features are added milestone by milestone.
Design assumptions: [docs/DESIGN_DECISIONS.md](docs/DESIGN_DECISIONS.md).

## Run
```
docker compose up --build        # app on :8080, PostgreSQL on :5432
curl http://localhost:8080/actuator/health
```

## Test (needs JDK 21, Maven, and a running Docker daemon for Testcontainers)
```
mvn test
```

## Benchmarks
**PENDING.** Nothing has been measured yet.
