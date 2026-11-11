# aiSector-Velocity

**Kompletny workspace (shared, audytory, źródła testów runtime i dokumentacja):** [aiSector/WORKSPACE.md](https://github.com/BusioMeister/aiSector/blob/master/WORKSPACE.md). Kod proxy i jego unit tests są w `src/`, wspólne źródła do samodzielnego buildu w `build-support/shared/`.

## Rewrite 2.0 — 2026-11-02

Velocity **4.3.1**, Java **25**. Backend: [aiSector](https://github.com/BusioMeister/aiSector).

Z samodzielnego klonu uruchom `.\build.ps1` (Windows) lub `sh build.sh` (Linux/macOS). Maven Wrapper oraz lokalny snapshot parent/shared są dołączone; skrypt instaluje je przed `clean verify`. Wynik: `target/aiSector-Velocity-2.0.0-SNAPSHOT.jar`.

`build-support/source-manifest.json` zawiera hashe wejść wspólnego modułu. Przy zmianie protokołu publikować oba repozytoria jako zgodny zestaw.

Adresy backendów i forwarding konfiguruj w `velocity.toml`. Redis/Mongo ustawiaj przez JVM properties albo environment:

| JVM property | Environment | Default |
|---|---|---|
| `aisector.redis.host` | `AISECTOR_REDIS_HOST` | `127.0.0.1` |
| `aisector.redis.port` | `AISECTOR_REDIS_PORT` | `6379` |
| `aisector.redis.password` | `AISECTOR_REDIS_PASSWORD` | empty |
| `aisector.mongodb.uri` | `AISECTOR_MONGODB_URI` | `mongodb://127.0.0.1:27017` |
| `aisector.mongodb.database` | `AISECTOR_MONGODB_DATABASE` | `users` |

Nie umieszczaj prawdziwych haseł ani URI z credentials w repozytorium. `/msg` i `/reply` nie zostały aktywowane. Szczegóły: [RELEASE_NOTES.md](RELEASE_NOTES.md).

## Oryginalny opis projektu

Plugin proxy dla sieci serwerów aiSector
To jest oficjalny plugin-towarzysz dla aiSector (https://github.com/BusioMeister/aiSector, niezbędny do zarządzania komunikacją i synchronizacją danych w całej sieci serwerów.

O Projekcie
Ten plugin jest nierozłączną częścią większego ekosystemu aiSector. Jego zadaniem jest pełnienie roli centralnego systemu nerwowego, który koordynuje akcje między poszczególnymi serwerami (sektorami).

Podobnie jak główny plugin, ten projekt został w 99% stworzony przy pomocy zaawansowanych modeli sztucznej inteligencji (Gemini 2.5 Pro, ChatGPT 4.0 i 5.0). Jest to dowód na to, jak złożone, sieciowe systemy można budować poprzez iteracyjną współpracę z AI. Projekt jest w pełni darmowy i open-source.
