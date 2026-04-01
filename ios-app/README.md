# IMPALA iOS (SwiftUI)

Ce dossier contient un point d'entree SwiftUI de reference (`IMPALA/`) pour:

- enregistrement de commande,
- prise en charge livreur,
- validation QR,
- validation paiement Airtel Money,
- historique et statut.

## Integration dans Xcode

1. Creer un projet iOS SwiftUI nomme `IMPALA`.
2. Remplacer le contenu des fichiers app/view par ceux de ce dossier.
3. Ajouter `ios-core` comme package Swift local:
   - `File > Add Package Dependencies...`
   - selectionner le chemin local `../ios-core`.
4. Activer Push Notifications + Background Modes selon l'environnement.
