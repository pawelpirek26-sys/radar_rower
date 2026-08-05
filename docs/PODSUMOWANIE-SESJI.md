# Podsumowanie sesji — RadarRower (2026-08-01)

## Co powstało

Kompletna natywna aplikacja Android **RadarRower** (Kotlin + Jetpack Compose) —
wyświetlacz dla radaru rowerowego W100 (protokół BLE zgodny z Garmin Varia).
**Projekt buduje się do APK**
(`./gradlew assembleDebug` — zweryfikowane w tej sesji, build czysty, bez
ostrzeżeń kompilacji).

## Architektura (zgodnie z wymaganiami)

- minSdk 26, targetSdk 35, single-activity, Compose, dark theme domyślnie,
  orientacja pionowa.
- `RadarService` — ForegroundService typu `connectedDevice` z persistent
  notification (status: „Połączono — X aut z tyłu" / „Zerwane połączenie —
  ponawiam…"), partial wake lock, auto-reconnect z backoffem 1→2→4→8→16→30 s.
- MAC sparowanego urządzenia w DataStore (`SettingsRepository`), auto-połączenie
  przy starcie.
- `RadarRepository` (singleton, StateFlow/SharedFlow) — wspólny stan serwisu
  i UI: lista celów, poziom zagrożenia, zdarzenia alertowe, log debug.

## Protokół BLE — źródła (NIE zgadywany)

- Serwis `6A4E3200-667B-11E3-949A-0800200C9A66`, charakterystyka danych
  `6A4E3203-…`, subskrypcja przez CCCD 0x2902.
- Format pakietu wzięty z dwóch zgodnych źródeł open-source:
  1. [harbour-tacho / variaconnectivity.cpp](https://github.com/Wunderfitz/harbour-tacho/blob/master/src/variaconnectivity.cpp)
     (działająca aplikacja Sailfish OS dla Varii),
  2. [wątek reverse-engineeringu na forum Garmin](https://forums.garmin.com/developer/connect-iq/f/discussion/240452/bluetooth-profile-for-garmin-varia-rtl515)
     (autor tej samej implementacji, pomiary na realnym RTL515: dystans w metrach
     — max ~143 przy zasięgu 140 m; prędkość ≈ uint8 w km/h).
- Layout: `bajt 0` = licznik pakietu (górne 4 bity wspólne dla podzielonego
  payloadu), potem trójki `[ID celu, dystans m, prędkość km/h]`.
- Scalanie podzielonych payloadów: kontynuacja = ten sam górny półbajt licznika
  w oknie 500 ms (`RadarRepository.onRadarPacket`).

## ⚠️ Do weryfikacji na realnym W100 (następny krok)

Zgodnie z ustaleniem: **parser jest wersją wstępną** (format RTL515). W aplikacji
jest ekran **Debug** (ikona 🐞 na ekranie jazdy): log surowych pakietów hex
z timestampami + interpretacja parsera, przyciski Pauza/Wyczyść/Kopiuj.
Procedura: połączyć z W100 → poprosić kogoś o przejazd autem za rowerem →
skopiować log → porównać dystanse/prędkości z rzeczywistością. Dopiero po tej
weryfikacji finalizować parser (ewentualne różnice W100: kolejność bajtów
w trójce, jednostka prędkości, rola bajtu 0).

## UI / alerty

- Ekran jazdy: pionowy pas drogi (Canvas) — rowerzysta na dole, auta jako kropki
  z góry z dystansem w metrach; tło zielone/pomarańczowe/czerwone (próg
  prędkości czerwonego konfigurowalny suwakiem 20–120 km/h, domyślnie 50);
  wielki licznik aut / „CZYSTO"; keep-screen-on jako przełącznik.
- Alerty (działają z zgaszonym ekranem — gra je serwis):
  nowy pojazd = podwójny beep 880 Hz; czerwony = potrójny 1400 Hz; „czysto" =
  wznoszący akord 660→990 Hz. Dźwięki syntezowane AudioTrackiem (zero plików
  audio), strumień ALARM (niezależny od głośności mediów) lub MEDIA, opcjonalna
  własna głośność. Wibracje z odrębnymi wzorami per zdarzenie.
- Onboarding: runtime permissions (SCAN/CONNECT/POST_NOTIFICATIONS; na
  Androidzie <12 FINE_LOCATION) + `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

## Build i środowisko

- AGP 8.7.3, Kotlin 2.0.21 (+ plugin compose), Compose BOM 2024.12.01,
  Gradle wrapper 8.14.3 w repo. JDK 17+.
- `local.properties` (sdk.dir) — poza repo, `.gitignore` dodany.
- Zbudowany artefakt tej sesji: `app-debug.apk` ~16 MB (debug, niepodpisany
  kluczem release).

## Otwarte

1. Weryfikacja formatu pakietów na realnym W100 (ekran Debug) → finalizacja
   parsera.
2. Po weryfikacji: ewentualne czytanie charakterystyk 6A4E3201/3202 (jeśli W100
   je ma — na RTL515 służą do komend/stanu; nie są potrzebne do odczytu celów).
3. Ikona aplikacji jest wektorowym placeholderem (łuki radaru) — do podmiany,
   jeśli ma być ładniej.
4. Podpis release (keystore) — gdy apka pójdzie poza telefon developerski.

## Aktualizacja 2026-08-04 — wydzielenie do osobnego repozytorium

RadarRower to inna aplikacja niż mini-baselinker, więc projekt został odczepiony:
kod usunięty z repo mini-baselinker (PR #5 zamknięty bez merge'a) i przeniesiony
1:1 do tego samodzielnego repozytorium (projekt Gradle w korzeniu repo).

## Aktualizacja 2026-08-05 — kompletny UX (v0.2.0)

- Onboarding = checklista 2 kroków (uprawnienia, bateria) z odhaczaniem; krok
  baterii można świadomie pominąć (zapamiętane w DataStore).
- Skaner: obsługa wyłączonego Bluetootha (przycisk włączenia), pulsująca ikona,
  po 15 s karta podpowiedzi (radar zajęty przez Garmina itd.), „Skanuj od nowa",
  siła sygnału opisowo (blisko/średnio/daleko), wejście w Ustawienia zębatką.
- Ekran jazdy: bez połączenia tło neutralne + duży komunikat (ŁĄCZENIE…/
  PONAWIAM…/BRAK POŁĄCZENIA) — zielony nigdy nie udaje „czysto" bez radaru;
  przy kropkach aut obok dystansu także prędkość w km/h.
- Ustawienia: stan połączenia na żywo, „Zmień radar" (skan bez zapominania),
  sekcja „O aplikacji" z wersją.
- Debug: licznik pakietów w nagłówku (N/300).

## Aktualizacja 2026-08-05 — kompletny UX (v0.2.0)

- Onboarding = checklista 2 kroków (uprawnienia, bateria) z odhaczaniem; krok
  baterii można świadomie pominąć (zapamiętane w DataStore).
- Skaner: obsługa wyłączonego Bluetootha (przycisk włączenia), pulsująca ikona,
  po 15 s karta podpowiedzi (radar zajęty przez Garmina itd.), „Skanuj od nowa",
  siła sygnału opisowo (blisko/średnio/daleko), wejście w Ustawienia zębatką.
- Ekran jazdy: bez połączenia tło neutralne + duży komunikat (ŁĄCZENIE…/
  PONAWIAM…/BRAK POŁĄCZENIA) — zielony nigdy nie udaje „czysto" bez radaru;
  przy kropkach aut obok dystansu także prędkość w km/h.
- Ustawienia: stan połączenia na żywo, „Zmień radar" (skan bez zapominania),
  sekcja „O aplikacji" z wersją.
- Debug: licznik pakietów w nagłówku (N/300).

## Aktualizacja 2026-08-05 — obsługa słuchawek (v0.3.0)

Alerty grają w słuchawkach (BT/przewodowe/USB), gdy są podłączone:
- AudioTrack.setPreferredDevice kieruje dźwięk wprost do słuchawek — także przy
  strumieniu ALARM, który na części telefonów domyślnie gra tylko z głośnika;
- 300 ms ciszy rozbiegowej przed tonem, bo bezczynne słuchawki BT wybudzają się
  ułamek sekundy i ucinałyby początek beepu;
- przełącznik „Graj w słuchawkach, jeśli podłączone" (domyślnie ON); OFF =
  alert zawsze z głośnika telefonu, nawet przy podłączonych słuchawkach.
Urządzenie referencyjne usera: Pixel 9 Pro (Android 17 beta); wspierany zakres
bez zmian — minSdk 26 (Android 8.0+), targetSdk 35.

## Aktualizacja 2026-08-05 — obsługa innych radarów + bateria (v0.4.0)

- Skaner bez filtra systemowego: klasyfikacja w kodzie — serwis radarowy Varia
  („Protokół radaru ✓") > znana nazwa (Varia/RTL/RVR/RCT, Gardia, Magene/L508,
  iGPSPORT/SR30, CarBack, W100) > reszta; przełącznik „Pokaż wszystkie
  urządzenia BLE" dla nietypowych klonów, sort: pewne > po nazwie > RSSI.
- Ochrona przed złym wyborem: urządzenie bez serwisu radarowego po połączeniu
  dostaje stan INCOMPATIBLE (ekran „TO NIE RADAR", notyfikacja, bez pętli
  reconnect) — wystarczy Ustawienia → Zmień radar.
- Poziom baterii radaru ze standardowego serwisu BLE Battery (0x180F/0x2A19):
  odczyt po połączeniu + odświeżanie co 10 min; 🔋% na ekranie jazdy
  i w Ustawieniach. Wspólny dla wszystkich producentów.
