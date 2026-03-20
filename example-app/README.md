# Example app — capacitor-video-processor

Ce dossier n’est **pas** publié sur npm ; il sert à valider le plugin sur appareil ou simulateur.

## Prérequis

- Dépendances : à la racine du dépôt `npm install`, puis `cd example-app && npm install`
- Build du plugin : depuis la racine du dépôt, `npm run build`
- **Android** : `@capacitor/android` 8.x compile en **Java 21**. Le `settings.gradle` du module `android/` du plugin active le résolveur **Foojay** : Gradle peut télécharger un JDK 21 automatiquement si aucun n’est installé. Sinon, installez un JDK 21 (Temurin, etc.).

## Sync natif

```bash
cd example-app
npm run build
npx cap sync
```

Ouvrez le projet Android dans Android Studio ou le projet iOS dans Xcode, puis lancez l’app. Renseignez des chemins absolus vers une vidéo source et une destination (fichier sortie), puis appuyez sur **Compresser**.

Après `cap sync`, Android régénère `capacitor.plugins.json` : la classe enregistrée doit être `com.gme.plugins.capacitor.videoprocessor.VideoProcessorPlugin`.

## Vérifications depuis la racine du package

- `npm run verify:web` — build TypeScript + rollup
- `npm run verify:android` — `./gradlew` du module Android du plugin
- `npm run verify:ios` — `swift package resolve` (manifest SPM + dépendances)
