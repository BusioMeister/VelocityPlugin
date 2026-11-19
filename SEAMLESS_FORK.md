# Velocity bez ekranów — gałąź laboratoryjna

Baza: `PaperMC/Velocity` 4.3.1, commit `300f5d0823691c8c8bace0c91714c63ab52e0b77`.
`velocity-fork/seam-config-diagnostics.patch` to pierwszy etap zmian, nie gotowy
tryb seamless. Nakłada się go na wskazaną bazę Velocity, nie na ten plugin.
Do diagnostyki proxy należy podać `-Dvelocity.seam-diagnostics=true`.

## Prototyp oparty na InstantTransfer — sprawdzony na 26.3

Źródło koncepcji i patcha: [CodeAndCup/InstantTransfer](https://github.com/CodeAndCup/InstantTransfer),
commit `74e1be2b0fb798b24e74ec9496f99baf8e7aa190`, wskazany przez Bifu.

- `velocity-fork/build-instant.ps1 -OutputDirectory <nowy-katalog>` (JDK 25,
  Git, Maven) pobiera wskazane commity i buduje sprawdzony wariant silnika.
  Skrypt najpierw nakłada upstream `seamless.patch` z pominięciem `StateRegistry.java`,
  potem `instanttransfer-velocity-4.3.1-mc26.3-overlay.patch` na **PaperMC/Velocity**.
  Nie używa się razem z patchem diagnostycznym.
  Używa rzeczywistego ID `Respawn` dla 26.3 (`0x54`). Zero-screen jest opt-in
  przez `-Dvelocity.seamlessSwitch=true` dla klientów 26.3 między
  `GameWest` i `GameEast`; `-Dvelocity.seamlessSpawn=true` dołącza `Spawn`.
  Nie wymaga modyfikacji każdej komendy; działa na zatwierdzonych trasach
  backendowych, nie zmienia uprawnień ani blokady wyjścia ze `Spawn`.
- `velocity-fork/instanttransfer-plugin-mc26.3-overlay.patch` nakłada się na
  podany commit **InstantTransfer**. Na pierwszym powrocie klient zgłosił
  błąd `set_equipment`; modyfikacja zmienia tylko identyfikator encji,
  bez ponownego kodowania całej zawartości pakietu przez wrapper. Guard
  startuje dopiero po faktycznej zmianie backendu, nie po samym wniosku
  o połączenie. Timeout nie wysyła już teleportu na starą pozycję.
  Chunk docelowy odblokowuje gracza, ale usunięcie pozostałych starych
  chunków czeka 750 ms na dosłanie wspólnej części widoku.
  Ruch do nowego backendu czeka na potwierdzenie jego teleportu. Proxy nie
  wymusza lotu przy lądowaniu ani nie nadpisuje później umiejętności: wysyła
  tryb gry przed pakietem umiejętności z Paper. Agent nie przyznaje
  tymczasowego prawa lotu w odpowiedzi na `arrival`.
- W teście użyto PacketEvents **2.14.0**, które dodaje obsługę 26.3.
  Plugin proxy zbudowano przez `mvn -Dvelocity.version=4.3.1 -Dpacketevents.version=2.14.0 verify`.
  Agent Paper używa nieshade'owanego oryginału JAR + zewnętrznego PacketEvents,
  aby nie korzystać z osadzonego API 2.13.0. Oba JAR-y są potrzebne na
  każdym backendzie uczestniczącym w InstantTransfer, w tym na `Spawn`.
- `tools/runtime/instant_transfer_lab.py`: osobne kopie mapy, porty
  `25575–25577`, oficjalny klient 26.3, bez dotykania aktywnego proxy.
  Po poprawce oba przełączenia `/server` zarejestrowały wyłącznie `0 PLAY`
  (brak obu ekranów i rozłączenia). 36 testów jednostkowych InstantTransfer
  zaliczonych. Dowody: `instant-263-smoke-swd4v_ir/results.json` pod `%LOCALAPPDATA%/Temp/opencode`.

**Włączono lokalnie na `127.0.0.1:25565`.** `verify_instant_layout.py`
przeszedł na tej instancji: sześć rzeczywistych przekroczeń granicy (po trzy
w obu kierunkach), tylko `0 PLAY` w śladzie klienta, ten sam ekwipunek i XP,
poprawny border i pojedynczy bossbar. Nie zarejestrowano cofnięcia na źródłową
stronę po pierwszym kroku przez granicę. `/spawn`, `/tppos` i przycisk na
gąbce również dały `0 PLAY`; `/tppos` ze `Spawn` nadal nie pozwala wyjść.
Dowody: `aisector-layout-live-db7j3scu/RwSEast60a161-instant/results.json`.
Przed włączeniem zweryfikowano backup światów; zachowano świat i OP.
`start_layout.py --resume ... --instant` włącza fork; wznowienie bez `--instant`
wraca do oficjalnego Velocity i odkłada dodane JAR-y poza `plugins/`.
Nie testowano jeszcze otwartych GUI, walki ani dodatkowych
backendów. Bramka pary serwerów nie jest dowodem zgodności registry; nie
wdrażać tego na produkcji na podstawie samego lokalnego smoke.

1. W standardowym Velocity `LoginSessionHandler` wywołuje `doSwitch()` zaraz
   po zatwierdzeniu logowania backendu. Klient 26.3 otwiera wtedy
   `ServerReconfigScreen`. Fork ma utrzymać źródło w PLAY podczas sprawdzania
   konfiguracji celu. Pierwszy patch zbiera kontrolny fingerprint raw
   registry, tagów i ofert known packs; porównanie negatywne nie może
   dopuścić fast-path. Fingerprint pozytywny nadal nie jest wystarczający:
   trzeba porównać także uzgodnione pakiety klienta i wszystkie pozostałe
   elementy konfiguracji. Obecna diagnostyka nie zmienia routingu pakietów.
2. Klient 26.3 uruchamia `LevelLoadingScreen` po `JoinGame` oraz `Respawn`,
   nawet jeśli wymiar pozostał ten sam. Tryb bez ekranu nie może wysyłać
   żadnego z nich na zwykłym przejściu pomiędzy połówkami mapy. Potrzebna
   jest wymiana stanu w PLAY: stała tożsamość lokalnej encji gracza i
   translacja identyfikatorów, czyszczenie starych encji, spójny handoff
   chunków, pozycji, kontenerów, map, bossa, tablisty, inventory i bordera.
3. Ścieżka bez obu ekranów wymaga tego samego wymiaru protokołu, zgodnych
   rejestrów i paczek. Pierwsze logowanie pozostaje standardowe, a zmiana
   wymiaru nadal może pokazać ekran. Opt-in nie sprawdza zgodności pakietów.
4. Kryterium dopuszczenia: stockowy klient 26.3 w obu kierunkach, brak
   **obu** ekranów w `trace-seam`, natychmiastowa kontrola postaci, poprawne
   akcje na blokach i bytach, identyczny ekwipunek/XP i brak duchów po
   wielu przejściach; dodatkowo test rozłączenia i odrzucenia celu.

Nie podmieniać działającego proxy na samą diagnostykę.
