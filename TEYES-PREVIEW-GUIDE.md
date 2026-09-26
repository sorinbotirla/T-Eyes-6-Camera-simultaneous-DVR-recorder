# Camera Tester 0.4.2 — preview prin serviciul TEYES

Instalează peste Camera Tester existent, fără dezinstalare. Datele și exportul ZIP rămân disponibile.

1. Deschide **Camera Tester**.
2. Din meniul hamburger, apasă **Show cameras**. Aplicația citește starea DVR și configurația canalelor; nu este nevoie de scanarea veche.
3. Grila afișează cele șase camere. Apasă o poziție pentru camera individuală; Back sau **Show cameras** revine la grilă.
4. Dacă o imagine lipsește sau nu corespunde poziției, deschide **Camera options → Choose channels** în meniul hamburger, alege canalul și salvează. Apoi repornește preview-ul. Sunt disponibile canalele 0–11 și opțiunea Auto.
5. Deschide **Export → Export reports** după test și salvează fișierul text pe stick. Nu este nevoie de o nouă arhivă cu APK-uri.

## Ce înseamnă rezultatele

- `Known DVR profile`: versiunea pachetului DVR corespunde APK-ului analizat. Nu garantează accesul la camere.
- `signal flags`: stările raportate de serviciul TEYES. Nu sunt imagini confirmate.
- `TEYES accepted ... waiting for frames`: apelul a fost acceptat, dar încă nu s-a primit imagine.
- `TEYES frames ... FPS`: TextureView a primit cadre. Abia acum sursa este salvată ca verificată.
- `Shared channel`: două poziții folosesc același canal efectiv. Imaginea este preluată o singură dată și repetată local; copia este limitată la aproximativ 10 FPS.
- `Auto channel unknown`: configurația nu a putut fi citită suficient pentru o sugestie; alege manual canalul.
- `No frames`, `denied`, `timed out`: mesajul exact este inclus în raport. Un apel acceptat nu este echivalent cu o cameră funcțională.

Pozițiile sunt sugestii bazate pe codul TEYES, nu o identificare vizuală automată. Codul 360 folosește 0 față / 1 spate / 2 stânga / 3 dreapta. În ADAS, configurația decide între 0 și 5 pentru față, respectiv 1 și 7 pentru spate. Canalul 7 poate fi redirecționat de serviciu; testerul citește și aplică această regulă când datele sunt disponibile. Când redirecționarea nu este cunoscută, canalul 7 poate fi testat individual; grila îl omite pentru a evita suprascrierea altui canal.

## Comportament și limite

Testează cu mașina parcată. Pornirea unui preview poate înlocui preview-ul TEYES de pe același canal. Testerul folosește identificatori proprii pentru oprire, însă implementarea vendor nu asigură izolarea completă între aplicații. După test, redeschide aplicația vendor dacă imaginea ei nu revine singură.

**Camera options → Stop preview**, ieșirea din ecran și trecerea aplicației în fundal trimit eliberarea preview-urilor proprii. La revenire în aplicație, pornește din nou afișarea. Dacă serviciul vendor blochează un apel Binder, UI rămâne disponibil; aplicația raportează timeout și trimite eliberarea după întoarcerea apelului. Un proces vendor blocat nu poate fi deblocat forțat fără privilegii.

Nu necesită root sau Developer Options. Nu modifică setările hardware, calibrarea, alimentarea camerelor sau configurația DVR. Nu încarcă biblioteci native TEYES în procesul testerului.

Grila încearcă simultan canalele distincte. Pe 11 septembrie 2026, versiunea 0.3.0 a fost verificată pe CC4 Pro al lui Sorin, cu firmware-ul său beta: toate cele șase panouri au afișat imagini; raportul confirmă 49 de porniri cu primirea primului cadru, inclusiv șapte porniri ale grilei complete. Sorin a confirmat pozițiile camerelor și funcționarea aplicațiilor originale DVR/360 după Stop/Exit. Maparea verificată pe acest dispozitiv este ADAS față 5 / spate 7 și 360 față 0 / stânga 2 / dreapta 3 / spate 1. Alte versiuni de firmware trebuie testate separat; funcționarea continuă a înregistrării în timpul preview-ului și stabilitatea pe termen lung nu au fost validate prin acest test.

## Implementare

Clientul folosește componenta exportată `com.spd.dvr/com.spd.dvr.service.DVRService`, descriptorul `com.spd.dvr.aidl.IDVRService`, tranzacția 22 pentru stare, 31 pentru preview Surface și 5/comanda 1012 pentru oprire cu același hash nenul. Tranzacțiile rulează într-o coadă separată de UI; eliberările unui ecran preced deschiderile ecranului următor.

Parserul folosește structura identificată în APK-ul extras (DVR versiune 13, cod 33). Pentru un firmware diferit, compatibilitatea trebuie reverificată. Dimensiunea bufferului cerut este 1280×720; FPS-ul măsoară actualizările imaginii în tester, nu performanța algoritmului ADAS sau rezoluția nativă garantată.

Nodurile V4L2 de control/dummy cunoscute sunt acum excluse din preview. O nouă scanare generală actualizează și metadatele vechilor rezultate.

În buildul 0.4.2 cu înregistrare în fundal, preview-ul este suspendat cât timp DVR deține camerele. Intră în Record și oprește înregistrarea înainte de a testa alte fluxuri. Ieșirea din preview nu oprește serviciul DVR.
