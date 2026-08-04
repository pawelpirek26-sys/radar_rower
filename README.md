# RadarRower

Natywna aplikacja Android (Kotlin + Jetpack Compose) — wyświetlacz dla radaru
rowerowego **W100** (protokół BLE zgodny z Garmin Varia). Pokazuje nadjeżdżające
z tyłu auta na pionowym „pasie drogi", alarmuje dźwiękiem i wibracją, działa
z zablokowanym ekranem i telefonem w kieszeni.

## Funkcje

- **Ekran jazdy**: pionowy pas drogi — Ty na dole, auta jako kropki zbliżające
  się z góry, przy każdej dystans w metrach. Kolor tła: 🟢 pusto / 🟠 auto
  w zasięgu / 🔴 auto zbliża się szybko (próg prędkości konfigurowalny).
  Duże elementy — czytelne kątem oka na kierownicy w słońcu.
- **Alerty**: dźwięk przy nowym wykrytym pojeździe, osobny pilniejszy dla stanu
  czerwonego, sygnał „czysto" gdy ostatnie auto przejedzie. Dźwięki syntezowane
  programowo (bez plików audio), strumień konfigurowalny (alarm/media),
  opcjonalna własna głośność aplikacji. Wibracje włącz/wyłącz.
- **Praca w tle**: ForegroundService (typ `connectedDevice`) + partial wake lock
  utrzymują połączenie BLE przy zgaszonym ekranie; persistent notification
  pokazuje status („Połączono — 2 auta z tyłu"). Auto-reconnect z backoffem
  1→30 s po zerwaniu połączenia. MAC sparowanego radaru zapamiętany w DataStore.
- **Ekran Debug**: log surowych pakietów hex z charakterystyki radarowej +
  wstępna interpretacja parsera (pauza / wyczyść / kopiuj do schowka) — do
  weryfikacji formatu na realnym W100.
- **Onboarding**: uprawnienia runtime (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`,
  `POST_NOTIFICATIONS`) + prośba o wyłączenie optymalizacji baterii
  (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
- Dark theme domyślnie, single-activity, keep-screen-on (przełącznik).

## Build

Wymagania:

- JDK **17+** (testowane na 21)
- Android SDK: platform **35** + build-tools **35.0.0** (albo po prostu Android Studio)

```bash
# wskaż SDK (jeśli nie masz ANDROID_HOME w środowisku):
echo "sdk.dir=/ścieżka/do/android-sdk" > local.properties

./gradlew assembleDebug
# wynik: app/build/outputs/apk/debug/app-debug.apk

# instalacja na telefonie (włączone debugowanie USB):
adb install app/build/outputs/apk/debug/app-debug.apk
```

Wersja release (do podpisania własnym kluczem): `./gradlew assembleRelease`.

- minSdk **26** (Android 8.0), targetSdk **35**
- AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, Gradle wrapper 8.14.3 (w repo)

## Protokół BLE (Varia / W100)

- Serwis radarowy: `6A4E3200-667B-11E3-949A-0800200C9A66`
- Charakterystyka danych (notyfikacje): `6A4E3203-667B-11E3-949A-0800200C9A66`
  (subskrypcja przez standardowy deskryptor CCCD `0x2902`)

Format pakietu według otwartych implementacji protokołu Varia
([harbour-tacho](https://github.com/Wunderfitz/harbour-tacho/blob/master/src/variaconnectivity.cpp),
[wątek reverse-engineeringu na forum Garmin](https://forums.garmin.com/developer/connect-iq/f/discussion/240452/bluetooth-profile-for-garmin-varia-rtl515) — zweryfikowany na realnym RTL515):

```
bajt 0            licznik pakietu; górne 4 bity wspólne dla części
                  podzielonego payloadu (>6 celów nie mieści się w 20 B)
bajty 1+3i…3+3i   trójka na cel: [ID celu, dystans (m, uint8), prędkość (km/h, uint8)]
```

⚠️ **Status parsera: wstępny.** Format przyjęty dla Garmin Varia RTL515; W100
deklaruje zgodność, ale przed uznaniem parsera za ostateczny zweryfikuj surowe
pakiety na ekranie **Debug** z realnym urządzeniem (ikona 🐞 na ekranie jazdy).

## Pierwsze uruchomienie

1. Przyznaj uprawnienia (ekran startowy) i wyłącz optymalizację baterii.
2. Włącz radar W100 → aplikacja skanuje i pokazuje znalezione radary → tapnij swój.
3. MAC zostaje zapamiętany; serwis łączy się automatycznie przy każdym starcie
   aplikacji i wznawia po zerwaniu połączenia.
4. Ustawienia (⚙): próg czerwonego alertu (km/h), dźwięki, strumień audio,
   głośność, wibracje, wygaszanie ekranu, zapomnienie urządzenia.

## Struktura kodu

```
app/src/main/java/com/radarrower/
├── MainActivity.kt          # single-activity, nawigacja, onboarding uprawnień
├── RadarApp.kt              # kanał notyfikacji
├── ble/
│   ├── VariaParser.kt       # parser pakietów radarowych (+ dokumentacja formatu)
│   ├── BleRadarClient.kt    # GATT: connect, subskrypcja notyfikacji
│   └── RadarScanner.kt      # skan BLE filtrowany po serwisie Varia
├── core/
│   ├── RadarRepository.kt   # wspólny stan (StateFlow): cele, zagrożenie, alerty, log debug
│   └── AlertPlayer.kt       # dźwięki syntezowane (AudioTrack) + wibracje
├── data/SettingsRepository.kt  # DataStore: MAC, próg, dźwięk, wibracje…
├── service/RadarService.kt  # foreground service, auto-reconnect z backoffem
└── ui/                      # Compose: RideScreen, ScanScreen, DebugScreen, SettingsScreen, Theme
```
