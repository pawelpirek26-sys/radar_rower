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

## Aktualizacja 2026-08-05 — głośność percepcyjna + brzmienia alertów (v0.5.0)

- Głośność: suwak działa teraz po krzywej sześciennej (gain liniowy przy 10%
  to ledwie -20 dB — stąd „za głośno"); amplituda syntezy 0.85→0.6, domyślna
  głośność 0.6, zakres suwaka od 5%. Feedback usera z realnego telefonu.
- Brzmienia alertów do wyboru (FilterChips w Ustawieniach, tapnięcie = odsłuch):
  „Beep" (dotychczasowe), „Klakson" (stos harmonicznych, urgent = ta-taaa),
  „Dzwonek" (nieharmoniczny alikwot + eksponencjalne wybrzmiewanie, ding-ding).
  Sygnał „czysto" w każdym brzmieniu łagodny.

## Aktualizacja 2026-08-05 — osobna głośność czerwonego + tryb demo (v0.6.0)

- Osobny suwak „Głośność czerwonego alertu" (domyślnie 90% vs 60% zwykłych) +
  przycisk „Testuj czerwony". Pilny alert może grać głośno, gdy zwykłe są ciche.
- Tryb demo (Ustawienia → Demo): DemoSimulator fabrykuje pakiety W FORMACIE
  VARIA i wpuszcza je w normalny pipeline (parser → tracker → alerty → UI →
  log Debug) — pełne doświadczenie apki bez radaru. Auta pojawiają się co
  kilka sekund (czasem kolumna dwóch, co ~4. szybkie = czerwony), demo
  zatrzymuje prawdziwy serwis na czas działania i przywraca go po wyłączeniu.

## Aktualizacja 2026-08-06 — retro mini-auta, motyw jasny/ciemny, tryb czuwania (v0.7.0)

- Auta na pasie drogi to teraz retro mini-samochody z góry (karoseria, szyby,
  koła — wzorowane na screenshocie usera); kolor auta per cel: pomarańczowe /
  czerwone gdy prędkość >= progu. Rowerzysta = pikselowy trójnik, linia
  środkowa = grube klocki.
- Motyw jasny/ciemny za systemem: RoadPalette (ciemna/jasna) + lightColorScheme
  + themes.xml/values-night. Jezdnia w jasnym celowo ciemna (kontrast aut).
- Tryb czuwania (ikona księżyca na ekranie jazdy lub Ustawienia → Ekran):
  po 10 s pustej drogi ekran gaśnie na czarno (OLED = piksele wyłączone),
  budzi się natychmiast po wykryciu auta przez radar; dotknięcie też budzi.
  Dźwięki i wibracje działają w czuwaniu normalnie (gra je serwis).

## Aktualizacja 2026-08-06 — orientacja „widok za plecami" (v0.7.1)

Feedback usera: radar patrzy DO TYŁU, a auta wyglądały jak jadące z naprzeciwka.
Odwrócona metafora ekranu: rowerzysta (trójnik + „TY") u GÓRY, auta wjeżdżają
od dołu (140 m z tyłu) i wspinają się ku niemu, przednią szybą w jego stronę —
doganiają. Lewy pas = wyprzedzanie (ruch prawostronny).

## Aktualizacja 2026-08-06 — kolory per auto + rowerzysta zamiast trójkąta (v0.7.2)

- Każde nadjeżdżające auto ma własny, stały kolor (paleta 6 kolorów po ID celu:
  pomarańcz/bursztyn/błękit/fiolet/cyjan/róż) — łatwo rozróżnić auta w kolumnie.
  Czerwony ZAREZERWOWANY dla aut powyżej progu prędkości (sygnał bezpieczeństwa).
- Trójnik rowerzysty zastąpiony pikselowym rowerem z góry: koła w linii, rama,
  kierownica, plecy i biały kask.

## Aktualizacja 2026-08-06 — rower bokiem z wyborem stylu (v0.8.0)

Rowerzysta u góry pokazywany Z BOKU (jak w innych apkach radarowych), styl
do wyboru w Ustawieniach → Ekran → „Twój pojazd na ekranie":
Gravel (domyślny, grubsze opony), Szosa (cienkie opony), Dziecinny (małe koła,
boczne kółka, wysoka kierownica, różowa chorągiewka), Moto (grube opony,
korpus z bakiem, wydech). Wszystko rysowane prymitywami Canvas, zero assetów.

## Aktualizacja 2026-08-06 — 5 rowerów, bez moto (v0.8.1)

Moto usunięte na życzenie usera. Pięć sylwetek rowerów: Gravel (grube opony,
baranek), Szosa (cienkie opony), MTB (płaska kierownica, amortyzowany widelec),
Miejski (damka: błotniki, cofnięta kierownica, koszyk), Dziecinny (boczne
kółka, chorągiewka). Zapamiętany styl "moto" spada na domyślny gravel.

## Aktualizacja 2026-08-06 — ochrona przed wyjściem + restart połączenia (v0.8.2)

- Ekran jazdy: cofnięcie nie zamyka apki od razu — toast „Naciśnij ponownie,
  aby wyjść (radar działa dalej w tle)", drugie cofnięcie w 2 s zamyka.
- Ustawienia → Urządzenie: przycisk „Restartuj połączenie" — ACTION_START
  serwisu zeruje backoff, zrywa bieżące połączenie GATT i łączy od świeża
  (bez zabijania serwisu).

## Aktualizacja 2026-08-06 — reorganizacja Ustawień + tryb ekranu (v0.9.0)

- Feedback usera: „Nie wygaszaj ekranu" i „Czuwanie" wzajemnie się wykluczały.
  Scalone w JEDEN wybór „Ekran podczas jazdy": Systemowy / Zawsze włączony /
  Czuwanie (chipy + opis aktywnego trybu). Migracja ze starych kluczy DataStore
  (standby > keepOn > system). Księżyc na ekranie jazdy przełącza keepOn↔standby.
- Ustawienia przeorganizowane w sekcje: Radar (urządzenie, stan, bateria,
  restart/zmiana/zapomnienie) → Ekran → Twój rower → Alerty (próg, dźwięki,
  brzmienie, strumień, słuchawki, głośności, testy, wibracje) → Bateria
  telefonu → Demo → O aplikacji. Opcje dźwięku chowają się przy wyłączonych
  dźwiękach; przyciski drugorzędne jako Outlined.
- „Rowery się nie zmieniają": różnice były za subtelne (grubość opon).
  Teraz: gravel = gruba opona z drobnym bieżnikiem (dash), MTB = bardzo gruby
  bieżnik terenowy, szosa = cienka gładka; do tego różna geometria/kierownice.

## Aktualizacja 2026-08-06 — diagnostyka uprawnień + test skanu (v0.9.1)

- Wspólny helper core/Permissions (required/hasNearby/hasNotifications/hasAll).
- Onboarding krok 1 pokazuje status per uprawnienie: ✓/✗ „Urządzenia w pobliżu"
  i ✓/✗ „Powiadomienia" + informację, że system zapyta o zgodę po przycisku.
- Ustawienia → nowa sekcja „Uprawnienia": statusy ✓/✗ obu uprawnień, przycisk
  „Przyznaj brakujące" oraz „Test wyszukiwania (5 s)" — realny 5-sekundowy skan
  BLE raportujący liczbę widzianych urządzeń (dowód, że uprawnienie DZIAŁA,
  a nie tylko figuruje jako przyznane).

## Aktualizacja 2026-08-06 — bateria w Uprawnieniach, globalna blokada wyjścia, ikona PNG (v0.9.2)

- Sekcja Uprawnienia ma teraz TRZY klikalne wiersze („zmień ›"): Urządzenia
  w pobliżu i Powiadomienia → ustawienia aplikacji w systemie; Bez optymalizacji
  baterii → systemowy dialog wyłączenia. Osobna sekcja „Bateria telefonu" scalona.
- Blokada przypadkowego wyjścia PRZENIESIONA na poziom AppRoot — działała tylko
  na ekranie jazdy, a przy niesparowanym radarze (skaner-korzeń) back wychodził
  od razu. Teraz podwójne cofnięcie obowiązuje na każdym ekranie głównym;
  podekrany (Ustawienia/Debug/skaner otwarty ręcznie) cofają do środka apki.
- Ikona: wygenerowane PNG (Pillow) we wszystkich gęstościach mdpi–xxxhdpi +
  ic_launcher_round + roundIcon w manifeście + adaptive round — ikona widoczna
  wszędzie, w tym na liście aplikacji w ustawieniach systemu.

## Aktualizacja 2026-08-06 — motyw w apce + widoczność roweru (v0.9.3)

- Ustawienia → Ekran: „Motyw aplikacji" (Jak system / Jasny / Ciemny) —
  LocalDarkMode w RadarRowerTheme, działa natychmiast (bez restartu), ikony
  pasków systemowych przełączane WindowInsetsControllerCompat. Wcześniej motyw
  szedł WYŁĄCZNIE za systemem — stąd „nie działał" w demo.
- Rower na ciemnym: koła/detale rysowały się prawie czarnym carWheel na ciemnej
  jezdni — teraz jasny roadLine (jezdnia ciemna w obu motywach = kontrast zawsze).

## Aktualizacja 2026-08-07 — fix łączenia z realnym W100 (v0.9.4)

User próbował sparować realny W100 („Radar67B2", rozpoznany po nazwie,
-94 dBm) — apka orzekła INCOMPATIBLE. Trzy przyczyny i fixy:
1. BUG GATT: requestMtu() + discoverServices() wołane NARAZ — operacje GATT
   są sekwencyjne, druga bywała gubiona → niepełna lista usług. Fix:
   requestMtu usunięte (pakiety ≤19 B mieszczą się w domyślnym MTU),
   discoverServices po 400 ms pauzy stabilizującej.
2. INCOMPATIBLE po JEDNEJ próbie — teraz 3 próby (backoff) zanim werdykt;
   licznik zerowany przy sukcesie i ręcznym restarcie.
3. Kontekst usera: radar równolegle połączony z licznikiem — większość
   klonów obsługuje TYLKO JEDNO połączenie BLE; licznik po BLE blokuje
   telefon (po ANT+ nie przeszkadza). Do testów: rozłączyć licznik.
   Dodatkowo -94 dBm = skrajnie słaby sygnał; parować blisko telefonu.
   Wskazówki wpisane w ekran INCOMPATIBLE.

## Aktualizacja 2026-08-07 — równoległa praca z licznikiem (v0.9.5)

User potwierdził (foto licznika GEOID z polem „Radar"): W100 obsługuje
RÓWNOCZEŚNIE licznik i telefon — oryginalna apka tak działa, RadarRower też ma.
Fix: connect(mac, autoConnect) — od 2. próby reconnect tryb cierpliwy
(autoConnect=true), bo radar zajęty drugim centralem rozgłasza się rzadko
i szybka próba bezpośrednia się timeoutowała. Rada „rozłącz licznik" z 0.9.4
wycofana — niepotrzebna po fixie GATT.

## Aktualizacja 2026-08-07 — cache GATT, diagnostyka usług, skan po nazwie (v0.9.6)

Licznik wyłączony, wciąż „brak serwisu radaru". Trzy nowe mechanizmy:
1. refresh() (ukryte API) czyści CACHE GATT przed odkrywaniem — Android
   zapamiętuje listę usług per adres i jedno historycznie zepsute odkrycie
   (wyścig z requestMtu sprzed 0.9.4) wracało z cache'u przy każdej próbie.
   Ręczna alternatywa: wyłącz/włącz Bluetooth w telefonie.
2. Werdykt INCOMPATIBLE dopisuje do logu Debug pełną listę usług, które
   urządzenie NAPRAWDĘ zgłosiło („DIAG: …") — screenshot z 🐞 pokaże,
   czy W100 mówi innym serwisem niż Varia.
3. Adres 40:… = resolvable private address (może rotować) — po 3 nieudanych
   próbach serwis skanuje po starym MAC LUB dokładnej nazwie (celowo nie po
   samym serwisie — w peletonie złapałby cudzy radar) i aktualizuje MAC.

## Aktualizacja 2026-08-07 — TRYB NASŁUCHU protokołu W100 (v0.9.7)

Test znikającego urządzenia potwierdził: „Radar67B2" (-31 dBm blisko) TO JEST
W100 (TUTULOO/MMWR), a mimo czystych danych apki nie wystawia serwisu Varia —
klon mówi WŁASNYM protokołem BLE (licznik GEOID działa zapewne po ANT+).
Nowy fallback: gdy serwisu Varia brak, apka subskrybuje WSZYSTKIE
charakterystyki notify/indicate urządzenia (sekwencyjna kolejka CCCD)
i zrzuca każdy pakiet do logu Debug jako „[uuid] hex". Stan pokazuje
„<nazwa> · nasłuch". Plan: user zbiera log z przejazdu auta → z hexów
odtwarzamy format W100 → parser dostaje drugi dialekt.

## Aktualizacja 2026-08-07 — pierwszy zrzut protokołu W100! (v0.9.8)

NASŁUCH DZIAŁA. W100 nadaje z własnej charakterystyki aa86ffe2-… co ~120-250 ms
ramki 8 B. Ramka spoczynkowa (brak pojazdów): 30 00 55 41 10 04 00 00.
Hipoteza robocza: b0=0x30 nagłówek, b1=liczba pojazdów (0), 55 41 10 04
status/wersja, 00 00 pole celów. Bateria czyta się ze standardowego 0x180F
(100% — czyli 0x55/0x41 to nie bateria). CZEKAMY na ramki z przejazdu auta.
v0.9.8: identyczne kolejne ramki zwijane do wpisu z licznikiem (×N) — ramki
spoczynkowe nie wypychają ciekawych; bufor Debug 300→1000; na ekranie jazdy
ostrzeżenie „tryb nasłuchu — alerty NIEAKTYWNE".

## 2026-08-08 — PROTOKÓŁ W100 ROZSZYFROWANY, parser wdrożony (v1.0.0)

Log nasłuchu z realnych przejazdów pozwolił zdekodować protokół W100
(TUTULOO/MMWR). Serwis `aa86ffe0-3884-465c-a034-c242988b0000`,
notyfikacje z `aa86ffe2-…`, ramka 8 B co ~120–250 ms:

```
b0=0x30 nagłówek | b1=maska celów (0x00 = pusto) | b2=0x00
b3 = licznik ramek (bity 0-5) + 2 najmłodsze bity dystansu celu 1 (bity 6-7)
b4 = dolny nibble: bity 2-5 dystansu celu 1; górny nibble: młodsze bity dystansu celu 2
b5 = 2 młodsze bity: starsze bity dystansu celu 2; górny nibble: cel 3
b6 = prędkość celu 1 w BCD (0x33 = 33 km/h) | b7 = prędkość celu 2 w BCD
```

🔑 **Jednostka dystansu = 3,125 m** (ta sama kwantyzacja co ANT+ Varia).
Weryfikacja fizyczna na 3 niezależnych przejazdach: dystanse maleją liniowo,
a prędkość policzona z Δdystans/Δczas zgadza się z bajtem prędkości
(72→62 m w 0,98 s = 36 km/h przy odczycie 33; 15,6→12,5 m w 0,24 s = 46
przy odczycie 43). Ramka spoczynkowa: `30 00 55 41 10 04 00 00`.

Wdrożenie: `W100Parser` + `RadarProtocol`; `BleRadarClient` wykrywa protokół
po odkryciu usług (Varia → W100 → nasłuch) i przekazuje go do repozytorium,
które routuje pakiety do właściwego parsera. Tryb nasłuchu zostaje dla
nieznanych urządzeń. Cele poza zasięgiem (>160 m) odsiewane jako błąd
dekodowania. Demo wymusza protokół Varia (fabrykuje ramki w tym formacie).

⚠ Do kalibracji w terenie: prędkość celu 2 (b7 bywa polem statusu — brana
tylko gdy ≥10 km/h, inaczej dziedziczy prędkość celu 1) i pole celu 3.

## 2026-08-08 — nowa nazwa: Uniwersalny Radar Rowerowy (v1.0.1)

Nazwa zmieniona na życzenie usera. Podział na dwa ciągi:
- `app_name` = „Radar Rowerowy" — etykieta pod ikoną (launchery ucinają dłuższe
  nazwy do „Uniwersalny…", co byłoby nieczytelne),
- `app_name_full` = „Uniwersalny Radar Rowerowy" — ekran powitalny i „O aplikacji".
applicationId ŚWIADOMIE bez zmian (com.radarrower) — zmiana utworzyłaby drugą
aplikację na telefonie i skasowała sparowanie oraz ustawienia. Teksty
przestały odwoływać się do konkretnego modelu radaru.

## 2026-08-08 — rewizja parsera W100 po logu z jazdy (v1.0.2)

Drugi, znacznie dłuższy log (11:32–11:34, realne przejazdy) wymusił korektę:

1. **BUG: ramki z nagłówkiem 0x31 były ODRZUCANE.** Radar nadaje dwie
   przeplatane serie (0x30 i 0x31) — parser wymagał dokładnie 0x30, więc
   połowa danych z gęstego ruchu przepadała. Teraz akceptowany cały 0x3X.
2. **Cele siedzą w RÓŻNYCH polach zależnie od slotu.** W jednym zdarzeniu
   porusza się pole A = (b4&0x0F)<<2 | b3>>6 przy zerowym B, w innym B = b3&0x3F
   przy nieruchomym A. Wcześniejsza wersja czytała tylko A → zdarzenia typu T1/E4
   pokazywały dystans 0 m (fałszywy „samochód tuż za tobą"). Teraz czytane są
   wszystkie trzy pola, z odsianiem duplikatów (±3 m) i zakresu.
3. **Kalibracja 3,125 m: potwierdzona w 2 z 4 zdarzeń** (2,99 i 2,89 m/jednostkę),
   w 2 wychodzi 2,0 i 6,0 — czyli bajt prędkości nie zawsze opisuje ten sam cel.
   Wcześniejsze „potwierdzenie fizyczne" opierało się na 2 próbkach i było
   przedwczesne. Pewne pozostaje: wykrycie pojazdu i licznik (ramka spoczynkowa
   30 00 55 41 10 04 00 00 jest jednoznaczna).

❗ Do domknięcia: pomiar ze ZNANYCH odległości (radar nieruchomy, cel 10/20/40 m)
— to jedyny sposób, by przypiąć jednostkę bez zgadywania z czasu.

## 2026-08-08 — bateria „100%" i kontekst testów w samochodzie (v1.0.3)

- Bateria: wartość pochodzi ze standardowego serwisu BLE 0x180F. User zgłasza,
  że radar raczej nie ma 100%. Każdy odczyt trafia teraz do logu Debug (DIAG) —
  stała wartość przez wiele godzin = radar zgłasza atrapę (typowe dla klonów).
  W Ustawieniach dopisek „(wg radaru)". Ramka spoczynkowa `30 00 55 41 10 04 00 00`
  była identyczna przez ~12 h, więc bajty 55/41/10/04 to też NIE bateria,
  tylko stała statusowa/wersja.
- ❗KLUCZOWY KONTEKST: dotychczasowe logi zbierane były W SAMOCHODZIE. To
  tłumaczy stałe tempo spadku dystansu (~3,07 jednostki/s) w wielu niezależnych
  zdarzeniach — to nie były różne pojazdy o różnych prędkościach, tylko
  najpewniej obiekty nieruchome mijane ze stałą prędkością auta. Dlatego
  kalibracja jednostki z Δdystans/Δczas dawała sprzeczne wyniki.
  Dane z roweru (i z pomiaru ze znanych odległości) będą rozstrzygające.

## 2026-08-08 — KALIBRACJA POTWIERDZONA + własne liczenie prędkości (v1.1.0)

Log przy NIERUCHOMYM obserwatorze rozstrzygnął kalibrację:
- pełny przejazd auta: pole A spada ciągle 24→1 jednostki = **75 m → 3 m**
  w 5,69 s, co przy 3,125 m/jednostkę daje 45,5 km/h — radar raportował
  55 km/h na początku i 45 km/h na końcu przejazdu. ✅
- cztery niezależne zdarzenia: przelicznik 2,60 / 2,99 / 3,55 / 3,32 m,
  średnio 3,11 m ≈ 3,125 m. Wcześniejsze sprzeczności wynikały z tego,
  że logi zbierano w jadącym samochodzie (mijane obiekty nieruchome).

Naprawa „czasem prędkość nie zgadza się":
- bajt prędkości bywa małym licznikiem (0x01–0x06) przy słabych śladach —
  takie wartości nie są już pokazywane jako km/h (dawały absurdalne „3 km/h"
  dla auta jadącego ~35 km/h),
- w zamian aplikacja **liczy prędkość sama** z tempa zbliżania się celu
  (punkt odniesienia przesuwany dopiero przy realnej zmianie dystansu,
  wynik wygładzany) — działa dla każdego śladu, niezależnie od firmware'u.

## 2026-08-08 — walidacja na rowerze (5 km) — parser działa

Trzy pełne przejazdy aut z realnej jazdy, wszystkie zdekodowane poprawnie:
- 119 m → 3 m w 16,1 s (zbliżanie 26 km/h) — pełna, ciągła krzywa,
- 75 m → 3 m w 12,5 s (21 km/h),
- 75 m → 38 m z DWOMA/TRZEMA celami naraz (pola A, B i C jednocześnie,
  każde malejące płynnie) — potwierdza, że wielocelowość działa.

🔑 ODKRYCIE SEMANTYCZNE: gdy radar podaje prędkość (b6 = 0x31 = 31 km/h),
jest to prędkość auta **względem drogi**. Prędkość liczona przez aplikację
ze zmiany dystansu to prędkość **zbliżania** (auto minus rower) — w tym
zdarzeniu 17 km/h. Różnica 14 km/h ≈ prędkość roweru usera. Obie wartości
są poprawne, ale to różne wielkości.
KONSEKWENCJA dla progu czerwonego alertu: przy szybkim aucie i szybkiej
jeździe prędkość zbliżania bywa DUŻO niższa niż prędkość auta, więc próg
ustawiony „jak dla prędkości auta" może nie zadziałać, gdy radar nie poda
własnego odczytu. Do decyzji usera: kompensacja prędkością roweru z GPS
(wymaga uprawnienia lokalizacji) albo przeetykietowanie progu na
„prędkość zbliżania".

## 2026-08-08 — ✅ dystans potwierdzony NIEZALEŻNIE licznikiem rowerowym

User zweryfikował odległość jednego auta z odczytem licznika (GEOID), który
czyta ten sam radar po ANT+. To potwierdzenie z niezależnego źródła i po
zupełnie innej ścieżce dekodowania — kalibracja 3,125 m jest TRAFNA, nie
tylko wewnętrznie spójna. Dekodowanie dystansu uznane za zamknięte.

Otwarte pozostaje jedynie: czy próg czerwonego alertu ma działać na prędkości
auta względem drogi (wymaga kompensacji GPS) czy na prędkości zbliżania.
