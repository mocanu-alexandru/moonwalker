# Moonwalker

Aplicație Android care injectează locație GPS falsă (mock location) ca să **deblocheze automat
harta jocului [Bump](https://bumpapp.com/)** (`co.amo.android.location`) — un scratch-map social în
care „pictezi" hexagoanele pe unde treci. Moonwalker conduce singur prin lume după un model **„zbor +
aterizare"** și bifează țară după țară, sărind peste ce e deja deblocat.

> **Necesită root + un framework Xposed (LSPosed/Vector).** NU e o aplicație fără root — vezi de ce mai jos.

---

## Cuprins
1. [Cum funcționează](#cum-funcționează)
2. [Cerințe](#cerințe)
3. [Configurarea telefonului (pas cu pas)](#configurarea-telefonului-pas-cu-pas)
4. [Compilare](#compilare)
5. [Utilizare](#utilizare)
6. [Modul TUR — turul lumii](#modul-tur--turul-lumii)
7. [Limita Bump (de ce nu merge „instant")](#limita-bump-de-ce-nu-merge-instant)
8. [Depanare](#depanare)
9. [Structura proiectului](#structura-proiectului)

---

## Cum funcționează

1. **Injecție GPS** — Moonwalker se înregistrează ca *mock location provider* și injectează poziții
   prin `LocationManager` test providers (stil Lockito), pe providerele `gps` + `network`. GMS le
   fuzionează în provider-ul `fused`, de unde le citește Bump.

2. **Bypass-ul flagului de mock (Xposed)** — nucleul Bump e scris în **Rust** și **respinge** orice
   locație care are bitul „mock" setat (`"invalid position due to MockPosition"`). Bitul NU se citește
   prin `Location.isMock()`, ci direct din câmpul obiectului. De aceea e nevoie de modulul Xposed
   **`MWMockBypass`** (`xposedmock/`): hookuiește `Location.CREATOR.createFromParcel` ÎN procesul Bump
   și **șterge bitul de mock** (`mFieldsMask`) pe fiecare locație, înainte ca nucleul Rust să-l citească.
   Fără modul activ → Bump vede „mock" → nu pictează nimic.

3. **Citirea progresului real (root)** — Moonwalker citește direct baza de date a Bump
   (`footprint_spatial__v1`, celule H3 res-10) cu `su`, ca să știe exact ce e **deja deblocat**. Așa
   sare peste țările făcute (nu le reparcurge) și verifică dacă o țară chiar s-a pictat.

4. **Modelul „zbor + aterizare"** — Bump pictează doar mișcare *plauzibilă*. Turul:
   - **Zboară** între capitale la viteză „de avion" (~1000–1200 km/h, cu jitter per zbor) — sub
     plafonul de „warp" al Bump. Zborul pictează o dâră continuă pe sub.
   - **Aterizează** la fiecare capitală (serpentină lentă ~35 km/h) ca Bump să picteze sigur celula →
     țara bifată.
   - Verifică pe footprint-ul real că s-a pictat; dacă nu, mai aterizează o dată.

---

## Cerințe

**Telefon (ținta de rulare):**
- Android 8.1–14, **rootat cu [Magisk](https://github.com/topjohnwu/Magisk)**.
- Un framework Xposed cu Zygisk: **[LSPosed](https://github.com/JingMatrix/LSPosed)** sau forkul
  **Vector** (testat pe Vector). Instalat ca modul Magisk/Zygisk.
- **Bump** instalat și **logat** (același cont pe care vrei să-l deblochezi).
- Testat pe: **Samsung Galaxy Note 20 Ultra (SM-N986B), Android 13, Magisk + Vector**.

**Calculator (pentru compilare):**
- JDK 17 (ex. Temurin), Android SDK cu **platform-34** + **build-tools 34**.
- (Opțional) `adb` pentru instalare/loguri pe cablu.

---

## Configurarea telefonului (pas cu pas)

Trebuie două APK-uri: **`MoonWalker.apk`** (aplicația) și **`MWMockBypass.apk`** (modulul Xposed). Le
iei din [Releases](../../releases) (build CI) sau le compilezi local (vezi [Compilare](#compilare)).

### 1. Root
Instalează **Magisk** și verifică `su` (orice terminal: `su` → trebuie să dea shell root).

### 2. Framework Xposed
Instalează **LSPosed** sau **Vector** (ca modul Magisk/Zygisk), repornește. Deschide managerul lui și
confirmă că e **activ**.

### 3. Instalează cele două APK-uri
- `MoonWalker.apk` → aplicația principală.
- `MWMockBypass.apk` → modulul Xposed (apare în lista de aplicații ca **„MW Mock Bypass"**).
  (Permite „surse necunoscute".)

### 4. Activează + scopează modulul Xposed
În managerul LSPosed/Vector:
- **Modules** → activează **MW Mock Bypass**.
- La **Scope** bifează **Bump** (`co.amo.android.location`).
- **Force-stop Bump** (sau repornește telefonul) ca hookul să se încarce.
- Verificare: în logul LSPosed/Vector trebuie să apară `MWMock: hooks installed for Bump`.

### 5. Bump
Instalează **Bump**, loghează-te, dă-i permisiunea de **locație: Always** și **background location**.

### 6. Mock location → Moonwalker
- **Settings → About → apasă 7× pe „Build number"** → activează **Developer Options**.
- Developer Options → **Select mock location app** → **Moonwalker**.
- (Echivalent prin adb root: `appops set com.alexmcn.moonwalker android:mock_location allow`.)

### 7. Permisiuni + baterie
- La prima deschidere, dă Moonwalker **permisiunea de locație** (Fine + background).
- Scoate optimizarea de baterie pentru Moonwalker (rulează ore/zile cu ecranul stins).

### Checklist final
Bump logat ✓ · modul MW Mock Bypass activ + scope pe Bump ✓ (log „hooks installed") · mock location app
= Moonwalker ✓ · permisiuni locație ✓ · baterie neoptimizată ✓.

---

## Compilare

`local.properties` trebuie să indice SDK-ul: `sdk.dir=/calea/catre/Android/sdk` (fișier local, ignorat de git).

**Local:**
```bash
./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :xposedmock:assembleDebug   # → xposedmock/build/outputs/apk/debug/xposedmock-debug.apk
```
Gradle 8.9, JDK 17. Cele două module: `:app` (Moonwalker) și `:xposedmock` (MWMockBypass).

**În cloud (fără PC):** la fiecare push pe `main`, **GitHub Actions** (`.github/workflows/build.yml`)
compilează ambele APK-uri și publică un **Release** cu `MoonWalker.apk` + `MWMockBypass.apk`. Poți
declanșa manual din *Actions → Build APK → Run workflow*. Versionarea e dinamică (`1.0.<nr_commituri+58>`).

> Build-urile sunt semnate cu un keystore consistent din repo (`keystore/moonwalker-debug.p12`, debug,
> nu producție) → instalările „peste" merg fără dezinstalare.

---

## Utilizare

La deschidere aplicația **NU pornește nimic automat** — doar reîmprospătează masca de zone deblocate și
afișează harta. Pornirea e **manuală**:
- **Buton de jos** → mod **AUTO** (acoperă România din locația ta).
- **Buton TUR** → **turul lumii** (zbor + aterizare prin capitale).
- **Buton DIAGNOSTIC** → teste/măsurători, fără să acopere.

Merge cu ecranul stins (foreground service).

---

## Modul TUR — turul lumii

- **Setul de ținte:** ~192 de capitale (≈ toate statele ONU) din `Capitals.kt`.
- **„Gata" se derivă din footprint-ul REAL Bump** — orice capitală cu celula deja deblocată e bifată și
  **sărită** (zero reparcurgere). Lista hardcodată e doar fallback fără root.
- **Ordonare nearest-first** din poziția curentă reală → atacă întâi cele mai apropiate țări blocate.
- Pentru fiecare țară: **zbor** (~1000–1200 km/h, jitter per leg) → **aterizare lentă** (pictează) →
  verificare pe footprint → bifare. Buclează prin toate cele rămase.
- Fără teleport: totul e condus continuu (interpolat), cu timestamp-uri reale.

---

## Limita Bump (de ce nu merge „instant")

Nucleul Bump respinge mișcarea **implauzibilă** ca „warp" (`footprint.rs: ignore warped location`),
calculată din **Δpoziție / Δtimp real**. Consecințe dovedite:
- **~800 km/h → acceptat și pictează.** **1944 km/h → respins + ÎNGHEȚAT** (nu mai pictează deloc, până
  se reașează pe GPS real). Plafonul real e undeva între.
- **Câmpul `speed` e irelevant** (setat pe 0 → tot warp): contează mișcarea reală poziție/timp.
- **Timestamp-urile în viitor NU ajută** — GMS aruncă locațiile cu `elapsedRealtimeNanos` în viitor.
- Deci viteza de „zbor" e plafonată (~1000–1200 km/h, sub prag). Globul întreg ≈ **câteva zile** de
  rulare nesupravegheată. Nu există scurtătură mai rapidă fără să fie respins de Bump.

> Notă: mișcarea imposibil de rapidă pe glob poate atrage și flag server-side (FootprintSync). Modelul
> „zbor + aterizare" la viteză de avion e cel mai plauzibil compromis găsit.

---

## Depanare

| Simptom | Cauză probabilă | Rezolvare |
|---|---|---|
| Nu pictează nimic, în logul Bump apare `MockPosition` | Modulul Xposed nu e activ în Bump | Activează + scope pe Bump în LSPosed; force-stop Bump; verifică log `hooks installed` |
| Nu pictează, în log `ignore warped location` repetat | Viteză peste plafon → Bump înghețat | Oprește injecția (revino pe GPS real câteva minute) ca să se dezghețe; ține viteza ≤ ~800–1200 |
| Footprint plat dar fără `warped` | Ești în zonă **deja deblocată** | Normal — nu e nimic nou de pictat acolo |
| „0 deblocate" la pornirea TUR | Fără root / Bump nelogat / mască indisponibilă | Verifică `su`, Bump logat, DB accesibil |
| Poziția „sare" / se resetează | Reinstalare APK (repornește procesul) | Evită reinstalări cât rulează turul |

---

## Structura proiectului

```
app/                      Moonwalker (aplicația)
  MockService.kt          serviciul de injecție: AUTO, TUR (zbor+aterizare), diagnostic
  MainActivity.kt         UI minimal (hartă + butoane); pornire DOAR manuală
  UnlockedMask.kt         citește footprint-ul Bump (root + H3) → ce e deblocat
  Capitals.kt             capitalele lumii (ținte TUR)
  Counties.kt / Zone.kt / RouteGenerator.kt   geometrie & acoperire (mod AUTO)
xposedmock/               MWMockBypass (modul Xposed: șterge flagul de mock pt. Bump)
  MockHook.kt
.github/workflows/build.yml   CI: compilează + publică Release cu ambele APK-uri
```

> **Sinceritate tehnică:** Moonwalker e un instrument pentru deblocarea hărții Bump pe cont propriu, pe
> telefonul tău rootat. Depinde de structura internă a Bump (tabele, nucleu Rust) și de un framework
> Xposed — un update Bump poate cere ajustarea hookului.
