# Moonwalker

Aplicație Android care injectează locația GPS programatic (mock location), urmărind o
**serpentină generată live dintr-o formulă** — fără fișiere KML/GPX, fără limită de puncte.
Acoperă o zonă întreagă (bbox, județ sau poligon desenat) la rezoluție de hexagon, în buclă.

Gândită pentru deblocarea hărților de tip scratch-map (Țesătura/Bump). Fără root.

---

## Ce face

- **3 moduri de zonă**: ce e vizibil pe hartă (bbox), județ din listă (Iași inclus), sau desenezi un poligon
- **Serpentină E-V** la pasul hexagonului (parametrizabil) + point-in-polygon (nu iese din zonă)
- **Generare live** — calculează punctul următor din formulă, nu stochează nimic → suprafață nelimitată
- **Foreground service** — merge cu ecranul stins, ore/zile
- **Control**: viteză (km/h) și densitate (distanță între rânduri) din sliders
- **Loop** — când termină zona, o ia de la capăt

---

## Pasul 1 — pune pe GitHub

1. Creează un repo nou pe GitHub (ex. `moonwalker`), gol.
2. În folderul ăsta, rulează:
   ```bash
   git init
   git add .
   git commit -m "Moonwalker v1"
   git branch -M main
   git remote add origin https://github.com/UTILIZATORUL_TAU/moonwalker.git
   git push -u origin main
   ```

## Pasul 2 — compilare automată (cloud, fără PC)

- La fiecare push, **GitHub Actions** compilează APK-ul automat.
- Mergi pe GitHub → tab **Actions** → ultimul run → secțiunea **Artifacts** → descarcă **Moonwalker-debug-apk**.
- Dezarhivează → ai `app-debug.apk`.
- (Poți declanșa manual și din Actions → Build APK → *Run workflow*.)

## Pasul 3 — instalare pe telefon

1. Copiază `app-debug.apk` pe telefon, instalează-l (permite „surse necunoscute").
2. **Developer Options** → activează-le (Settings → About → apasă de 7× pe „Build number").
3. În Developer Options → **Select mock location app** → alege **Moonwalker**.
4. Dă-i permisiunea de locație când o cere.

## Pasul 4 — utilizare

1. Deschide Moonwalker.
2. Alege modul (bbox / județ / desen).
3. Reglează viteza și densitatea.
4. **START**. Verifică în Bump/Țesătura că poziția se mișcă și deblochează.

---

## Parametri recomandați (hexagon ~150m)

| Reglaj | Valoare | Note |
|---|---|---|
| Distanță rânduri | 130 m | acoperă tot fără goluri între rânduri |
| Pas pe rând (fix în cod) | 75 m | un punct sigur per hexagon |
| Viteză | 100–150 km/h | 200+ riscă fix-uri respinse de app |

## Adăugare alte județe

În `Counties.kt`, adaugă o intrare în `MAP` cu lista `[lat,lon]` a conturului.
(Iași e deja inclus, 215 vertice, contur real prin dizolvarea comunelor.)

## Limite / sinceritate tehnică

- Serpentina e **linie dreaptă**, nu pe șosele.
- Fără root: dacă o app verifică „isFromMockProvider", ar putea respinge. Bump nu o face (Lockito merge cu el).
- Conturul județului din listă e simplificat (~vertice reduse) — pentru graniță exactă, mărește rezoluția în Counties.kt.

## Personalizare ulterioară

- Pattern vertical N-S: în `MainActivity.startService()`, pune `EXTRA_VERTICAL = true`.
- Trecere automată prin mai multe județe la rând: se adaugă o coadă de zone în `MockService` (extensie ușoară).
