# Rewrite 2.0 — 2026-11-02

- Wspólny protokół/Mongo, bounded UUID queues oraz shutdown-safe, session-aware callbacki.
- Wybór preferowanego sektora przed pierwszym backend join, timeout i generation guard.
- Transfer save ACK z walidacją tokenu/UUID, cleanup po disconnect i ochrona przed stale callbackami.
- Poprawione TPA claim/expiry/failure paths, presence cache, ban/maintenance payload validation i global chat.
- `/msg` i `/reply` pozostają nieaktywne. Pozostałe granty i niepotwierdzone integracje nie zostały rozszerzone.

Zgodny workspace: **122 testy Maven** (shared 9 / Paper 92 / Velocity 21), zero failures/errors/skips, oraz 8 Python. Pełny smoke z 2 oficjalnymi klientami 26.3 obejmuje trzy backendy, inventory/XP/PDC, opóźniony source profile flush, mutation guard, home/TPA/spawn/reconnect, vanish/cold restart, generator, PvP, invsee, chat i backup restore/conflict.

Wdrażaj z aktualnym [aiSector](https://github.com/BusioMeister/aiSector), po backupie Mongo i światów, sprawdzeniu docelowej konfiguracji oraz forwardingu. Testy lokalne nie są wdrożeniem produkcyjnym. Mongo/Redis/świat nie tworzą jednej transakcji i nie gwarantują globalnego exactly-once przy crashu. Niejednoznaczne restore outcomes wymagają manual review. Rollback backendu musi zachować czytnik backupów v1/v2; nie przywracaj starszej bazy w ciemno.

Repozytorium zawiera źródłowy snapshot `shared` i parent POM, wrapper oraz skrypty samodzielnego budowania. Nie zawiera światów, stagingowej bazy, prywatnych credentials ani lokalnych danych klientów.
