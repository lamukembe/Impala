# IMPALA - Application mobile de livraison

IMPALA est une base mobile multiplateforme pour la gestion de livraisons de colis:

- Android: Kotlin + Jetpack Compose (`android-app`)
- iOS: SwiftUI + Swift Package (`ios-app`, `ios-core`)

Le flux metier implemente respecte vos exigences:

1. creation de commande;
2. notification livreurs;
3. prise en charge (`PENDING` -> `IN_PROGRESS`);
4. validation QR (`WAITING_QR_VALIDATION`);
5. attente paiement Airtel Money (`WAITING_PAYMENT`);
6. completion (`COMPLETED`) et notification WhatsApp.

## Fonctionnalites incluses

- Authentification par session token.
- Creation de commande avec:
  - type de colis
  - poids
  - volume
  - lieu de collecte
  - adresse de livraison
  - telephone du destinataire
  - type de livraison (`URGENT`, `IMMEDIATE`, `NORMAL`, `DEFERRED`)
  - valeur du colis
- Synchronisation Dolibarr API.
- Notifications temps reel aux livreurs pour nouvelles commandes.
- Choix de commande par livreur.
- Verification QR code avant livraison.
- Validation paiement Airtel Money obligatoire avant completion.
- Notification WhatsApp automatique du client avec numero de commande.
- Mode hors-ligne:
  - file de commandes locales
  - reprise/synchronisation des actions quand le reseau revient
- Historique des commandes et suivi de statut.
- Gestion d'erreurs metier explicites.

## Structure du depot

- `android-app/shared-core`: coeur metier Kotlin testable (workflow, repository, offline, securite).
- `android-app/app`: UI Jetpack Compose (creation de commande, suivi, paiement, historique).
- `ios-core`: Swift Package avec coeur metier iOS et tests.
- `ios-app/IMPALA`: ecran SwiftUI de reference.

## Integration Dolibarr (adapter votre backend)

Le code utilise des interfaces (`DolibarrApi`, notifiers, payment gateway). Branchez ces interfaces vers votre backend securise.

Exemple d'API cible:

- `POST /api/index.php/orders` - creation
- `PATCH /api/index.php/orders/{id}` - changement de statut
- `GET /api/index.php/orders?status=pending` - liste commandes

Recommandations:

- mapper strictement les statuts IMPALA <-> Dolibarr;
- conserver l'idempotence sur updates statut;
- journaliser les echecs de synchro pour retry.

## Paiement Airtel Money

Le mobile ne doit **pas** valider directement la transaction finale.

Flux recommande:

1. mobile envoie `paymentReference` a votre backend;
2. backend appelle Airtel Money server-to-server;
3. backend retourne resultat signe;
4. mobile passe la commande en `COMPLETED` uniquement si validation positive.

## Notification WhatsApp

Declencher cote backend pour la fiabilite:

- message: `Votre commande IMPALA #<orderNumber> est confirmee.`
- envoi apres confirmation paiement et statut final `COMPLETED`.

## Securite

- HTTPS/TLS obligatoire.
- JWT/session token court + refresh.
- chiffrement local des donnees sensibles (token, references paiement).
- pinning certificat en production.
- signature et verification des webhooks entrants.

## Lancer les tests (coeur metier)

Android/Kotlin:

- `cd android-app`
- `./gradlew :shared-core:test`

iOS/Swift Package (sur macOS avec Swift installe):

- `cd ios-core`
- `swift test`
